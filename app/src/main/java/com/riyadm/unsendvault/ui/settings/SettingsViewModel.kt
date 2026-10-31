package com.riyadm.unsendvault.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riyadm.unsendvault.core.model.AppSummary
import com.riyadm.unsendvault.core.model.StartTab
import com.riyadm.unsendvault.core.model.ThemeMode
import com.riyadm.unsendvault.core.model.VaultSettings
import com.riyadm.unsendvault.data.RetentionScheduler
import com.riyadm.unsendvault.data.SettingsRepository
import com.riyadm.unsendvault.data.VaultRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
)

@Immutable
data class SettingsUiState(
    val settings: VaultSettings? = null,
    val archiveApps: List<AppSummary> = emptyList(),
    val launcherApps: List<InstalledApp>? = null,
    val busy: Boolean = false,
) {
    /** Archive apps first (they matter most), then other launcher apps; excluded apps always included. */
    val exclusionCandidates: List<ExclusionCandidate>
        get() {
            val excluded = settings?.excludedPackages.orEmpty()
            val byPackage = LinkedHashMap<String, ExclusionCandidate>()
            archiveApps.sortedBy { it.appLabel.lowercase() }.forEach {
                byPackage[it.packageName] = ExclusionCandidate(it.packageName, it.appLabel, it.messageCount, it.packageName in excluded)
            }
            launcherApps.orEmpty().forEach {
                byPackage.putIfAbsent(it.packageName, ExclusionCandidate(it.packageName, it.label, 0, it.packageName in excluded))
            }
            excluded.filterNot { it in byPackage }.sorted().forEach {
                byPackage[it] = ExclusionCandidate(it, it, 0, true)
            }
            return byPackage.values.toList()
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

    val state: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings, vault.observeAppSummaries(), launcherApps, busy,
    ) { s, apps, launcher, isBusy -> SettingsUiState(s, apps, launcher, isBusy) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private fun update(transform: (VaultSettings) -> VaultSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    // Capture
    fun setCaptureEnabled(value: Boolean) = update { it.copy(captureEnabled = value) }
    fun setMessagesOnly(value: Boolean) = update { it.copy(messagesOnly = value) }

    fun loadLauncherApps() {
        if (launcherApps.value != null) return
        viewModelScope.launch { launcherApps.value = runCatching { installedApps.launcherApps() }.getOrDefault(emptyList()) }
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
