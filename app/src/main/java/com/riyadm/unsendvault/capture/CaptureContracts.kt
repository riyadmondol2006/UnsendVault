package com.riyadm.unsendvault.capture

import com.riyadm.unsendvault.core.model.ContentKind
import com.riyadm.unsendvault.core.model.RemovalReason

/**
 * Plain, Android-free copy of the fields UnsendVault reads from a StatusBarNotification. The listener service builds
 * this on its callback thread (cheap field copies only) and hands it to a background worker; parsers and adapters
 * operate on it so they can be unit-tested on the JVM.
 */
data class NotificationSnapshot(
    val packageName: String,
    /** StatusBarNotification.key — unique per posted notification. */
    val key: String,
    val id: Int,
    val tag: String?,
    val postTime: Long,
    /** Notification.when */
    val whenTime: Long,
    val category: String?,
    val channelId: String?,
    val groupKey: String?,
    val isGroupSummary: Boolean,
    val isOngoing: Boolean,
    /** Notification.shortcutId (API 26+) — stable conversation id for apps that publish conversation shortcuts. */
    val shortcutId: String?,
    /** Notification.extras template class name, e.g. "android.app.Notification$MessagingStyle". */
    val template: String?,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val summaryText: String?,
    val textLines: List<String>,
    /** EXTRA_CONVERSATION_TITLE */
    val conversationTitle: String?,
    /** EXTRA_IS_GROUP_CONVERSATION */
    val isGroupConversation: Boolean,
    /** EXTRA_SELF_DISPLAY_NAME / messagingUser name */
    val selfName: String?,
    /** EXTRA_MESSAGES */
    val messages: List<SnapshotMessage>,
    /** EXTRA_HISTORIC_MESSAGES */
    val historicMessages: List<SnapshotMessage>,
    val tickerText: String?,
    /** True when the system replaced content (e.g. Android 15 sensitive-notification redaction). Best-effort. */
    val looksSystemRedacted: Boolean = false,
)

data class SnapshotMessage(
    val sender: String?,
    val text: String?,
    val timestamp: Long,
    /** Message.dataMimeType when the message is an image/media item. */
    val dataMimeType: String? = null,
)

/** One message extracted from a notification, ready to be archived. */
data class CapturedMessage(
    val packageName: String,
    /** Stable key for the conversation inside its app (shortcut id, normalized title, or sender). */
    val conversationKey: String,
    val conversationTitle: String,
    val isGroup: Boolean,
    val sender: String?,
    val text: String,
    val contentKind: ContentKind,
    /** Timestamp attached by the source app; falls back to the notification post time. */
    val messageTimestamp: Long,
    /** True when [messageTimestamp] came from a MessagingStyle message (precise identity), false for fallbacks. */
    val hasPreciseTimestamp: Boolean,
)

/** An explicit "message deleted/unsent" marker the source app displayed. */
data class DeletionMarker(
    val packageName: String,
    val conversationKey: String,
    /** Sender whose message was removed, if the marker names one. */
    val sender: String?,
    val markerText: String,
    val adapterId: String,
    /** Timestamp of the marker message itself (may equal the deleted message's timestamp for some apps). */
    val markerTimestamp: Long,
)

/** Result of parsing one notification. */
data class ParsedNotification(
    val packageName: String,
    val appLabel: String,
    val notificationKey: String,
    val postTime: Long,
    val adapterId: String,
    val messages: List<CapturedMessage>,
    val deletionMarkers: List<DeletionMarker> = emptyList(),
)

/** A removal event, recorded for transparency only — never treated as proof that a message was deleted. */
data class RemovalEvent(
    val packageName: String,
    val notificationKey: String,
    val reason: RemovalReason,
    val removedAt: Long,
)

/** Outcome of [com.riyadm.unsendvault.data.VaultRepository.ingest]. */
data class IngestResult(
    val inserted: Int,
    val updated: Int,
    val unchanged: Int,
    val markedPossiblyDeleted: Int,
    /** Messages stored as new rows by this ingest, with their conversation id. In memory only (saved-message notifications). */
    val newMessages: List<SavedRef> = emptyList(),
    /** Conversations in which a message was newly marked possibly deleted. */
    val possiblyDeletedIn: Set<Long> = emptySet(),
) {
    data class SavedRef(val conversationId: Long, val message: CapturedMessage)

    val changed: Boolean get() = inserted > 0 || updated > 0 || markedPossiblyDeleted > 0

    companion object {
        val EMPTY = IngestResult(0, 0, 0, 0)
    }
}
