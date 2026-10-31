package com.riyadm.unsendvault.ui.onboarding

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riyadm.unsendvault.capture.ListenerStatus
import com.riyadm.unsendvault.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OnboardingStep { WELCOME, LIMITS, PRIVACY, ACCESS, LOCK }

@Immutable
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val accessGranted: Boolean = false,
    val appLockEnabled: Boolean = false,
) {
    val isFirst: Boolean get() = step.ordinal == 0
    val isLast: Boolean get() = step.ordinal == OnboardingStep.entries.lastIndex
}

class OnboardingViewModel(
    private val settings: SettingsRepository,
    private val listenerStatus: ListenerStatus,
) : ViewModel() {
    private val step = MutableStateFlow(OnboardingStep.WELCOME)
    private val accessGranted = MutableStateFlow(listenerStatus.isAccessGranted())

    val state: StateFlow<OnboardingUiState> = combine(step, accessGranted, settings.settings) { s, access, prefs ->
        OnboardingUiState(s, access, prefs.appLockEnabled)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnboardingUiState(accessGranted = accessGranted.value))

    fun next() = step.update { current -> OnboardingStep.entries.getOrElse(current.ordinal + 1) { current } }

    fun back() = step.update { current -> OnboardingStep.entries.getOrElse(current.ordinal - 1) { current } }

    fun refreshAccess() {
        accessGranted.value = listenerStatus.isAccessGranted()
    }

    fun accessSettingsIntent() = listenerStatus.accessSettingsIntent()

    /** The UI verifies the device credential before enabling. */
    fun setAppLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.update { if (enabled) it.copy(appLockEnabled = true, widgetShowContent = false, notificationShowContent = false) else it.copy(appLockEnabled = false) }
        }
    }

    fun finish() {
        viewModelScope.launch { settings.update { it.copy(onboardingCompleted = true) } }
    }
}
