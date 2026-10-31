package com.riyadm.unsendvault.data

import com.riyadm.unsendvault.data.Fx.T0
import com.riyadm.unsendvault.data.Fx.marker
import com.riyadm.unsendvault.data.Fx.msg
import com.riyadm.unsendvault.data.Fx.parsed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The per-row details ingest reports for saved-message notifications. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IngestDetailsTest : RoomTestBase() {

    @Test
    fun reportsOnlyNewRows_withTheirConversation() = runBlocking {
        val first = repo.ingest(parsed(msg("hi", ts = T0), msg("other chat", key = "c2", title = "Bob", sender = "Bob")), T0)
        val conversations = repo.observeConversations().first().associateBy { it.conversationKey }
        assertEquals(
            listOf("hi" to conversations.getValue("c1").id, "other chat" to conversations.getValue("c2").id),
            first.newMessages.map { it.message.text to it.conversationId },
        )
        // Re-post with one new message: only that one is new; the edit/unchanged rows aren't.
        val second = repo.ingest(parsed(msg("hi", ts = T0), msg("again", ts = T0 + 1_000)), T0 + 2_000)
        assertEquals(listOf("again"), second.newMessages.map { it.message.text })
        assertTrue(second.possiblyDeletedIn.isEmpty())
    }

    @Test
    fun reportsConversationOfAPossiblyDeletedMessage() = runBlocking {
        repo.ingest(parsed(msg("secret", sender = "Bob", ts = T0)), T0)
        val id = repo.observeConversations().first().single().id
        val r = repo.ingest(parsed(markers = listOf(marker("Bob", T0))), T0 + 60_000)
        assertEquals(setOf(id), r.possiblyDeletedIn)
        assertTrue(r.newMessages.isEmpty())
    }
}
