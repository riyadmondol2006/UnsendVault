package com.riyadm.unsendvault.capture

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.riyadm.unsendvault.appContainer
import com.riyadm.unsendvault.core.model.AppsToSave
import com.riyadm.unsendvault.core.model.VaultSettings
import java.io.FileDescriptor
import java.io.PrintWriter

/**
 * Receives notifications after the user explicitly grants Notification Access.
 *
 * The system delivers *every* notification on the phone here, on the main thread, for as long as access is granted,
 * so the callbacks are built to drop the common case almost for free:
 * 1. [CapturePreFilter] on header fields and two Bundle lookups, using the in-memory settings snapshot (own package,
 *    ongoing, paused, excluded, call/system categories, empty group summaries, the messages-only gate). No snapshot,
 *    no queue item, no background wake-up for rejected notifications.
 * 2. Accepted notifications are copied into a [NotificationSnapshot] (strings/primitives only) and handed to the
 *    [CaptureEngine]'s coalescing queue; parsing, filtering and storage happen on its single background consumer.
 * 3. Removals are forwarded only for notifications that could have been archived (pre-filter) or were archived in this
 *    process, so dismissing unrelated notifications never opens the database.
 *
 * Pause unbinds the listener entirely ([CapturePauseController]); reconnect catch-up is throttled and incremental
 * ([CatchUpPolicy]); automatic rebinds back off ([RebindPolicy]). No wake locks, alarms, polling or foreground
 * service. Every callback is guarded so a malformed notification can never crash the service.
 */
class VaultNotificationListener : NotificationListenerService() {

    private var runtimeCache: CaptureRuntime? = null
    private var runtimeUnavailable = false
    private var ownPackage: String = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private val deferredCatchUp = Runnable { catchUpActiveNotifications() }

    /** Built on first use; null (and not retried) when the app container is unavailable (e.g. a test Application). */
    private val runtime: CaptureRuntime?
        get() {
            runtimeCache?.let { return it }
            if (runtimeUnavailable) return null
            return try {
                applicationContext.appContainer.capture.also { runtimeCache = it }
            } catch (e: Exception) {
                runtimeUnavailable = true
                null
            }
        }

    private val pauseController: CapturePauseController?
        get() = try {
            applicationContext.appContainer.pauseController
        } catch (e: Exception) {
            null
        }

    override fun onCreate() {
        super.onCreate()
        ownPackage = packageName
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        ListenerConnectionState.set(true)
        bound = this
        rebindPolicy.onConnected(SystemClock.elapsedRealtime())
        cancelPendingRebind()
        CaptureLog.d("listener connected")
        val rt = runtime // builds the runtime now so the settings snapshot loads before the first notification
        pauseController?.onListenerConnected()
        if (rt?.settings?.current?.captureEnabled == false) return // paused: nothing to catch up
        catchUpActiveNotifications()
    }

    override fun onListenerDisconnected() {
        ListenerConnectionState.set(false)
        if (bound === this) bound = null
        mainHandler.removeCallbacks(deferredCatchUp)
        catchUpPolicy.onDisconnected(System.currentTimeMillis())
        CaptureLog.d("listener disconnected")
        super.onListenerDisconnected()
        // OEM task killers (Xiaomi, Samsung, Oppo, Vivo, OnePlus, Huawei…) commonly unbind listeners; ask to come back,
        // unless we unbound ourselves for a pause, and never in a tight loop.
        if (pauseController?.onListenerDisconnected() == false) {
            CaptureStats.inc(CaptureStats.Counter.REBIND_SKIPPED)
            return
        }
        scheduleRebind(applicationContext, rebindPolicy.onDisconnected(SystemClock.elapsedRealtime()))
    }

    override fun onDestroy() {
        ListenerConnectionState.set(false)
        if (bound === this) bound = null
        mainHandler.removeCallbacks(deferredCatchUp)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        CaptureStats.inc(CaptureStats.Counter.POSTED_CALLBACKS)
        val rt = runtime ?: return
        CaptureTrace.begin("UV:onPosted")
        try {
            admit(rt, sbn)?.let(rt.engine::onPosted)
        } catch (e: Exception) {
            CaptureLog.d("onNotificationPosted failed: ${e.javaClass.simpleName}")
        } finally {
            CaptureTrace.end()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        if (sbn == null) return
        CaptureStats.inc(CaptureStats.Counter.REMOVED_CALLBACKS)
        val rt = runtime ?: return
        try {
            val pkg = sbn.packageName ?: return
            val key = sbn.key ?: return
            val verdict = SnapshotFactory.preFilter(sbn, rt.preFilter, rt.settings.current)
            if (!verdict.accept && (verdict.isSettingsReject || !rt.engine.isTracked(key))) {
                CaptureStats.inc(CaptureStats.Counter.REMOVALS_DROPPED)
                return
            }
            CaptureStats.inc(CaptureStats.Counter.REMOVALS_FORWARDED)
            rt.engine.onRemoved(
                RemovalEvent(
                    packageName = pkg,
                    notificationKey = key,
                    reason = RemovalReasons.fromListenerReason(reason),
                    removedAt = System.currentTimeMillis(),
                ),
            )
        } catch (e: Exception) {
            CaptureLog.d("onNotificationRemoved failed: ${e.javaClass.simpleName}")
        }
    }

    /** Pre-filter, then snapshot. Null when the notification is dropped. */
    private fun admit(rt: CaptureRuntime, sbn: StatusBarNotification): NotificationSnapshot? {
        val settings = rt.settings.current
        val verdict = SnapshotFactory.preFilter(sbn, rt.preFilter, settings)
        if (!verdict.accept) {
            verdict.counter?.let { CaptureStats.inc(it) }
            if (verdict == CapturePreFilter.Result.NOT_SELECTED && settings != null) noteNewApp(rt, sbn, settings)
            return null
        }
        if (settings == null) CaptureStats.inc(CaptureStats.Counter.PREFILTER_UNKNOWN_SETTINGS)
        val snapshot = SnapshotFactory.fromStatusBarNotification(sbn, ownPackage.ifEmpty { packageName }) ?: return null
        CaptureStats.inc(CaptureStats.Counter.SNAPSHOTS_BUILT)
        return snapshot
    }

    /**
     * "Only selected apps": an unselected app whose notification would otherwise be saved (same header/template checks,
     * with the default messages-only gate; no text is read) is offered as "New". Already-listed apps cost one set lookup.
     */
    private fun noteNewApp(rt: CaptureRuntime, sbn: StatusBarNotification, settings: VaultSettings) {
        val pkg = sbn.packageName ?: return
        if (pkg in settings.newApps) return
        val asIfSelected = settings.copy(appsToSave = AppsToSave.ALL_CHAT_APPS, messagesOnly = true)
        if (SnapshotFactory.preFilter(sbn, rt.preFilter, asIfSelected).accept) rt.onNewApp(pkg)
    }

    /**
     * Pass over what is already in the shade (after a reboot or rebind), throttled and incremental per [CatchUpPolicy],
     * through the same pre-filter and the engine's low-priority catch-up lane. Deduplicated downstream.
     */
    private fun catchUpActiveNotifications() {
        mainHandler.removeCallbacks(deferredCatchUp)
        if (bound !== this) return
        val rt = runtime ?: return
        when (val decision = catchUpPolicy.decide(SystemClock.elapsedRealtime())) {
            is CatchUpPolicy.Decision.Defer -> {
                CaptureStats.inc(CaptureStats.Counter.CATCH_UP_DEFERRED)
                mainHandler.postDelayed(deferredCatchUp, decision.delayMillis)
            }
            is CatchUpPolicy.Decision.Run -> {
                val active = try {
                    activeNotifications
                } catch (e: SecurityException) {
                    null
                } catch (e: RuntimeException) {
                    null
                } ?: return
                catchUpPolicy.onRan(SystemClock.elapsedRealtime())
                CaptureStats.inc(CaptureStats.Counter.CATCH_UP_RUNS)
                var count = 0
                CaptureTrace.section("UV:catchUp") {
                    for (sbn in active) {
                        try {
                            if (sbn == null || sbn.postTime < decision.minPostTime) continue
                            admit(rt, sbn)?.let {
                                rt.engine.onCatchUp(it)
                                count++
                            }
                        } catch (e: Exception) {
                            // Skip the malformed one, keep going.
                        }
                    }
                }
                CaptureStats.inc(CaptureStats.Counter.CATCH_UP_ITEMS, count.toLong())
                CaptureLog.d("catch-up queued $count of ${active.size} notifications")
            }
        }
    }

    override fun dump(fd: FileDescriptor?, writer: PrintWriter?, args: Array<out String>?) {
        if (writer == null) return
        writer.println("connected=${ListenerConnectionState.connected.value} bound=${bound === this}")
        CaptureStats.dump(writer)
    }

    companion object {
        /** The listener instance the system currently has bound in this process, if any. Main thread writes only. */
        @Volatile
        private var bound: VaultNotificationListener? = null

        internal val catchUpPolicy = CatchUpPolicy()
        internal val rebindPolicy = RebindPolicy()

        private val processHandler by lazy { Handler(Looper.getMainLooper()) }
        private val rebindToken = Any()

        /** Asks the bound instance (if any) to unbind; used by [CapturePauseController] via [AndroidListenerControl]. */
        fun requestUnbindBound(): Boolean {
            val listener = bound ?: return false
            return try {
                listener.requestUnbind()
                true
            } catch (e: Exception) {
                CaptureLog.d("requestUnbind failed: ${e.javaClass.simpleName}")
                false
            }
        }

        /**
         * Active notifications of [packageName] from the bound listener, or null when no listener is bound (access
         * revoked, capture paused, not yet connected). Called only on demand (reply), never on the capture path; safe
         * off the main thread (binder call).
         */
        fun activeNotificationsOf(packageName: String): List<StatusBarNotification>? {
            val listener = bound ?: return null
            return try {
                listener.activeNotifications?.filter { it != null && it.packageName == packageName }.orEmpty()
            } catch (e: SecurityException) {
                null
            } catch (e: RuntimeException) {
                null
            }
        }

        /** One pending rebind at most per process; a newer request replaces it. */
        private fun scheduleRebind(context: Context, delayMillis: Long) {
            val app = context.applicationContext
            cancelPendingRebind()
            val action = Runnable {
                if (ListenerConnectionState.connected.value) return@Runnable
                CaptureStats.inc(CaptureStats.Counter.REBIND_REQUESTS)
                NotificationAccess.requestRebind(app)
            }
            if (delayMillis <= 0) {
                action.run()
            } else {
                processHandler.postAtTime(action, rebindToken, SystemClock.uptimeMillis() + delayMillis)
            }
        }

        private fun cancelPendingRebind() = processHandler.removeCallbacksAndMessages(rebindToken)
    }
}
