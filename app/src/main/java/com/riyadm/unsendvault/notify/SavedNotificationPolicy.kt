package com.riyadm.unsendvault.notify

import com.riyadm.unsendvault.capture.IngestResult
import com.riyadm.unsendvault.capture.ParsedNotification
import com.riyadm.unsendvault.core.model.VaultSettings

/**
 * Decides which newly stored messages deserve UnsendVault's own (opt-in, silent) notification. Android-free so it can
 * be unit-tested. The rules keep the notification about *live* messages:
 * - only when the user turned the feature on;
 * - never for the catch-up pass after a reboot/rebind (the source notification was posted long before it was stored);
 * - never for old history that a chat app re-posts alongside a new message;
 * - never for the user's own messages (e.g. a reply sent from UnsendVault or the chat app).
 */
class SavedNotificationPolicy(
    private val selfSender: String,
    private val maxPostAgeMillis: Long = MAX_POST_AGE_MS,
    private val maxMessageAgeMillis: Long = MAX_MESSAGE_AGE_MS,
) {
    data class Update(
        val conversationId: Long,
        val packageName: String,
        val appLabel: String,
        val conversationTitle: String,
        val isGroup: Boolean,
        /** "sender: text" (groups) or "text", oldest first. Only rendered when the user allows content. */
        val lines: List<String>,
        val newMessages: Int,
        val possiblyDeleted: Int,
        val latestAt: Long,
    )

    fun updates(settings: VaultSettings, parsed: ParsedNotification, result: IngestResult, capturedAt: Long): List<Update> {
        if (!settings.savedMessageNotifications || !settings.captureEnabled) return emptyList()
        if (capturedAt - parsed.postTime > maxPostAgeMillis) return emptyList()
        val fresh = result.newMessages.filter { ref ->
            val m = ref.message
            m.sender != selfSender && capturedAt - m.messageTimestamp <= maxMessageAgeMillis
        }
        val byConversation = fresh.groupBy { it.conversationId }
        val ids = LinkedHashSet<Long>().apply {
            addAll(byConversation.keys)
            addAll(result.possiblyDeletedIn)
        }
        return ids.mapNotNull { id ->
            val refs = byConversation[id].orEmpty().sortedBy { it.message.messageTimestamp }
            val deleted = if (id in result.possiblyDeletedIn) 1 else 0
            val first = refs.firstOrNull()?.message
            val title = first?.conversationTitle ?: parsed.messages.firstOrNull()?.conversationTitle
            if (refs.isEmpty() && deleted == 0) return@mapNotNull null
            Update(
                conversationId = id,
                packageName = parsed.packageName,
                appLabel = parsed.appLabel,
                conversationTitle = title ?: parsed.appLabel,
                isGroup = first?.isGroup ?: false,
                lines = refs.map { r ->
                    val m = r.message
                    if (m.isGroup && !m.sender.isNullOrBlank()) "${m.sender}: ${m.text}" else m.text
                },
                newMessages = refs.size,
                possiblyDeleted = deleted,
                latestAt = refs.lastOrNull()?.message?.messageTimestamp ?: capturedAt,
            )
        }
    }

    companion object {
        /** A source notification older than this when stored came from catch-up, not a live message. */
        const val MAX_POST_AGE_MS = 2 * 60_000L

        /** Older messages in a re-posted notification are history, not news. */
        const val MAX_MESSAGE_AGE_MS = 10 * 60_000L
    }
}

/** Running per-chat state behind one notification: count and the last few lines since the user last looked. */
class SavedNotificationState(private val maxLines: Int = MAX_LINES) {
    data class Entry(
        val update: SavedNotificationPolicy.Update,
        val totalNew: Int,
        val totalDeleted: Int,
        val lines: List<String>,
    )

    private val entries = HashMap<Long, Entry>()

    /** Merges [update] into the chat's entry. [stillShowing] false means the user dismissed it: start over. */
    @Synchronized
    fun merge(update: SavedNotificationPolicy.Update, stillShowing: Boolean): Entry {
        val previous = entries[update.conversationId]?.takeIf { stillShowing }
        val entry = Entry(
            update = update,
            totalNew = (previous?.totalNew ?: 0) + update.newMessages,
            totalDeleted = (previous?.totalDeleted ?: 0) + update.possiblyDeleted,
            lines = ((previous?.lines ?: emptyList()) + update.lines).takeLast(maxLines),
        )
        entries[update.conversationId] = entry
        return entry
    }

    @Synchronized
    fun remove(conversationId: Long) {
        entries.remove(conversationId)
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun activeCount(): Int = entries.size

    companion object {
        const val MAX_LINES = 5
    }
}
