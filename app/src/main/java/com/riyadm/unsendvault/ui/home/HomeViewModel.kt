package com.riyadm.unsendvault.ui.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riyadm.unsendvault.capture.ListenerStatus
import com.riyadm.unsendvault.core.model.AppSummary
import com.riyadm.unsendvault.core.model.ArchivedMessage
import com.riyadm.unsendvault.core.model.Conversation
import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.core.model.StartTab
import com.riyadm.unsendvault.core.model.VaultStats
import com.riyadm.unsendvault.data.SettingsRepository
import com.riyadm.unsendvault.data.VaultRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HomeTab { RECENT, CONVERSATIONS, APPS }

fun StartTab.toHomeTab(): HomeTab = when (this) {
    StartTab.RECENT -> HomeTab.RECENT
    StartTab.CHATS -> HomeTab.CONVERSATIONS
    StartTab.APPS -> HomeTab.APPS
}

/** Capture health, in priority order: the first problem found is the one shown. */
enum class CaptureStatus { NO_ACCESS, PAUSED, DISCONNECTED, ACTIVE }

fun captureStatusOf(accessGranted: Boolean, captureEnabled: Boolean, connected: Boolean): CaptureStatus = when {
    !accessGranted -> CaptureStatus.NO_ACCESS
    !captureEnabled -> CaptureStatus.PAUSED
    !connected -> CaptureStatus.DISCONNECTED
    else -> CaptureStatus.ACTIVE
}

@Immutable
data class HomeUiState(
    val loading: Boolean = true,
    val tab: HomeTab = HomeTab.RECENT,
    val captureStatus: CaptureStatus = CaptureStatus.ACTIVE,
    val apps: List<AppSummary> = emptyList(),
    val selectedPackages: Set<String> = emptySet(),
    val recent: List<ArchivedMessage> = emptyList(),
    val conversations: List<Conversation> = emptyList(),
    val stats: VaultStats? = null,
    val appLockEnabled: Boolean = false,
    val selectedConversationId: Long? = null,
)

class HomeViewModel(
    private val vault: VaultRepository,
    private val settings: SettingsRepository,
    private val listenerStatus: ListenerStatus,
) : ViewModel() {
    /** Null until the user picks a tab: the saved start tab applies (each new launch gets a new ViewModel). */
    private val tab = MutableStateFlow<HomeTab?>(null)
    private val packageFilter = MutableStateFlow<Set<String>>(emptySet())
    private val accessGranted = MutableStateFlow(listenerStatus.isAccessGranted())
    private val selectedConversation = MutableStateFlow<Long?>(null)

    // Drops filters for apps whose data no longer exists so the list never silently shows nothing. Runs only while
    // [state] is collected (WhileSubscribed), not for the ViewModel's whole life: a stopped UI keeps no Room observer.
    private val apps = vault.observeAppSummaries().onEach { list ->
        val known = list.mapTo(HashSet()) { it.packageName }
        if (known.isNotEmpty()) packageFilter.update { f -> f.filterTo(LinkedHashSet()) { it in known } }
    }
    private val recent = packageFilter.flatMapLatest { vault.observeMessages(MessageQuery(packages = it, limit = RECENT_LIMIT)) }
    private val conversations = packageFilter.flatMapLatest { vault.observeConversations(it) }

    private val status = combine(accessGranted, listenerStatus.connected, settings.settings) { access, connected, s ->
        captureStatusOf(access, s.captureEnabled, connected) to s.appLockEnabled
    }

    private val effectiveTab = combine(tab, settings.settings) { picked, s -> picked ?: s.startTab.toHomeTab() }

    private val ui = combine(effectiveTab, packageFilter, selectedConversation) { t, f, c -> Triple(t, f, c) }

    val state: StateFlow<HomeUiState> = combine(
        ui, status, apps, combine(recent, conversations) { r, c -> r to c }, vault.observeStats(),
    ) { (t, filter, selectedConv), (capture, lock), appList, (messages, convs), stats ->
        val known = appList.mapTo(HashSet()) { it.packageName }
        HomeUiState(
            loading = false,
            tab = t,
            captureStatus = capture,
            apps = appList,
            selectedPackages = filter.filterTo(LinkedHashSet()) { it in known },
            recent = messages,
            conversations = convs,
            stats = stats,
            appLockEnabled = lock,
            selectedConversationId = selectedConv?.takeIf { id -> convs.any { it.id == id } },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState(tab = settings.current.startTab.toHomeTab()))

    fun selectTab(value: HomeTab) = tab.update { value }

    fun togglePackage(packageName: String) = packageFilter.update { if (packageName in it) it - packageName else it + packageName }

    fun clearPackageFilter() = packageFilter.update { emptySet() }

    /** From the Apps tab: show that app's conversations. */
    fun openApp(packageName: String) {
        packageFilter.value = setOf(packageName)
        tab.value = HomeTab.CONVERSATIONS
    }

    fun selectConversation(id: Long?) = selectedConversation.update { id }

    /** Notification access can only change in system settings; re-check whenever the screen resumes. */
    fun refreshAccess() {
        accessGranted.value = listenerStatus.isAccessGranted()
    }

    fun resumeCapture() {
        viewModelScope.launch { settings.update { it.copy(captureEnabled = true) } }
    }

    fun reconnect() {
        listenerStatus.requestRebind()
        refreshAccess()
    }

    fun accessSettingsIntent() = listenerStatus.accessSettingsIntent()

    companion object {
        const val RECENT_LIMIT = 300
    }
}
