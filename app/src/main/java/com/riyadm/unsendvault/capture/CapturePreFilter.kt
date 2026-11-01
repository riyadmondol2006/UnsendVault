package com.riyadm.unsendvault.capture

import android.app.Notification
import com.riyadm.unsendvault.capture.adapter.AdapterRegistry
import com.riyadm.unsendvault.capture.parser.TextRules
import com.riyadm.unsendvault.core.model.VaultSettings

/**
 * Cheap reject step that runs on the listener's main-thread callback *before* a [NotificationSnapshot] is built.
 *
 * The listener receives every notification on the phone, almost all of which UnsendVault drops. This decides from a few
 * header fields (package, flags, category) and two Bundle lookups (template string, presence of EXTRA_MESSAGES) so the
 * common case costs no snapshot, no queue item and no background wake-up.
 *
 * Contract: it only rejects what [com.riyadm.unsendvault.capture.engine.DefaultCaptureEngine.process] would also
 * reject, so capture results are identical with or without it:
 * - own package, ongoing / foreground-service: rejected by the engine;
 * - call / non-message categories and group summaries without any MessagingStyle extras: rejected by every parser
 *   (AdapterRegistry falls back to the generic parser, which rejects them too);
 * - paused, excluded or not-selected package, messages-only gate: rejected by the engine's settings gates — but only applied when the
 *   in-memory settings snapshot is loaded. With `settings == null` (cold start, DataStore not read yet) those checks
 *   are skipped and the background consumer, which waits for the real settings, decides.
 */
class CapturePreFilter(private val registry: AdapterRegistry, private val ownPackage: String) {

    enum class Result(val accept: Boolean, val counter: CaptureStats.Counter?) {
        ACCEPT(true, null),
        OWN_PACKAGE(false, CaptureStats.Counter.PREFILTER_OWN_PACKAGE),
        ONGOING(false, CaptureStats.Counter.PREFILTER_ONGOING),
        PAUSED(false, CaptureStats.Counter.PREFILTER_PAUSED),
        EXCLUDED(false, CaptureStats.Counter.PREFILTER_EXCLUDED),

        /** "Only selected apps" is on and this app isn't selected. */
        NOT_SELECTED(false, CaptureStats.Counter.PREFILTER_NOT_SELECTED),
        NON_MESSAGE_CATEGORY(false, CaptureStats.Counter.PREFILTER_NON_MESSAGE_CATEGORY),
        GROUP_SUMMARY_WITHOUT_MESSAGES(false, CaptureStats.Counter.PREFILTER_GROUP_SUMMARY),
        NOT_A_MESSAGE(false, CaptureStats.Counter.PREFILTER_NOT_A_MESSAGE),
        ;

        /** Rejected because of the user's settings (as opposed to what the notification contains). */
        val isSettingsReject: Boolean get() = this == PAUSED || this == EXCLUDED || this == NOT_SELECTED || this == OWN_PACKAGE
    }

    /** Step 1: header fields only (no Bundle access). */
    fun checkHeader(packageName: String, flags: Int, category: String?, settings: VaultSettings?): Result {
        if (packageName == ownPackage) return Result.OWN_PACKAGE
        if (flags and ONGOING_FLAGS != 0) return Result.ONGOING
        if (settings != null) {
            if (!settings.captureEnabled) return Result.PAUSED
            if (packageName in settings.excludedPackages) return Result.EXCLUDED
            if (!settings.savesApp(packageName)) return Result.NOT_SELECTED
        }
        if (TextRules.isCallCategory(category) || TextRules.isNonMessageCategory(category)) return Result.NON_MESSAGE_CATEGORY
        return Result.ACCEPT
    }

    /** Step 2: needs the template string and whether the MessagingStyle message arrays are present. */
    fun checkContent(
        packageName: String,
        flags: Int,
        category: String?,
        channelId: String?,
        template: String?,
        hasMessages: Boolean,
        hasHistoricMessages: Boolean,
        settings: VaultSettings?,
    ): Result {
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0 && !hasMessages && !hasHistoricMessages) {
            return Result.GROUP_SUMMARY_WITHOUT_MESSAGES
        }
        if (settings != null && settings.messagesOnly) {
            val adapter = registry.adapterFor(packageName)
            val message = try {
                adapter.mayBeMessage(category, channelId, template, hasMessages)
            } catch (e: Exception) {
                true // let the background consumer decide with its own fallback
            }
            if (!message) return Result.NOT_A_MESSAGE
        }
        return Result.ACCEPT
    }

    /** Both steps; convenience for tests and callers that already have every field. */
    fun decide(
        packageName: String,
        flags: Int,
        category: String?,
        channelId: String?,
        template: String?,
        hasMessages: Boolean,
        hasHistoricMessages: Boolean,
        settings: VaultSettings?,
    ): Result {
        val header = checkHeader(packageName, flags, category, settings)
        if (!header.accept) return header
        return checkContent(packageName, flags, category, channelId, template, hasMessages, hasHistoricMessages, settings)
    }

    companion object {
        const val ONGOING_FLAGS = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE
    }
}
