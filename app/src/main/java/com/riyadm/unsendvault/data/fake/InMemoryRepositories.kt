package com.riyadm.unsendvault.data.fake

import com.riyadm.unsendvault.capture.IngestResult
import com.riyadm.unsendvault.capture.ParsedNotification
import com.riyadm.unsendvault.capture.RemovalEvent
import com.riyadm.unsendvault.core.model.AppSummary
import com.riyadm.unsendvault.core.model.ArchivedMessage
import com.riyadm.unsendvault.core.model.Conversation
import com.riyadm.unsendvault.core.model.MessageDetail
import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.core.model.MessageRevision
import com.riyadm.unsendvault.core.model.MessageStatus
import com.riyadm.unsendvault.core.model.VaultSettings
import com.riyadm.unsendvault.core.model.VaultStats
import com.riyadm.unsendvault.data.SettingsRepository
import com.riyadm.unsendvault.data.VaultRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Simple in-memory implementations used for Compose previews, UI/widget tests and as the skeleton default.
 * Not used in production builds once the Room implementation is wired in [com.riyadm.unsendvault.data.DataModule].
 */
class InMemoryVaultRepository(
    initialConversations: List<Conversation> = emptyList(),
    initialMessages: List<ArchivedMessage> = emptyList(),
) : VaultRepository {
    private val conversations = MutableStateFlow(initialConversations)
    private val messages = MutableStateFlow(initialMessages)
    private val revisions = MutableStateFlow<List<MessageRevision>>(emptyList())
    private var nextId = (initialMessages.maxOfOrNull { it.id } ?: 0L) + 1
    private var nextConversationId = (initialConversations.maxOfOrNull { it.id } ?: 0L) + 1

    override fun observeMessages(query: MessageQuery): Flow<List<ArchivedMessage>> = messages.map { all ->
        all.asSequence()
            .filter { m -> query.text.isNullOrBlank() || m.text.contains(query.text, true) || (m.sender?.contains(query.text, true) == true) || m.conversationTitle.contains(query.text, true) }
            .filter { m -> query.packages.isEmpty() || m.packageName in query.packages }
            .filter { m -> query.conversationId == null || m.conversationId == query.conversationId }
            .filter { m -> query.fromMillis == null || m.messageTimestamp >= query.fromMillis }
            .filter { m -> query.toMillis == null || m.messageTimestamp <= query.toMillis }
            .filter { m -> !query.favoritesOnly || m.isFavorite }
            .filter { m -> !query.possiblyDeletedOnly || m.status == MessageStatus.POSSIBLY_DELETED }
            .sortedByDescending { it.messageTimestamp }
            .take(query.limit)
            .toList()
    }

    override fun observeConversations(packages: Set<String>): Flow<List<Conversation>> = conversations.map { list ->
        list.filter { packages.isEmpty() || it.packageName in packages }.sortedByDescending { it.lastMessageAt }
    }

    override fun observeConversation(id: Long): Flow<Conversation?> = conversations.map { list -> list.firstOrNull { it.id == id } }

    override fun observeMessage(id: Long): Flow<MessageDetail?> = messages.map { all ->
        all.firstOrNull { it.id == id }?.let { m -> MessageDetail(m, revisions.value.filter { it.messageId == id }) }
    }

    override fun observeAppSummaries(): Flow<List<AppSummary>> = messages.map { all ->
        all.groupBy { it.packageName }.map { (pkg, list) ->
            AppSummary(pkg, list.first().appLabel, list.size, list.map { it.conversationId }.distinct().size, list.maxOf { it.messageTimestamp })
        }.sortedByDescending { it.lastMessageAt }
    }

    override fun observeStats(): Flow<VaultStats> = messages.map { all ->
        VaultStats(
            totalMessages = all.size,
            totalConversations = all.map { it.conversationId }.distinct().size,
            favorites = all.count { it.isFavorite },
            possiblyDeleted = all.count { it.status == MessageStatus.POSSIBLY_DELETED },
            apps = all.map { it.packageName }.distinct().size,
            oldestCapturedAt = all.minOfOrNull { it.firstCapturedAt },
            newestCapturedAt = all.maxOfOrNull { it.firstCapturedAt },
        )
    }

    override fun observeRecent(limit: Int): Flow<List<ArchivedMessage>> =
        messages.map { all -> all.sortedByDescending { it.messageTimestamp }.take(limit) }

    override fun observeFavorites(limit: Int): Flow<List<ArchivedMessage>> =
        messages.map { all -> all.filter { it.isFavorite }.sortedByDescending { it.messageTimestamp }.take(limit) }

    override suspend fun ingest(parsed: ParsedNotification, capturedAt: Long): IngestResult {
        var inserted = 0
        var unchanged = 0
        val saved = ArrayList<IngestResult.SavedRef>()
        parsed.messages.forEach { c ->
            val conv = conversations.value.firstOrNull { it.packageName == c.packageName && it.conversationKey == c.conversationKey }
                ?: Conversation(nextConversationId++, c.packageName, parsed.appLabel, c.conversationKey, c.conversationTitle, c.isGroup, c.messageTimestamp, 0, null)
                    .also { new -> conversations.update { it + new } }
            val exists = messages.value.any { it.conversationId == conv.id && it.sender == c.sender && it.messageTimestamp == c.messageTimestamp && it.text == c.text }
            if (exists) {
                unchanged++
            } else {
                inserted++
                saved += IngestResult.SavedRef(conv.id, c)
                messages.update {
                    it + ArchivedMessage(
                        nextId++, conv.id, c.packageName, parsed.appLabel, c.conversationTitle, c.sender, c.text, c.contentKind,
                        c.messageTimestamp, capturedAt, capturedAt, false, MessageStatus.ACTIVE, null, 1, null, null,
                    )
                }
            }
        }
        return IngestResult(inserted, 0, unchanged, 0, saved)
    }

    override suspend fun recordRemoval(event: RemovalEvent) = Unit

    override suspend fun setFavorite(messageId: Long, favorite: Boolean) =
        messages.update { list -> list.map { if (it.id == messageId) it.copy(isFavorite = favorite) else it } }

    override suspend fun deleteMessages(ids: Collection<Long>) = messages.update { list -> list.filterNot { it.id in ids } }

    override suspend fun deleteConversation(conversationId: Long) {
        messages.update { list -> list.filterNot { it.conversationId == conversationId } }
        conversations.update { list -> list.filterNot { it.id == conversationId } }
    }

    override suspend fun deleteApp(packageName: String) {
        messages.update { list -> list.filterNot { it.packageName == packageName } }
        conversations.update { list -> list.filterNot { it.packageName == packageName } }
    }

    override suspend fun deleteAll() {
        messages.value = emptyList()
        conversations.value = emptyList()
        revisions.value = emptyList()
    }

    override suspend fun deleteOlderThan(cutoffMillis: Long, keepFavorites: Boolean): Int {
        val before = messages.value.size
        messages.update { list -> list.filterNot { it.messageTimestamp < cutoffMillis && !(keepFavorites && it.isFavorite) } }
        return before - messages.value.size
    }
}

class InMemorySettingsRepository(initial: VaultSettings = VaultSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<VaultSettings> = state
    override val current: VaultSettings get() = state.value
    override suspend fun update(transform: (VaultSettings) -> VaultSettings) = state.update(transform)
}
