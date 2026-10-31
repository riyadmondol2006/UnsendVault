package com.riyadm.unsendvault.ui.conversation

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riyadm.unsendvault.core.model.ArchivedMessage
import com.riyadm.unsendvault.core.model.Conversation
import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.data.VaultRepository
import com.riyadm.unsendvault.reply.ReplyAvailability
import com.riyadm.unsendvault.reply.ReplyGateway
import com.riyadm.unsendvault.reply.ReplyResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Timeline rows, newest first, for a reversed (bottom-anchored) chat list. */
@Immutable
sealed interface TimelineItem {
    val key: String

    data class Message(val message: ArchivedMessage) : TimelineItem {
        override val key: String get() = "m${message.id}"
    }

    /** Placed after the oldest message of its day, so it renders above that day in a reversed list. */
    data class DayHeader(val day: LocalDate, val millis: Long) : TimelineItem {
        override val key: String get() = "d$day"
    }
}

fun buildTimeline(newestFirst: List<ArchivedMessage>, zone: ZoneId): List<TimelineItem> {
    val out = ArrayList<TimelineItem>(newestFirst.size + 8)
    newestFirst.forEachIndexed { index, message ->
        out += TimelineItem.Message(message)
        val day = Instant.ofEpochMilli(message.messageTimestamp).atZone(zone).toLocalDate()
        val nextOlder = newestFirst.getOrNull(index + 1)?.let { Instant.ofEpochMilli(it.messageTimestamp).atZone(zone).toLocalDate() }
        if (nextOlder != day) out += TimelineItem.DayHeader(day, message.messageTimestamp)
    }
    return out
}

@Immutable
data class ConversationUiState(
    val loading: Boolean = true,
    val conversation: Conversation? = null,
    /** Newest first (rendered bottom-up in a reversed list, like a chat). */
    val messages: List<ArchivedMessage> = emptyList(),
    val timeline: List<TimelineItem> = emptyList(),
    val selection: Set<Long> = emptySet(),
    /** Null while unknown, or when replying isn't offered at all. */
    val reply: ReplyAvailability? = null,
    val sendingReply: Boolean = false,
) {
    val selecting: Boolean get() = selection.isNotEmpty()
}

sealed interface ConversationEvent {
    data class MessagesDeleted(val count: Int) : ConversationEvent
    data object ConversationDeleted : ConversationEvent
    data class Reply(val result: ReplyResult) : ConversationEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModel(
    private val conversationId: Long,
    private val vault: VaultRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val replyGateway: ReplyGateway? = null,
) : ViewModel() {
    private val selection = MutableStateFlow<Set<Long>>(emptySet())
    private val replyRefresh = MutableStateFlow(0)
    private val sending = MutableStateFlow(false)
    private val conversation = vault.observeConversation(conversationId)
    private val _events = Channel<ConversationEvent>(Channel.BUFFERED)
    val events: Flow<ConversationEvent> = _events.receiveAsFlow()

    private val messages = vault.observeMessages(MessageQuery(conversationId = conversationId, limit = MESSAGE_LIMIT))
        .map { it to buildTimeline(it, zone) }

    /**
     * Re-checked when the chat changes (a new message usually means the app re-posted its notification), when the
     * screen resumes and after each send. Each check reads only this app's active notifications.
     */
    private val replyAvailability = if (replyGateway == null) {
        flowOf(null)
    } else {
        combine(conversation.map { c -> c?.let { Triple(it.packageName, it.conversationKey, it.lastMessageAt) } }.distinctUntilChanged(), replyRefresh) { c, _ -> c }
            .mapLatest { c ->
                c?.let { (pkg, key, _) ->
                    try {
                        replyGateway.availability(pkg, key)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
    }

    private val replyState = combine(replyAvailability, sending) { a, s -> a to s }

    val state: StateFlow<ConversationUiState> = combine(
        conversation,
        messages,
        selection,
        replyState,
    ) { conversation, (list, timeline), selected, (reply, isSending) ->
        val ids = list.mapTo(HashSet()) { it.id }
        ConversationUiState(false, conversation, list, timeline, selected.filterTo(LinkedHashSet()) { it in ids }, reply, isSending)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationUiState())

    fun refreshReply() = replyRefresh.update { it + 1 }

    fun sendReply(text: String) {
        val gateway = replyGateway ?: return
        val c = state.value.conversation ?: return
        if (sending.value) return
        viewModelScope.launch {
            sending.value = true
            val result = try {
                gateway.send(c.packageName, c.conversationKey, text)
            } catch (e: Exception) {
                ReplyResult.FAILED
            }
            sending.value = false
            _events.send(ConversationEvent.Reply(result))
            refreshReply()
        }
    }

    fun toggleSelection(id: Long) = selection.update { if (id in it) it - id else it + id }

    fun selectAll() = selection.update { state.value.messages.mapTo(LinkedHashSet()) { it.id } }

    fun clearSelection() = selection.update { emptySet() }

    fun toggleFavorite(message: ArchivedMessage) {
        viewModelScope.launch { vault.setFavorite(message.id, !message.isFavorite) }
    }

    fun deleteSelected() {
        val ids = selection.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            vault.deleteMessages(ids)
            selection.value = emptySet()
            _events.send(ConversationEvent.MessagesDeleted(ids.size))
        }
    }

    fun deleteConversation() {
        viewModelScope.launch {
            vault.deleteConversation(conversationId)
            _events.send(ConversationEvent.ConversationDeleted)
        }
    }

    companion object {
        const val MESSAGE_LIMIT = 2_000
    }
}
