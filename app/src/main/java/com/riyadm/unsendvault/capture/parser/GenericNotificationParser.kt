package com.riyadm.unsendvault.capture.parser

import com.riyadm.unsendvault.capture.CapturedMessage
import com.riyadm.unsendvault.capture.DeletionMarker
import com.riyadm.unsendvault.capture.NotificationSnapshot
import com.riyadm.unsendvault.capture.SnapshotMessage
import com.riyadm.unsendvault.capture.adapter.KnownApps
import com.riyadm.unsendvault.capture.filter.SystemRedaction
import com.riyadm.unsendvault.core.model.ContentKind

/**
 * App-agnostic notification parser. Per-app adapters subclass it and override the small hooks ([parseTitle],
 * [cleanBody], [deletionMarker], [isFlatNoise]) rather than re-implementing parsing.
 *
 * Order of evidence:
 * 1. MessagingStyle (EXTRA_MESSAGES, plus EXTRA_HISTORIC_MESSAGES not already present) — precise per-message
 *    timestamps and senders. Messages with a null sender (or the self display name) are the user's own and get the
 *    consistent [CapturePlaceholders.selfSender] label.
 * 2. InboxStyle lines, 3. BigText, 4. title/text — timestamps fall back to Notification.when / post time and are
 *    flagged imprecise.
 *
 * Messages are de-duplicated inside one notification by sender + timestamp + text. Group summaries without
 * MessagingStyle content, ongoing notifications, calls and app chatter are rejected.
 */
open class GenericNotificationParser(protected val placeholders: CapturePlaceholders) {

    open val adapterId: String get() = GENERIC_ID

    /** Whether flat (non-MessagingStyle) "Name: body" lines should always be split into sender and body. */
    protected open val alwaysSplitSenderPrefix: Boolean get() = false

    fun parseNotification(s: NotificationSnapshot): ParseOutcome {
        if (s.isOngoing) return ParseOutcome.Rejected(RejectReason.ONGOING)
        if (TextRules.isCallCategory(s.category)) return ParseOutcome.Rejected(RejectReason.CALL)
        if (TextRules.isNonMessageCategory(s.category)) return ParseOutcome.Rejected(RejectReason.NOT_A_MESSAGE)
        val merged = mergeMessages(s)
        if (s.isGroupSummary && merged.isEmpty()) return ParseOutcome.Rejected(RejectReason.GROUP_SUMMARY)
        val redacted = SystemRedaction.looksRedacted(s)
        return if (merged.isNotEmpty()) parseMessaging(s, merged, redacted) else parseFlat(s, redacted)
    }

    // --- Hooks --------------------------------------------------------------------------------------------------------

    /** Derives conversation title / sender / group flag from the notification title fields. */
    protected open fun parseTitle(s: NotificationSnapshot): TitleInfo {
        val conv = TextRules.cleanTitle(s.conversationTitle)
        val title = TextRules.cleanTitle(s.title)?.takeUnless { isGenericTitle(it, s.packageName) }
        val isGroup = s.isGroupConversation
        val sender = when {
            conv == null -> title
            title != null && !title.equals(conv, ignoreCase = true) -> title
            isGroup -> null
            else -> conv
        }
        return TitleInfo(conversationTitle = conv ?: title, sender = sender, isGroup = isGroup)
    }

    /** Adapter-specific body cleanup (e.g. unread counters some apps prefix to the text). */
    protected open fun cleanBody(text: String): String = text

    /** Explicit deletion marker recognition. The generic parser never reports deletion evidence. */
    protected open fun deletionMarker(text: String, sender: String?, knownNames: Collection<String>): DeletionMarkerRules.Hit? = null

    /** Noise check for flat (non-MessagingStyle) notifications. */
    protected open fun isFlatNoise(text: String): Boolean = TextRules.isNoise(text)

    /** Noise check for MessagingStyle messages. MessagingStyle content is trusted by default. */
    protected open fun isMessagingNoise(text: String): Boolean = false

    /** True for titles that name the app or a summary rather than a conversation. */
    protected open fun isGenericTitle(title: String, packageName: String): Boolean {
        if (TextRules.isNoise(title)) return true
        val label = KnownApps.labelFor(packageName) ?: return false
        return title.equals(label, ignoreCase = true)
    }

    // --- MessagingStyle -----------------------------------------------------------------------------------------------

    private fun mergeMessages(s: NotificationSnapshot): List<SnapshotMessage> {
        if (s.historicMessages.isEmpty()) return s.messages
        val present = s.messages.mapTo(HashSet()) { (TextRules.cleanName(it.sender)) to it.timestamp }
        val older = s.historicMessages.filter { (TextRules.cleanName(it.sender) to it.timestamp) !in present }
        return older + s.messages
    }

    private fun parseMessaging(s: NotificationSnapshot, merged: List<SnapshotMessage>, redacted: Boolean): ParseOutcome {
        val titleInfo = parseTitle(s)
        val selfName = TextRules.cleanName(s.selfName)
        fun isSelf(name: String?) = name == null || (selfName != null && name.equals(selfName, ignoreCase = true))

        val otherSenders = merged.mapNotNull { m -> TextRules.cleanName(m.sender)?.takeUnless { isSelf(it) } }.distinct()
        val conversationTitle = titleInfo.conversationTitle ?: otherSenders.firstOrNull() ?: placeholders.selfSender
        val isGroup = s.isGroupConversation || titleInfo.isGroup ||
            (TextRules.cleanTitle(s.conversationTitle) != null && otherSenders.size > 1)
        val conversationKey = ConversationKeys.of(s.shortcutId, conversationTitle, otherSenders.firstOrNull())
        val knownNames = (otherSenders + listOfNotNull(conversationTitle, titleInfo.sender)).distinct()

        val out = LinkedHashMap<MessageIdentity, CapturedMessage>()
        val markers = ArrayList<DeletionMarker>()
        var noise = 0
        for (m in merged) {
            val rawSender = TextRules.cleanName(m.sender)
            val sender = if (isSelf(rawSender)) placeholders.selfSender else rawSender
            val precise = m.timestamp > 0
            val timestamp = if (precise) m.timestamp else fallbackTimestamp(s)
            val raw = m.text?.let { TextRules.stripInvisible(it).trim() }
            val text: String
            val kind: ContentKind
            when {
                raw.isNullOrEmpty() -> when {
                    m.dataMimeType != null -> { text = mediaLabel(m.dataMimeType); kind = ContentKind.MEDIA_PLACEHOLDER }
                    redacted -> { text = placeholders.systemRedacted; kind = ContentKind.SYSTEM_REDACTED }
                    else -> continue
                }
                SystemRedaction.isPlaceholderText(raw) -> {
                    text = placeholders.systemRedacted; kind = ContentKind.SYSTEM_REDACTED
                }
                else -> {
                    val body = TextRules.clampBody(cleanBody(raw).trim())
                    if (body.isEmpty()) continue
                    val hit = deletionMarker(body, sender, knownNames)
                    if (hit != null) {
                        markers += marker(s, conversationKey, if (hit.isSelf) placeholders.selfSender else hit.sender ?: sender, body, timestamp)
                        continue
                    }
                    if (isMessagingNoise(body)) { noise++; continue }
                    text = body
                    kind = if (m.dataMimeType != null || TextRules.isMediaPlaceholder(body)) {
                        ContentKind.MEDIA_PLACEHOLDER
                    } else {
                        ContentKind.TEXT
                    }
                }
            }
            val identity = MessageIdentity(sender, timestamp, text)
            if (identity in out) continue
            out[identity] = CapturedMessage(
                packageName = s.packageName,
                conversationKey = conversationKey,
                conversationTitle = conversationTitle,
                isGroup = isGroup,
                sender = sender,
                text = text,
                contentKind = kind,
                messageTimestamp = timestamp,
                hasPreciseTimestamp = precise,
                mediaUri = m.dataUri?.takeIf { m.dataMimeType != null },
                mediaMimeType = m.dataMimeType?.takeIf { m.dataUri != null },
            )
        }
        return finish(out.values.toList(), markers, noise)
    }

    // --- Inbox / BigText / plain ------------------------------------------------------------------------------------

    private fun parseFlat(s: NotificationSnapshot, redacted: Boolean): ParseOutcome {
        val titleInfo = parseTitle(s)
        val inboxLines = s.textLines.filter { it.isNotBlank() }
        val lines: List<String> = when {
            inboxLines.isNotEmpty() -> inboxLines
            !s.bigText.isNullOrBlank() -> listOf(s.bigText)
            !s.text.isNullOrBlank() -> listOf(s.text)
            !s.tickerText.isNullOrBlank() && s.title.isNullOrBlank() -> listOf(s.tickerText)
            redacted -> listOf("")
            else -> return ParseOutcome.Rejected(RejectReason.EMPTY)
        }
        if (lines.any { TextRules.isCallText(it) } || (s.title != null && TextRules.isCallText(s.title))) {
            return ParseOutcome.Rejected(RejectReason.CALL)
        }
        val split = alwaysSplitSenderPrefix || inboxLines.isNotEmpty() || titleInfo.isGroup || titleInfo.sender == null
        val timestamp = fallbackTimestamp(s)
        val out = LinkedHashMap<MessageIdentity, CapturedMessage>()
        val markers = ArrayList<DeletionMarker>()
        var noise = 0
        for (line in lines) {
            val raw = TextRules.stripInvisible(line).trim()
            var sender = titleInfo.sender
            val text: String
            val kind: ContentKind
            if (raw.isEmpty() || SystemRedaction.isPlaceholderText(raw)) {
                text = placeholders.systemRedacted
                kind = ContentKind.SYSTEM_REDACTED
            } else {
                var body = cleanBody(raw).trim()
                if (split) {
                    TextRules.splitSenderPrefix(body)?.let { (name, rest) -> sender = name; body = cleanBody(rest).trim() }
                }
                body = TextRules.clampBody(body)
                if (body.isEmpty()) continue
                val conversationTitle = titleInfo.conversationTitle ?: sender
                val knownNames = listOfNotNull(sender, conversationTitle)
                val hit = deletionMarker(body, sender, knownNames)
                if (hit != null) {
                    val key = ConversationKeys.of(s.shortcutId, conversationTitle, sender)
                    markers += marker(s, key, if (hit.isSelf) placeholders.selfSender else hit.sender ?: sender, body, timestamp)
                    continue
                }
                if (isFlatNoise(body) || TextRules.isCallText(body)) { noise++; continue }
                text = body
                kind = if (TextRules.isMediaPlaceholder(body)) ContentKind.MEDIA_PLACEHOLDER else ContentKind.TEXT
            }
            val conversationTitle = titleInfo.conversationTitle ?: sender ?: KnownApps.labelFor(s.packageName) ?: s.packageName
            val identity = MessageIdentity(sender, timestamp, text)
            if (identity in out) continue
            out[identity] = CapturedMessage(
                packageName = s.packageName,
                conversationKey = ConversationKeys.of(s.shortcutId, conversationTitle, sender),
                conversationTitle = conversationTitle,
                isGroup = titleInfo.isGroup || s.isGroupConversation,
                sender = sender,
                text = text,
                contentKind = kind,
                messageTimestamp = timestamp,
                hasPreciseTimestamp = false,
            )
        }
        return finish(out.values.toList(), markers, noise)
    }

    // --- Helpers ------------------------------------------------------------------------------------------------------

    private fun finish(messages: List<CapturedMessage>, markers: List<DeletionMarker>, noise: Int): ParseOutcome =
        if (messages.isEmpty() && markers.isEmpty() && noise > 0) {
            ParseOutcome.Rejected(RejectReason.NOISE)
        } else {
            ParseOutcome.Parsed(messages.sortedBy { it.messageTimestamp }, markers)
        }

    private fun marker(s: NotificationSnapshot, key: String, sender: String?, text: String, timestamp: Long) =
        DeletionMarker(
            packageName = s.packageName,
            conversationKey = key,
            sender = sender,
            markerText = text.take(MAX_MARKER_LENGTH),
            adapterId = adapterId,
            markerTimestamp = timestamp,
        )

    /** Notification.when when the app set it, otherwise the post time. */
    protected fun fallbackTimestamp(s: NotificationSnapshot): Long = if (s.whenTime > 0) s.whenTime else s.postTime

    private fun mediaLabel(mime: String): String = when {
        mime.startsWith("image/") -> placeholders.mediaPhoto
        mime.startsWith("video/") -> placeholders.mediaVideo
        mime.startsWith("audio/") -> placeholders.mediaAudio
        else -> placeholders.mediaAttachment
    }

    private data class MessageIdentity(val sender: String?, val timestamp: Long, val text: String)

    companion object {
        const val GENERIC_ID = "generic"
        private const val MAX_MARKER_LENGTH = 120

        fun isMessagingStyle(s: NotificationSnapshot): Boolean = isMessagingStyle(s.template, s.messages.isNotEmpty())

        fun isMessagingStyle(template: String?, hasMessages: Boolean): Boolean =
            SystemRedaction.isMessagingTemplate(template) || hasMessages
    }
}
