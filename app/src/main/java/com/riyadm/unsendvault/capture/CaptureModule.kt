package com.riyadm.unsendvault.capture

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.capture.adapter.AdapterRegistry
import com.riyadm.unsendvault.capture.adapter.AppLabelResolver
import com.riyadm.unsendvault.capture.engine.CaptureSink
import com.riyadm.unsendvault.capture.engine.DefaultCaptureEngine
import com.riyadm.unsendvault.capture.filter.SensitiveContentFilter
import com.riyadm.unsendvault.capture.parser.CapturePlaceholders
import com.riyadm.unsendvault.di.AppContainer
import kotlinx.coroutines.Dispatchers

/** Capture engine used by [VaultNotificationListener]. Every call must return immediately (they only enqueue work). */
interface CaptureEngine {
    fun onPosted(snapshot: NotificationSnapshot)
    fun onRemoved(event: RemovalEvent)

    /** Low-priority post from the catch-up pass over already-active notifications. */
    fun onCatchUp(snapshot: NotificationSnapshot) = onPosted(snapshot)

    /**
     * True when [notificationKey] was ingested (or is queued) in this process. Lets the listener forward the removal of
     * a notification whose final content no longer passes the pre-filter. Thread-safe.
     */
    fun isTracked(notificationKey: String): Boolean = false
}

/** Everything the listener needs, built once per process on the first callback. */
class CaptureRuntime(
    val engine: CaptureEngine,
    val preFilter: CapturePreFilter,
    val settings: CaptureSettingsCache,
    /** Also used off the hot path to find which chat an active notification belongs to (reply). */
    val registry: AdapterRegistry,
)

object CaptureModule {
    fun createRuntime(container: AppContainer): CaptureRuntime {
        val context = container.context
        val placeholders = placeholders(context)
        val registry = AdapterRegistry.createDefault(placeholders)
        val cache = container.captureSettings
        container.settingsRepository // attaches the settings cache (see AppContainer)
        val engine = DefaultCaptureEngine(
            scope = container.appScope,
            dispatcher = Dispatchers.Default.limitedParallelism(1),
            // In-memory snapshot; until DataStore's first read completes this waits for the persisted value (never the
            // possibly-default SettingsRepository.current).
            settingsProvider = { cache.get() },
            sink = object : CaptureSink {
                override suspend fun ingest(parsed: ParsedNotification, capturedAt: Long) {
                    val result = container.vaultRepository.ingest(parsed, capturedAt)
                    if (result.newMessages.isEmpty() && result.possiblyDeletedIn.isEmpty()) return
                    val settings = cache.get()
                    if (settings.savedMessageNotifications) {
                        container.savedMessageNotifier.onIngested(settings, parsed, result, capturedAt)
                    }
                }

                override suspend fun recordRemoval(event: RemovalEvent) {
                    container.vaultRepository.recordRemoval(event)
                }
            },
            registry = registry,
            labels = AppLabelResolver { pkg -> packageLabel(context, pkg) },
            sensitiveFilter = SensitiveContentFilter(placeholders),
            ownPackage = context.packageName,
        )
        return CaptureRuntime(engine, CapturePreFilter(registry, context.packageName), cache, registry)
    }

    fun createPauseController(context: Context): CapturePauseController =
        CapturePauseController(AndroidListenerControl(context.applicationContext))

    fun createListenerStatus(context: Context): ListenerStatus = AndroidListenerStatus(context)

    /** Placeholder texts written into storage, localised at capture time. */
    fun placeholders(context: Context): CapturePlaceholders {
        val defaults = CapturePlaceholders()
        fun str(id: Int, fallback: String) = try {
            context.getString(id)
        } catch (e: Exception) {
            fallback
        }
        return CapturePlaceholders(
            selfSender = str(R.string.capture_self_sender, defaults.selfSender),
            systemRedacted = str(R.string.capture_placeholder_system_redacted, defaults.systemRedacted),
            otpHidden = str(R.string.capture_placeholder_otp, defaults.otpHidden),
            sensitiveHidden = str(R.string.capture_placeholder_sensitive, defaults.sensitiveHidden),
            mediaPhoto = str(R.string.capture_media_photo, defaults.mediaPhoto),
            mediaVideo = str(R.string.capture_media_video, defaults.mediaVideo),
            mediaAudio = str(R.string.capture_media_audio, defaults.mediaAudio),
            mediaAttachment = str(R.string.capture_media_attachment, defaults.mediaAttachment),
        )
    }

    /** Label of a single package (never enumerates installed packages). Null when hidden by package visibility. */
    private fun packageLabel(context: Context, packageName: String): String? {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0)
        }
        return pm.getApplicationLabel(info).toString()
    }
}
