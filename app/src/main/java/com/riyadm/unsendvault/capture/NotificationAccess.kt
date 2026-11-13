package com.riyadm.unsendvault.capture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide "is the listener bound right now" flag, written only by [VaultNotificationListener]. */
object ListenerConnectionState {
    private val state = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = state.asStateFlow()

    internal fun set(connected: Boolean) {
        state.value = connected
    }
}

/** Notification Access helpers shared by [AndroidListenerStatus] and the listener service. */
object NotificationAccess {
    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"

    fun component(context: Context): ComponentName = ComponentName(context, VaultNotificationListener::class.java)

    fun isGranted(context: Context): Boolean = try {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    } catch (e: Exception) {
        false
    }

    /** Asks the system to rebind the listener after an OEM/process kill. No-op (and never throws) without access. */
    fun requestRebind(context: Context) {
        if (!isGranted(context)) return
        try {
            NotificationListenerService.requestRebind(component(context))
        } catch (e: Exception) {
            CaptureLog.d("requestRebind failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * User-initiated reconnect. `requestRebind()` is silently ignored when the system still thinks the listener is
     * bound or its binding is stale (typical after an app update), so if it has not connected shortly after, toggle
     * the component's enabled state, which forces the system to bind it again.
     */
    fun reconnect(context: Context) {
        val app = context.applicationContext
        requestRebind(app)
        Handler(Looper.getMainLooper()).postDelayed({
            if (!ListenerConnectionState.connected.value) forceRebind(app)
        }, FORCE_REBIND_DELAY_MS)
    }

    fun forceRebind(context: Context) {
        if (!isGranted(context)) return
        try {
            val pm = context.packageManager
            val cn = component(context)
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            requestRebind(context)
        } catch (e: Exception) {
            CaptureLog.d("forceRebind failed: ${e.javaClass.simpleName}")
        }
    }

    private const val FORCE_REBIND_DELAY_MS = 2_000L

    /**
     * Candidate settings screens, best first:
     * 1. API 30+: the per-app Notification Access detail screen for our component.
     * 2. The Notification Access list, with the component extra and the Settings "highlight" fragment-args trick.
     * 3. Last resort: this app's App Info screen (OEM ROMs that hide the listener settings activity).
     * Callers should start the first one that resolves (`intent.resolveActivity(pm) != null`).
     */
    fun settingsIntents(context: Context): List<Intent> {
        val flattened = component(context).flattenToString()
        val list = ArrayList<Intent>(3)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            list += Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, flattened)
        }
        list += Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME", flattened)
            .putExtra(EXTRA_FRAGMENT_ARG_KEY, flattened)
            .putExtra(EXTRA_SHOW_FRAGMENT_ARGS, Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, flattened) })
        list += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        return list
    }

    /** First candidate that resolves on this device; falls back to the generic list screen. */
    fun bestSettingsIntent(context: Context): Intent {
        val candidates = settingsIntents(context)
        val pm = context.packageManager
        return candidates.firstOrNull { intent ->
            try {
                intent.resolveActivity(pm) != null
            } catch (e: Exception) {
                false
            }
        } ?: candidates.first { it.action == Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS }
    }
}

/** [ListenerControl] over the system APIs and the currently bound [VaultNotificationListener]. */
class AndroidListenerControl(private val app: Context) : ListenerControl {
    override fun isAccessGranted(): Boolean = NotificationAccess.isGranted(app)

    override fun isConnected(): Boolean = ListenerConnectionState.connected.value

    override fun requestUnbind(): Boolean = VaultNotificationListener.requestUnbindBound()

    override fun requestRebind() = NotificationAccess.requestRebind(app)
}

/** [ListenerStatus] backed by the system's enabled-listener list and [ListenerConnectionState]. */
class AndroidListenerStatus(context: Context) : ListenerStatus {
    private val app = context.applicationContext

    override fun isAccessGranted(): Boolean = NotificationAccess.isGranted(app)

    override val connected: StateFlow<Boolean> get() = ListenerConnectionState.connected

    override fun requestRebind() = NotificationAccess.reconnect(app)

    override fun accessSettingsIntent(): Intent = NotificationAccess.bestSettingsIntent(app)

    /** Ordered candidates for UIs that want to try several screens. See [NotificationAccess.settingsIntents]. */
    fun accessSettingsIntents(): List<Intent> = NotificationAccess.settingsIntents(app)
}
