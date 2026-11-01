package com.riyadm.unsendvault.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riyadm.unsendvault.apps.ProfileKind
import com.riyadm.unsendvault.capture.adapter.KnownApps
import com.riyadm.unsendvault.core.model.AppsToSave
import com.riyadm.unsendvault.core.model.AppSummary
import com.riyadm.unsendvault.core.model.StartTab
import com.riyadm.unsendvault.core.model.ThemeMode
import com.riyadm.unsendvault.core.model.VaultSettings
import com.riyadm.unsendvault.data.RetentionScheduler
import com.riyadm.unsendvault.data.SettingsRepository
import com.riyadm.unsendvault.data.VaultRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row of the exclusion picker: apps seen in the archive plus launcher apps, merged by package. */
@Immutable
data class ExclusionCandidate(
    val packageName: String,
    val label: String,
    val archivedMessages: Int,
    val excluded: Boolean,
    /** Where the app is installed; exclusion is by package, so it covers every profile. */
    val profile: ProfileKind = ProfileKind.PERSONAL,
    val alsoIn: Set<ProfileKind> = emptySet(),
    /** Ticked in "Only selected apps" mode. */
    val selected: Boolean = false,
    /** Sent chat-style notifications while not selected. */
    val isNew: Boolean = false,
)

@Immutable
data class SettingsUiState(
    val settings: VaultSettings? = null,
    val archiveApps: List<AppSummary> = emptyList(),
    val launcherApps: List<InstalledApp>? = null,
    val busy: Boolean = false,
    /** Labels of listed packages that have no launcher entry (selected, "new" or excluded apps). */
    val otherLabels: Map<String, String> = emptyMap(),
) {
    /** Archive apps first (they matter most), then other launcher apps; excluded apps always included. */
    val exclusionCandidates: List<ExclusionCandidate>
        get() {
            val excluded = settings?.excludedPackages.orEmpty()
            val byPackage = LinkedHashMap<String, ExclusionCandidate>()
            val installed = launcherApps.orEmpty().associateBy { it.packageName }
            archiveApps.sortedBy { it.appLabel.lowercase() }.forEach {
                val app = installed[it.packageName]
                // Archives made before other profiles were looked up may hold the package name as the label.
                val label = if (it.appLabel == it.packageName && app != null) app.label else it.appLabel
                byPackage[it.packageName] = ExclusionCandidate(
                    it.packageName, label, it.messageCount, it.packageName in excluded,
                    app?.profile ?: ProfileKind.PERSONAL, app?.alsoIn.orEmpty(),
                )
            }
            launcherApps.orEmpty().forEach {
                byPackage.putIfAbsent(it.packageName, ExclusionCandidate(it.packageName, it.label, 0, it.packageName in excluded, it.profile, it.alsoIn))
            }
            excluded.filterNot { it in byPackage }.sorted().forEach {
                byPackage[it] = ExclusionCandidate(it, otherLabels[it] ?: KnownApps.labelFor(it) ?: it, 0, true)
            }
            // Selected or "new" apps may have no launcher entry (or live in a profile we can't read): still list them.
            val selected = settings?.selectedPackages.orEmpty()
            val newApps = settings?.newApps.orEmpty()
            (selected + newApps).filterNot { it in byPackage }.forEach {
                byPackage[it] = ExclusionCandidate(it, otherLabels[it] ?: KnownApps.labelFor(it) ?: it, 0, it in excluded)
            }
            return byPackage.values.map { it.copy(selected = it.packageName in selected, isNew = it.packageName in newApps && it.packageName !in selected) }
        }
}

sealed interface SettingsEvent {
    /** Excluding an app that already has archived messages: offer to delete them too. */
    data class OfferDeleteAppData(val packageName: String, val label: String, val messageCount: Int) : SettingsEvent
    data class AppDataDeleted(val label: String) : SettingsEvent
    data class CleanupFinished(val deleted: Int) : SettingsEvent
    data object AllDataDeleted : SettingsEvent
    data class DemoDataInserted(val messages: Int) : SettingsEvent
}

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val vault: VaultRepository,
    private val retentionScheduler: RetentionScheduler,
    private val installedApps: InstalledAppsSource,
    private val demoDataInserter: (suspend () -> Int)? = null,
    /** Removes posted saved-message notifications (feature off, content hidden, app lock on, all data deleted). */
    private val cancelSavedNotifications: () -> Unit = {},
) : ViewModel() {
    private val launcherApps = MutableStateFlow<List<InstalledApp>?>(null)
    private val busy = MutableStateFlow(false)
    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events: Flow<SettingsEvent> = _events.receiveAsFlow()

    private val otherLabels = MutableStateFlow<Map<String, String>>(emptyMap())

    val state: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings, vault.observeAppSummaries(), launcherApps, busy, otherLabels,
    ) { s, apps, launcher, isBusy, labels -> SettingsUiState(s, apps, launcher, isBusy, labels) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /** Resolves names for listed packages without a launcher entry, once each (only while the app list is open). */
    private fun resolveOtherLabels(settings: VaultSettings, launcher: List<InstalledApp>) {
        val known = launcher.mapTo(HashSet()) { it.packageName }
        val missing = (settings.newApps + settings.selectedPackages + settings.excludedPackages)
            .filter { it !in known && it !in otherLabels.value }
        if (missing.isEmpty()) return
        viewModelScope.launch {
            val resolved = missing.mapNotNull { pkg -> runCatching { installedApps.labelFor(pkg) }.getOrNull()?.let { pkg to it } }.toMap()
            if (resolved.isNotEmpty()) otherLabels.value += resolved
        }
    }

    private fun update(transform: (VaultSettings) -> VaultSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    // Capture
    fun setCaptureEnabled(value: Boolean) = update { it.copy(captureEnabled = value) }
    fun setMessagesOnly(value: Boolean) = update { it.copy(messagesOnly = value) }

    private var appListJob: Job? = null

    /** Loads the installed apps once, then keeps names resolved for listed apps that aren't launchable. */
    fun loadLauncherApps() {
        if (appListJob != null) return
        appListJob = viewModelScope.launch {
            val launcher = launcherApps.value ?: runCatching { installedApps.launcherApps() }.getOrDefault(emptyList())
            launcherApps.value = launcher
            settingsRepository.settings.collect { resolveOtherLabels(it, launcher) } // new apps can appear while open
        }
    }

    fun setExcluded(candidate: ExclusionCandidate, excluded: Boolean) {
        viewModelScope.launch {
            settingsRepository.update {
                it.copy(excludedPackages = if (excluded) it.excludedPackages + candidate.packageName else it.excludedPackages - candidate.packageName)
            }
            if (excluded && candidate.archivedMessages > 0) {
                _events.send(SettingsEvent.OfferDeleteAppData(candidate.packageName, candidate.label, candidate.archivedMessages))
            }
        }
    }

    /**
     * Switching to "Only selected apps" for the first time pre-ticks the apps already in the archive and the known chat
     * apps installed on the phone (minus exclusions), so capture doesn't silently stop for the apps that worked so far.
     * Later switches keep whatever was ticked before.
     */
    fun setAppsToSave(mode: AppsToSave) {
        viewModelScope.launch {
            val seed = if (mode == AppsToSave.SELECTED_APPS && settingsRepository.settings.first().selectedPackages.isEmpty()) {
                val installed = launcherApps.value ?: runCatching { installedApps.launcherApps() }.getOrDefault(emptyList())
                    .also { launcherApps.value = it }
                val archived = vault.observeAppSummaries().first().map { it.packageName }
                (archived + installed.map { it.packageName }.filter { it in KnownApps.packages }).toSet()
            } else {
                emptySet()
            }
            settingsRepository.update { s ->
                s.copy(appsToSave = mode, selectedPackages = if (seed.isEmpty()) s.selectedPackages else seed - s.excludedPackages)
            }
        }
    }

    /** Ticks or unticks an app in "Only selected apps" mode. Ticking also lifts an old exclusion and the "New" mark. */
    fun setSelected(candidate: ExclusionCandidate, selected: Boolean) {
        viewModelScope.launch {
            settingsRepository.update {
                if (selected) {
                    it.copy(
                        selectedPackages = it.selectedPackages + candidate.packageName,
                        excludedPackages = it.excludedPackages - candidate.packageName,
                        newApps = it.newApps - candidate.packageName,
                    )
                } else {
                    it.copy(selectedPackages = it.selectedPackages - candidate.packageName)
                }
            }
            if (!selected && candidate.archivedMessages > 0) {
                _events.send(SettingsEvent.OfferDeleteAppData(candidate.packageName, candidate.label, candidate.archivedMessages))
            }
        }
    }

    fun deleteAppData(packageName: String, label: String) {
        viewModelScope.launch {
            vault.deleteApp(packageName)
            _events.send(SettingsEvent.AppDataDeleted(label))
        }
    }

    // Retention
    fun setRetentionDays(days: Int) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(retentionDays = days) }
            retentionScheduler.schedule(days)
        }
    }

    fun runCleanupNow() {
        viewModelScope.launch {
            busy.value = true
            val deleted = try {
                retentionScheduler.runNow()
            } finally {
                busy.value = false
            }
            _events.send(SettingsEvent.CleanupFinished(deleted))
        }
    }

    // Privacy
    fun setFilterOtp(value: Boolean) = update { it.copy(filterOtp = value) }
    fun setFilterSensitive(value: Boolean) = update { it.copy(filterSensitive = value) }
    fun setSecureScreen(value: Boolean) = update { it.copy(secureScreen = value) }

    /** Widget content is always hidden while the app lock is on. */
    fun setWidgetShowContent(value: Boolean) = update { if (it.appLockEnabled) it.copy(widgetShowContent = false) else it.copy(widgetShowContent = value) }

    // Security. The UI verifies the device credential before calling setAppLockEnabled(true).
    fun setAppLockEnabled(value: Boolean) {
        update {
            if (value) it.copy(appLockEnabled = true, widgetShowContent = false, notificationShowContent = false) else it.copy(appLockEnabled = false)
        }
        if (value) cancelSavedNotifications() // posted ones may show chat names and text
    }

    fun setStartTab(tab: StartTab) = update { it.copy(startTab = tab) }

    /** The screen asks for POST_NOTIFICATIONS first on Android 13+; this only stores the choice. */
    fun setSavedMessageNotifications(value: Boolean) {
        update { it.copy(savedMessageNotifications = value) }
        if (!value) cancelSavedNotifications()
    }

    /** Forced off while the app lock is enabled (same rule as widget content). */
    fun setNotificationShowContent(value: Boolean) {
        update { if (it.appLockEnabled) it.copy(notificationShowContent = false) else it.copy(notificationShowContent = value) }
        if (!value) cancelSavedNotifications()
    }

    fun setLockTimeout(seconds: Int) = update { it.copy(lockTimeoutSeconds = seconds) }

    // Appearance
    fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }
    fun setAmoledBlack(value: Boolean) = update { it.copy(amoledBlack = value) }
    fun setDynamicColor(value: Boolean) = update { it.copy(dynamicColor = value) }

    // Backup reminder
    fun setBackupReminderDays(days: Int) = update { it.copy(backupReminderDays = days) }

    // Data
    fun deleteAll() {
        viewModelScope.launch {
            busy.value = true
            try {
                vault.deleteAll()
            } finally {
                busy.value = false
            }
            cancelSavedNotifications()
            _events.send(SettingsEvent.AllDataDeleted)
        }
    }

    val canInsertDemoData: Boolean get() = demoDataInserter != null

    fun insertDemoData() {
        val inserter = demoDataInserter ?: return
        viewModelScope.launch {
            busy.value = true
            val count = try {
                inserter()
            } finally {
                busy.value = false
            }
            _events.send(SettingsEvent.DemoDataInserted(count))
        }
    }

    companion object {
        val RETENTION_OPTIONS = listOf(0, 7, 30, 90, 365)
        val REMINDER_OPTIONS = listOf(0, 7, 14, 30, 60)
    }
}
