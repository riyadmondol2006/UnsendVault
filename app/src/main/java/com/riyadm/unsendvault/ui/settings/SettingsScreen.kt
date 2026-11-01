package com.riyadm.unsendvault.ui.settings

import android.content.res.Resources
import androidx.compose.ui.platform.LocalResources
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.core.model.AppsToSave
import com.riyadm.unsendvault.core.model.StartTab
import com.riyadm.unsendvault.core.model.ThemeMode
import com.riyadm.unsendvault.core.model.VaultSettings
import com.riyadm.unsendvault.data.BackupResult
import com.riyadm.unsendvault.data.ImportMode
import com.riyadm.unsendvault.security.lock.AppLockStateMachine
import com.riyadm.unsendvault.security.lock.AppLockSwitchRow
import com.riyadm.unsendvault.ui.common.LocalExternalActivityGuard
import com.riyadm.unsendvault.ui.common.appDetailsIntent
import com.riyadm.unsendvault.ui.common.rememberTimeFormatter
import com.riyadm.unsendvault.ui.common.startFirstAvailable
import com.riyadm.unsendvault.ui.components.AppScaffold
import com.riyadm.unsendvault.ui.components.ChoiceDialog
import com.riyadm.unsendvault.ui.components.ConfirmDialog
import com.riyadm.unsendvault.ui.components.InfoText
import com.riyadm.unsendvault.ui.components.SectionHeader
import com.riyadm.unsendvault.ui.components.SwitchRow
import com.riyadm.unsendvault.ui.components.ValueRow
import com.riyadm.unsendvault.ui.theme.supportsDynamicColor

object SettingsTestTags {
    const val LIST = "settings_list"
    const val CAPTURE = "settings_capture"
    const val OTP = "settings_otp"
    const val WIDGET = "settings_widget"
    const val SECURE = "settings_secure"
    const val RETENTION = "settings_retention"
    const val DELETE_ALL = "settings_delete_all"
    const val EXPORT = "settings_export"
    const val START_TAB = "settings_start_tab"
    const val APPS_TO_SAVE = "settings_apps_to_save"
    const val SELECTED_APPS = "settings_selected_apps"
}

private enum class SettingsDialog { NONE, APPS_TO_SAVE, RETENTION, TIMEOUT, THEME, START_TAB, REMINDER, DELETE_ALL }

@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel,
    backupViewModel: BackupViewModel,
    accessSettingsIntent: () -> Intent,
    onOpenExclusions: () -> Unit,
    onOpenSelectedApps: () -> Unit = {},
    onOpenReliability: () -> Unit,
    onOpenAbout: () -> Unit,
    showDeveloperOptions: Boolean,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val backupState by backupViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val guard = LocalExternalActivityGuard.current
    val snackbar = remember { SnackbarHostState() }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        backupViewModel.onExportDestination(it)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        backupViewModel.onImportSource(it)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val res = resources
            when (event) {
                is SettingsEvent.CleanupFinished -> snackbar.showSnackbar(res.getQuantityString(R.plurals.cleanup_finished, event.deleted, event.deleted))
                SettingsEvent.AllDataDeleted -> snackbar.showSnackbar(res.getString(R.string.settings_all_deleted))
                is SettingsEvent.DemoDataInserted -> snackbar.showSnackbar(res.getQuantityString(R.plurals.demo_inserted, event.messages, event.messages))
                is SettingsEvent.AppDataDeleted -> snackbar.showSnackbar(res.getString(R.string.exclusions_data_deleted, event.label))
                is SettingsEvent.OfferDeleteAppData -> Unit // handled on the exclusions screen
            }
        }
    }
    LaunchedEffect(backupViewModel) {
        backupViewModel.events.collect { event ->
            when (event) {
                is BackupEvent.PickExportDestination -> {
                    guard()
                    runCatching { exportLauncher.launch(event.suggestedName) }.onFailure { backupViewModel.onExportDestination(null) }
                }
                BackupEvent.PickImportSource -> {
                    guard()
                    runCatching { importLauncher.launch(arrayOf("*/*")) }
                }
                BackupEvent.PasswordLost -> snackbar.showSnackbar(resources.getString(R.string.backup_password_lost))
                is BackupEvent.Finished -> snackbar.showSnackbar(backupResultMessage(resources, event.operation, event.result))
            }
        }
    }

    SettingsScreen(
        state = state,
        backupState = backupState,
        snackbarHostState = snackbar,
        viewModel = viewModel,
        backupViewModel = backupViewModel,
        onOpenAccessSettings = {
            guard()
            context.startFirstAvailable(accessSettingsIntent(), Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), context.appDetailsIntent())
        },
        onOpenExclusions = onOpenExclusions,
        onOpenSelectedApps = onOpenSelectedApps,
        onOpenReliability = onOpenReliability,
        onOpenAbout = onOpenAbout,
        showDeveloperOptions = showDeveloperOptions && viewModel.canInsertDemoData,
    )
}

internal fun backupResultMessage(res: Resources, operation: BackupOperation, result: BackupResult): String {
    return when (result) {
        is BackupResult.Success -> {
            val messages = res.getQuantityString(R.plurals.stats_messages, result.messageCount, result.messageCount)
            val conversations = res.getQuantityString(R.plurals.stats_conversations, result.conversationCount, result.conversationCount)
            res.getString(if (operation == BackupOperation.EXPORT) R.string.backup_export_success else R.string.backup_import_success, messages, conversations)
        }
        is BackupResult.Failure -> res.getString(
            when (result.reason) {
                BackupResult.Reason.WRONG_PASSWORD -> R.string.backup_error_wrong_password
                BackupResult.Reason.CORRUPT_FILE -> R.string.backup_error_corrupt
                BackupResult.Reason.UNSUPPORTED_VERSION -> R.string.backup_error_version
                BackupResult.Reason.IO_ERROR -> R.string.backup_error_io
                BackupResult.Reason.EMPTY_PASSWORD -> R.string.backup_error_empty_password
            },
        )
    }
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    backupState: BackupUiState,
    viewModel: SettingsViewModel,
    backupViewModel: BackupViewModel,
    onOpenAccessSettings: () -> Unit,
    onOpenExclusions: () -> Unit,
    onOpenReliability: () -> Unit,
    onOpenAbout: () -> Unit,
    showDeveloperOptions: Boolean,
    snackbarHostState: SnackbarHostState? = null,
    onOpenSelectedApps: () -> Unit = {},
) {
    var dialog by rememberSaveable { mutableStateOf(SettingsDialog.NONE) }
    val formatter = rememberTimeFormatter()
    AppScaffold(title = stringResource(R.string.settings_title), snackbarHostState = snackbarHostState) { padding ->
        val s = state.settings ?: return@AppScaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .testTag(SettingsTestTags.LIST),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                // Capture
                SectionHeader(stringResource(R.string.settings_section_capture))
                SwitchRow(
                    title = stringResource(R.string.settings_capture_enabled),
                    summary = stringResource(if (s.captureEnabled) R.string.settings_capture_enabled_on else R.string.settings_capture_enabled_off),
                    checked = s.captureEnabled,
                    onCheckedChange = viewModel::setCaptureEnabled,
                    icon = Icons.Outlined.PauseCircle,
                    modifier = Modifier.testTag(SettingsTestTags.CAPTURE),
                )
                SwitchRow(
                    title = stringResource(R.string.settings_messages_only),
                    summary = stringResource(R.string.settings_messages_only_summary),
                    checked = s.messagesOnly,
                    onCheckedChange = viewModel::setMessagesOnly,
                    icon = Icons.Outlined.Apps,
                )
                ValueRow(
                    title = stringResource(R.string.settings_apps_to_save),
                    value = stringResource(if (s.appsToSave == AppsToSave.SELECTED_APPS) R.string.apps_to_save_selected else R.string.apps_to_save_all),
                    icon = Icons.Outlined.Checklist,
                    onClick = { dialog = SettingsDialog.APPS_TO_SAVE },
                    modifier = Modifier.testTag(SettingsTestTags.APPS_TO_SAVE),
                )
                if (s.appsToSave == AppsToSave.SELECTED_APPS) {
                    val newCount = s.newApps.count { it !in s.selectedPackages }
                    ValueRow(
                        title = stringResource(R.string.settings_selected_apps),
                        value = if (newCount > 0) {
                            pluralStringResource(R.plurals.selected_apps_count_with_new, s.selectedPackages.size, s.selectedPackages.size, newCount)
                        } else {
                            pluralStringResource(R.plurals.selected_apps_count, s.selectedPackages.size, s.selectedPackages.size)
                        },
                        icon = Icons.Outlined.Apps,
                        onClick = onOpenSelectedApps,
                        modifier = Modifier.testTag(SettingsTestTags.SELECTED_APPS),
                    )
                } else {
                    ValueRow(
                        title = stringResource(R.string.settings_excluded_apps),
                        value = if (s.excludedPackages.isEmpty()) {
                            stringResource(R.string.settings_excluded_none)
                        } else {
                            pluralStringResource(R.plurals.settings_excluded_count, s.excludedPackages.size, s.excludedPackages.size)
                        },
                        icon = Icons.Outlined.Block,
                        onClick = onOpenExclusions,
                    )
                }
                ValueRow(
                    title = stringResource(R.string.settings_notification_access),
                    value = stringResource(R.string.settings_notification_access_summary),
                    icon = Icons.Outlined.NotificationsActive,
                    onClick = onOpenAccessSettings,
                )

                NotificationSettingsSection(s, viewModel)

                // Retention
                SectionHeader(stringResource(R.string.settings_section_retention))
                ValueRow(
                    title = stringResource(R.string.settings_retention),
                    value = retentionLabel(s.retentionDays),
                    icon = Icons.Outlined.Schedule,
                    onClick = { dialog = SettingsDialog.RETENTION },
                    modifier = Modifier.testTag(SettingsTestTags.RETENTION),
                )
                ValueRow(
                    title = stringResource(R.string.settings_run_cleanup),
                    value = stringResource(R.string.settings_run_cleanup_summary),
                    icon = Icons.Outlined.EventBusy,
                    enabled = s.retentionDays > 0 && !state.busy,
                    onClick = viewModel::runCleanupNow,
                )
                InfoText(stringResource(R.string.settings_retention_favorites_note))

                // Privacy
                SectionHeader(stringResource(R.string.settings_section_privacy))
                SwitchRow(
                    title = stringResource(R.string.settings_filter_otp),
                    summary = stringResource(R.string.settings_filter_otp_summary),
                    checked = s.filterOtp,
                    onCheckedChange = viewModel::setFilterOtp,
                    icon = Icons.Outlined.Key,
                    modifier = Modifier.testTag(SettingsTestTags.OTP),
                )
                SwitchRow(
                    title = stringResource(R.string.settings_filter_sensitive),
                    summary = stringResource(R.string.settings_filter_sensitive_summary),
                    checked = s.filterSensitive,
                    onCheckedChange = viewModel::setFilterSensitive,
                    icon = Icons.Outlined.Shield,
                )
                SwitchRow(
                    title = stringResource(R.string.settings_secure_screen),
                    summary = stringResource(R.string.settings_secure_screen_summary),
                    checked = s.secureScreen,
                    onCheckedChange = viewModel::setSecureScreen,
                    icon = Icons.Outlined.VisibilityOff,
                    modifier = Modifier.testTag(SettingsTestTags.SECURE),
                )
                SwitchRow(
                    title = stringResource(R.string.settings_widget_content),
                    summary = stringResource(if (s.appLockEnabled) R.string.settings_widget_content_locked else R.string.settings_widget_content_summary),
                    checked = s.widgetShowContent && !s.appLockEnabled,
                    enabled = !s.appLockEnabled,
                    onCheckedChange = viewModel::setWidgetShowContent,
                    icon = Icons.Outlined.Widgets,
                    modifier = Modifier.testTag(SettingsTestTags.WIDGET),
                )

                // Security
                SectionHeader(stringResource(R.string.settings_section_security))
                AppLockSwitchRow(enabled = s.appLockEnabled, onEnabledChange = viewModel::setAppLockEnabled)
                ValueRow(
                    title = stringResource(R.string.settings_lock_timeout),
                    value = timeoutLabel(s.lockTimeoutSeconds),
                    icon = Icons.Outlined.Timer,
                    enabled = s.appLockEnabled,
                    onClick = { dialog = SettingsDialog.TIMEOUT },
                )

                // Appearance
                SectionHeader(stringResource(R.string.settings_section_appearance))
                ValueRow(
                    title = stringResource(R.string.settings_start_tab),
                    value = startTabLabel(s.startTab),
                    icon = Icons.Outlined.Tab,
                    onClick = { dialog = SettingsDialog.START_TAB },
                    modifier = Modifier.testTag(SettingsTestTags.START_TAB),
                )
                ValueRow(
                    title = stringResource(R.string.settings_theme),
                    value = themeLabel(s.themeMode),
                    icon = Icons.Outlined.DarkMode,
                    onClick = { dialog = SettingsDialog.THEME },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_amoled),
                    summary = stringResource(R.string.settings_amoled_summary),
                    checked = s.amoledBlack,
                    enabled = s.themeMode != ThemeMode.LIGHT,
                    onCheckedChange = viewModel::setAmoledBlack,
                )
                SwitchRow(
                    title = stringResource(R.string.settings_dynamic_color),
                    summary = stringResource(if (supportsDynamicColor) R.string.settings_dynamic_color_summary else R.string.settings_dynamic_color_unsupported),
                    checked = s.dynamicColor && supportsDynamicColor,
                    enabled = supportsDynamicColor,
                    onCheckedChange = viewModel::setDynamicColor,
                    icon = Icons.Outlined.Palette,
                )

                // Backup
                SectionHeader(stringResource(R.string.settings_section_backup))
                ValueRow(
                    title = stringResource(R.string.backup_export),
                    value = s.lastExportAt?.let { stringResource(R.string.backup_last_export, formatter.dateTime(it)) }
                        ?: stringResource(R.string.backup_never_exported),
                    icon = Icons.Outlined.Backup,
                    enabled = backupState.running == null,
                    onClick = backupViewModel::openExportDialog,
                    modifier = Modifier.testTag(SettingsTestTags.EXPORT),
                )
                ValueRow(
                    title = stringResource(R.string.backup_import),
                    value = stringResource(R.string.backup_import_summary),
                    icon = Icons.Outlined.Restore,
                    enabled = backupState.running == null,
                    onClick = backupViewModel::startImport,
                )
                ValueRow(
                    title = stringResource(R.string.backup_reminder),
                    value = reminderLabel(s.backupReminderDays),
                    onClick = { dialog = SettingsDialog.REMINDER },
                )
                InfoText(stringResource(R.string.backup_explainer))

                // Data
                SectionHeader(stringResource(R.string.settings_section_data))
                ValueRow(
                    title = stringResource(R.string.settings_delete_all),
                    value = stringResource(R.string.settings_delete_all_summary),
                    icon = Icons.Outlined.DeleteOutline,
                    enabled = !state.busy,
                    onClick = { dialog = SettingsDialog.DELETE_ALL },
                    modifier = Modifier.testTag(SettingsTestTags.DELETE_ALL),
                )

                UpdateSettingsSection()

                // Help
                SectionHeader(stringResource(R.string.settings_section_help))
                ValueRow(
                    title = stringResource(R.string.reliability_title),
                    value = stringResource(R.string.reliability_summary),
                    icon = Icons.Outlined.BatteryAlert,
                    onClick = onOpenReliability,
                )
                ValueRow(
                    title = stringResource(R.string.about_title),
                    value = stringResource(R.string.about_summary),
                    icon = Icons.AutoMirrored.Outlined.Article,
                    onClick = onOpenAbout,
                )

                if (showDeveloperOptions) {
                    SectionHeader(stringResource(R.string.settings_section_developer))
                    ValueRow(
                        title = stringResource(R.string.settings_demo_data),
                        value = stringResource(R.string.settings_demo_data_summary),
                        icon = Icons.Outlined.BugReport,
                        enabled = !state.busy,
                        onClick = viewModel::insertDemoData,
                    )
                }
                Spacer(Modifier.padding(bottom = 24.dp))
            }
        }

        SettingsDialogs(dialog, s, viewModel, onDismiss = { dialog = SettingsDialog.NONE })
    }

    if (backupState.exportDialogOpen) {
        ExportPasswordDialog(
            onConfirm = backupViewModel::confirmExportPassword,
            onDismiss = backupViewModel::dismissExportDialog,
        )
    }
    if (backupState.importSource != null) {
        ImportDialog(onConfirm = backupViewModel::confirmImport, onDismiss = backupViewModel::dismissImportDialog)
    }
    backupState.running?.let { operation ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(stringResource(if (operation == BackupOperation.EXPORT) R.string.backup_exporting else R.string.backup_importing)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.backup_keep_open))
                }
            },
        )
    }
}

@Composable
private fun SettingsDialogs(dialog: SettingsDialog, s: VaultSettings, viewModel: SettingsViewModel, onDismiss: () -> Unit) {
    when (dialog) {
        SettingsDialog.NONE -> Unit
        SettingsDialog.RETENTION -> ChoiceDialog(
            title = stringResource(R.string.settings_retention),
            options = SettingsViewModel.RETENTION_OPTIONS,
            selected = s.retentionDays,
            label = { retentionLabel(it) },
            onSelect = {
                viewModel.setRetentionDays(it)
                onDismiss()
            },
            onDismiss = onDismiss,
            footer = { Text(stringResource(R.string.settings_retention_favorites_note), style = MaterialTheme.typography.bodySmall) },
        )
        SettingsDialog.TIMEOUT -> ChoiceDialog(
            title = stringResource(R.string.settings_lock_timeout),
            options = AppLockStateMachine.TIMEOUT_OPTIONS_SECONDS,
            selected = s.lockTimeoutSeconds,
            label = { timeoutLabel(it) },
            onSelect = {
                viewModel.setLockTimeout(it)
                onDismiss()
            },
            onDismiss = onDismiss,
        )
        SettingsDialog.THEME -> ChoiceDialog(
            title = stringResource(R.string.settings_theme),
            options = ThemeMode.entries,
            selected = s.themeMode,
            label = { themeLabel(it) },
            onSelect = {
                viewModel.setThemeMode(it)
                onDismiss()
            },
            onDismiss = onDismiss,
        )
        SettingsDialog.APPS_TO_SAVE -> ChoiceDialog(
            title = stringResource(R.string.settings_apps_to_save),
            options = AppsToSave.entries,
            selected = s.appsToSave,
            label = { stringResource(if (it == AppsToSave.SELECTED_APPS) R.string.apps_to_save_selected else R.string.apps_to_save_all) },
            onSelect = {
                viewModel.setAppsToSave(it)
                onDismiss()
            },
            onDismiss = onDismiss,
            footer = { Text(stringResource(R.string.apps_to_save_footer), style = MaterialTheme.typography.bodySmall) },
        )
        SettingsDialog.START_TAB -> ChoiceDialog(
            title = stringResource(R.string.settings_start_tab),
            options = StartTab.entries,
            selected = s.startTab,
            label = { startTabLabel(it) },
            onSelect = {
                viewModel.setStartTab(it)
                onDismiss()
            },
            onDismiss = onDismiss,
        )
        SettingsDialog.REMINDER -> ChoiceDialog(
            title = stringResource(R.string.backup_reminder),
            options = SettingsViewModel.REMINDER_OPTIONS,
            selected = s.backupReminderDays,
            label = { reminderLabel(it) },
            onSelect = {
                viewModel.setBackupReminderDays(it)
                onDismiss()
            },
            onDismiss = onDismiss,
        )
        SettingsDialog.DELETE_ALL -> ConfirmDialog(
            title = stringResource(R.string.settings_delete_all_confirm_title),
            body = stringResource(R.string.settings_delete_all_confirm_body),
            confirmLabel = stringResource(R.string.action_delete_everything),
            destructive = true,
            onConfirm = {
                viewModel.deleteAll()
                onDismiss()
            },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun retentionLabel(days: Int): String =
    if (days == 0) stringResource(R.string.retention_off) else pluralStringResource(R.plurals.retention_days, days, days)

@Composable
private fun reminderLabel(days: Int): String =
    if (days == 0) stringResource(R.string.reminder_off) else pluralStringResource(R.plurals.reminder_days, days, days)

@Composable
private fun timeoutLabel(seconds: Int): String = when {
    seconds == 0 -> stringResource(R.string.timeout_immediately)
    seconds < 60 -> pluralStringResource(R.plurals.timeout_seconds, seconds, seconds)
    else -> pluralStringResource(R.plurals.timeout_minutes, seconds / 60, seconds / 60)
}

@Composable
private fun startTabLabel(tab: StartTab): String = stringResource(
    when (tab) {
        StartTab.RECENT -> R.string.home_tab_recent
        StartTab.CHATS -> R.string.home_tab_conversations
        StartTab.APPS -> R.string.home_tab_apps
    },
)

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    },
)

object BackupTestTags {
    const val PASSWORD = "backup_password"
    const val CONFIRM = "backup_confirm"
    const val SUBMIT = "backup_submit"
    const val ERROR = "backup_error"
    const val STRENGTH = "backup_strength"
}

@Composable
private fun ExportPasswordDialog(onConfirm: (String, String) -> PasswordIssue?, onDismiss: () -> Unit) {
    // Not saveable on purpose: passwords must not be written into the saved-instance-state bundle.
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var issue by remember { mutableStateOf<PasswordIssue?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_export_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.backup_export_dialog_body), style = MaterialTheme.typography.bodyMedium)
                PasswordField(password, { password = it; issue = null }, stringResource(R.string.backup_password), Modifier.testTag(BackupTestTags.PASSWORD))
                PasswordField(confirm, { confirm = it; issue = null }, stringResource(R.string.backup_password_confirm), Modifier.testTag(BackupTestTags.CONFIRM))
                if (password.isNotEmpty()) {
                    val strength = PasswordRules.strength(password)
                    Text(
                        stringResource(
                            when (strength) {
                                PasswordStrength.WEAK -> R.string.password_strength_weak
                                PasswordStrength.FAIR -> R.string.password_strength_fair
                                PasswordStrength.STRONG -> R.string.password_strength_strong
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(BackupTestTags.STRENGTH),
                    )
                }
                issue?.let {
                    Text(
                        if (it == PasswordIssue.TOO_SHORT) {
                            pluralStringResource(R.plurals.password_too_short, PasswordRules.MIN_LENGTH, PasswordRules.MIN_LENGTH)
                        } else {
                            stringResource(R.string.password_mismatch)
                        },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(BackupTestTags.ERROR).semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    issue = onConfirm(password, confirm)
                    if (issue == null) {
                        password = ""
                        confirm = ""
                    }
                },
                modifier = Modifier.testTag(BackupTestTags.SUBMIT),
            ) { Text(stringResource(R.string.backup_choose_location)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ImportDialog(onConfirm: (String, ImportMode) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(ImportMode.MERGE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_import_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PasswordField(password, { password = it }, stringResource(R.string.backup_password), Modifier.testTag(BackupTestTags.PASSWORD))
                Column(Modifier.selectableGroup()) {
                    ImportMode.entries.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = mode == option, role = Role.RadioButton, onClick = { mode = option }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = mode == option, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(if (option == ImportMode.MERGE) R.string.import_merge else R.string.import_replace))
                                Text(
                                    stringResource(if (option == ImportMode.MERGE) R.string.import_merge_summary else R.string.import_replace_summary),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (option == ImportMode.REPLACE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(password, mode)
                password = ""
            }, enabled = password.isNotEmpty(), modifier = Modifier.testTag(BackupTestTags.SUBMIT)) {
                Text(stringResource(R.string.backup_import_action))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun PasswordField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = modifier.fillMaxWidth(),
    )
}
