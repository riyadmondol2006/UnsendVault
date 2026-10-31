package com.riyadm.unsendvault.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Subject
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.core.model.VaultSettings
import com.riyadm.unsendvault.ui.common.appDetailsIntent
import com.riyadm.unsendvault.ui.common.startFirstAvailable
import com.riyadm.unsendvault.ui.components.SectionHeader
import com.riyadm.unsendvault.ui.components.SwitchRow
import com.riyadm.unsendvault.ui.components.ValueRow

object NotificationSettingsTestTags {
    const val SAVED = "settings_saved_notifications"
    const val CONTENT = "settings_notification_content"
    const val BLOCKED = "settings_notifications_blocked"
}

/**
 * Settings > Notifications: UnsendVault's own opt-in "messages saved" notifications. On Android 13+ turning it on asks
 * for POST_NOTIFICATIONS first; the setting is stored only if the permission is granted. If notifications are later
 * blocked in system settings, a row says so and opens those settings.
 */
@Composable
fun NotificationSettingsSection(s: VaultSettings, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val openSystemSettings = { context.startFirstAvailable(appNotificationSettings(context), context.appDetailsIntent()) }
    var allowed by remember { mutableStateOf(notificationsAllowed(context)) }
    LifecycleResumeEffect(Unit) {
        allowed = notificationsAllowed(context)
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = notificationsAllowed(context)
        when {
            granted && allowed -> viewModel.setSavedMessageNotifications(true)
            granted -> openSystemSettings() // permission granted but notifications blocked for the app
            // Denied with "don't ask again" (no rationale left): the system dialog won't show any more, only settings can.
            activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> openSystemSettings()
            else -> Unit // denied once: leave it off
        }
    }

    SectionHeader(stringResource(R.string.settings_section_notifications))
    SwitchRow(
        title = stringResource(R.string.settings_saved_notifications),
        summary = stringResource(R.string.settings_saved_notifications_summary),
        checked = s.savedMessageNotifications && allowed,
        onCheckedChange = { on ->
            when {
                !on -> viewModel.setSavedMessageNotifications(false)
                needsRuntimePermission(context) -> launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                !allowed -> openSystemSettings()
                else -> viewModel.setSavedMessageNotifications(true)
            }
        },
        icon = Icons.Outlined.NotificationsNone,
        modifier = Modifier.testTag(NotificationSettingsTestTags.SAVED),
    )
    if (s.savedMessageNotifications && !allowed) {
        ValueRow(
            title = stringResource(R.string.settings_saved_notifications_blocked),
            onClick = { openSystemSettings() },
            modifier = Modifier.testTag(NotificationSettingsTestTags.BLOCKED),
        )
    }
    SwitchRow(
        title = stringResource(R.string.settings_notification_content),
        summary = stringResource(
            if (s.appLockEnabled) R.string.settings_notification_content_locked else R.string.settings_notification_content_summary,
        ),
        checked = s.notificationShowContent && !s.appLockEnabled,
        enabled = s.savedMessageNotifications && !s.appLockEnabled,
        onCheckedChange = viewModel::setNotificationShowContent,
        icon = Icons.Outlined.Subject,
        modifier = Modifier.testTag(NotificationSettingsTestTags.CONTENT),
    )
}

private fun needsRuntimePermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

private fun notificationsAllowed(context: Context): Boolean =
    !needsRuntimePermission(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun appNotificationSettings(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
