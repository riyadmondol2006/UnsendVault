package com.riyadm.unsendvault.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.riyadm.unsendvault.core.model.AppsToSave
import com.riyadm.unsendvault.core.model.StartTab
import com.riyadm.unsendvault.core.model.ThemeMode
import com.riyadm.unsendvault.core.model.VaultSettings
import com.riyadm.unsendvault.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

/** Process-wide Preferences DataStore ("settings"). A corrupt file is replaced with defaults instead of crashing. */
val Context.vaultSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * [SettingsRepository] on Preferences DataStore.
 *
 * - [settings] is authoritative: it emits the persisted values (defaults for missing keys; defaults if the file can't
 *   be read).
 * - [current] is a StateFlow snapshot started eagerly in [scope]. It holds [VaultSettings] defaults until DataStore's
 *   first read completes (normally a few ms after process start). Code that must not act on defaults — e.g. the
 *   listener deciding whether capture is paused right after a cold start — should use `settings.first()` instead.
 *
 * Invariant enforced on read: widgetShowContent and notificationShowContent are reported false while the app lock is
 * enabled.
 */
class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) : SettingsRepository {

    override val settings: Flow<VaultSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map(::read)
        .distinctUntilChanged()

    private val snapshot: StateFlow<VaultSettings> = settings.stateIn(scope, SharingStarted.Eagerly, VaultSettings())

    override val current: VaultSettings get() = snapshot.value

    override suspend fun update(transform: (VaultSettings) -> VaultSettings) {
        dataStore.edit { prefs -> write(prefs, transform(read(prefs))) }
    }

    private object Keys {
        val onboardingCompleted = booleanPreferencesKey("onboarding_completed")
        val captureEnabled = booleanPreferencesKey("capture_enabled")
        val excludedPackages = stringSetPreferencesKey("excluded_packages")
        val messagesOnly = booleanPreferencesKey("messages_only")
        val retentionDays = intPreferencesKey("retention_days")
        val filterOtp = booleanPreferencesKey("filter_otp")
        val filterSensitive = booleanPreferencesKey("filter_sensitive")
        val appLockEnabled = booleanPreferencesKey("app_lock_enabled")
        val lockTimeoutSeconds = intPreferencesKey("lock_timeout_seconds")
        val themeMode = stringPreferencesKey("theme_mode")
        val amoledBlack = booleanPreferencesKey("amoled_black")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val widgetShowContent = booleanPreferencesKey("widget_show_content")
        val lastExportAt = longPreferencesKey("last_export_at")
        val backupReminderDays = intPreferencesKey("backup_reminder_days")
        val secureScreen = booleanPreferencesKey("secure_screen")
        val startTab = stringPreferencesKey("start_tab")
        val savedMessageNotifications = booleanPreferencesKey("saved_message_notifications")
        val notificationShowContent = booleanPreferencesKey("notification_show_content")
        val appsToSave = stringPreferencesKey("apps_to_save")
        val selectedPackages = stringSetPreferencesKey("selected_packages")

        /** Comma-separated (package names never contain commas) to keep the order. */
        val newApps = stringPreferencesKey("new_apps")
    }

    companion object {
        private val DEFAULTS = VaultSettings()

        internal fun read(p: Preferences): VaultSettings {
            val appLock = p[Keys.appLockEnabled] ?: DEFAULTS.appLockEnabled
            return VaultSettings(
                onboardingCompleted = p[Keys.onboardingCompleted] ?: DEFAULTS.onboardingCompleted,
                captureEnabled = p[Keys.captureEnabled] ?: DEFAULTS.captureEnabled,
                excludedPackages = p[Keys.excludedPackages] ?: DEFAULTS.excludedPackages,
                messagesOnly = p[Keys.messagesOnly] ?: DEFAULTS.messagesOnly,
                retentionDays = (p[Keys.retentionDays] ?: DEFAULTS.retentionDays).coerceAtLeast(0),
                filterOtp = p[Keys.filterOtp] ?: DEFAULTS.filterOtp,
                filterSensitive = p[Keys.filterSensitive] ?: DEFAULTS.filterSensitive,
                appLockEnabled = appLock,
                lockTimeoutSeconds = (p[Keys.lockTimeoutSeconds] ?: DEFAULTS.lockTimeoutSeconds).coerceAtLeast(0),
                themeMode = p[Keys.themeMode]?.let { n -> ThemeMode.entries.firstOrNull { it.name == n } } ?: DEFAULTS.themeMode,
                amoledBlack = p[Keys.amoledBlack] ?: DEFAULTS.amoledBlack,
                dynamicColor = p[Keys.dynamicColor] ?: DEFAULTS.dynamicColor,
                widgetShowContent = (p[Keys.widgetShowContent] ?: DEFAULTS.widgetShowContent) && !appLock,
                lastExportAt = p[Keys.lastExportAt],
                backupReminderDays = (p[Keys.backupReminderDays] ?: DEFAULTS.backupReminderDays).coerceAtLeast(0),
                secureScreen = p[Keys.secureScreen] ?: DEFAULTS.secureScreen,
                startTab = p[Keys.startTab]?.let { n -> StartTab.entries.firstOrNull { it.name == n } } ?: DEFAULTS.startTab,
                savedMessageNotifications = p[Keys.savedMessageNotifications] ?: DEFAULTS.savedMessageNotifications,
                notificationShowContent = (p[Keys.notificationShowContent] ?: DEFAULTS.notificationShowContent) && !appLock,
                appsToSave = p[Keys.appsToSave]?.let { n -> AppsToSave.entries.firstOrNull { it.name == n } } ?: DEFAULTS.appsToSave,
                selectedPackages = p[Keys.selectedPackages] ?: DEFAULTS.selectedPackages,
                newApps = p[Keys.newApps]?.split(',')?.filter { it.isNotBlank() }?.distinct()?.takeLast(VaultSettings.MAX_NEW_APPS)
                    ?: DEFAULTS.newApps,
            )
        }

        internal fun write(p: MutablePreferences, s: VaultSettings) {
            p[Keys.onboardingCompleted] = s.onboardingCompleted
            p[Keys.captureEnabled] = s.captureEnabled
            p[Keys.excludedPackages] = s.excludedPackages
            p[Keys.messagesOnly] = s.messagesOnly
            p[Keys.retentionDays] = s.retentionDays.coerceAtLeast(0)
            p[Keys.filterOtp] = s.filterOtp
            p[Keys.filterSensitive] = s.filterSensitive
            p[Keys.appLockEnabled] = s.appLockEnabled
            p[Keys.lockTimeoutSeconds] = s.lockTimeoutSeconds.coerceAtLeast(0)
            p[Keys.themeMode] = s.themeMode.name
            p[Keys.amoledBlack] = s.amoledBlack
            p[Keys.dynamicColor] = s.dynamicColor
            p[Keys.widgetShowContent] = s.widgetShowContent && !s.appLockEnabled
            if (s.lastExportAt != null) p[Keys.lastExportAt] = s.lastExportAt else p.remove(Keys.lastExportAt)
            p[Keys.backupReminderDays] = s.backupReminderDays.coerceAtLeast(0)
            p[Keys.secureScreen] = s.secureScreen
            p[Keys.startTab] = s.startTab.name
            p[Keys.savedMessageNotifications] = s.savedMessageNotifications
            p[Keys.notificationShowContent] = s.notificationShowContent && !s.appLockEnabled
            p[Keys.appsToSave] = s.appsToSave.name
            p[Keys.selectedPackages] = s.selectedPackages
            p[Keys.newApps] = s.newApps.filter { it.isNotBlank() && ',' !in it }.distinct().takeLast(VaultSettings.MAX_NEW_APPS).joinToString(",")
        }
    }
}
