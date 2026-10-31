package com.riyadm.unsendvault.data

import com.riyadm.unsendvault.capture.IngestResult
import com.riyadm.unsendvault.capture.RemovalEvent
import com.riyadm.unsendvault.core.model.ContentKind
import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.core.model.MessageStatus
import com.riyadm.unsendvault.core.model.RemovalReason
import com.riyadm.unsendvault.data.Fx.MIN
import com.riyadm.unsendvault.data.Fx.T0
import com.riyadm.unsendvault.data.Fx.marker
import com.riyadm.unsendvault.data.Fx.msg
import com.riyadm.unsendvault.data.Fx.parsed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IngestTest : RoomTestBase() {

    private suspend fun all() = repo.observeMessages(MessageQuery()).first()

    @Test
    fun precise_sameIdentitySameText_isUnchanged() = runBlocking {
        assertEquals(IngestResult(1, 0, 0, 0), repo.ingest(parsed(msg("hi")), T0).counts())
        assertEquals(IngestResult(0, 0, 1, 0), repo.ingest(parsed(msg("hi")), T0 + 1000).counts())
        // Whitespace/invisible-character differences are the same text.
        assertEquals(IngestResult(0, 0, 1, 0), repo.ingest(parsed(msg("  hi​ ")), T0 + 2000).counts())
        assertEquals(1, all().size)
    }

    @Test
    fun precise_sameIdentityDifferentText_recordsRevision() = runBlocking {
        repo.ingest(parsed(msg("first")), T0)
        val r = repo.ingest(parsed(msg("second (edited)")), T0 + 5_000)
        assertEquals(IngestResult(0, 1, 0, 0), r.counts())
        val m = all().single()
        assertEquals("second (edited)", m.text)
        assertEquals(2, m.revisionCount)
        assertEquals(T0 + 5_000, m.lastUpdatedAt)
        assertEquals(T0, m.firstCapturedAt)
        val detail = repo.observeMessage(m.id).first()!!
        assertEquals(listOf("first"), detail.revisions.map { it.text })
        assertEquals(T0, detail.revisions.single().capturedAt)

        repo.ingest(parsed(msg("third")), T0 + 9_000)
        val detail2 = repo.observeMessage(m.id).first()!!
        assertEquals(3, detail2.message.revisionCount)
        assertEquals(listOf("first", "second (edited)"), detail2.revisions.map { it.text })
    }

    @Test
    fun precise_differentTimestamp_isNewMessage() = runBlocking {
        repo.ingest(parsed(msg("ok", ts = T0), msg("ok", ts = T0 + 1)), T0)
        assertEquals(2, all().size)
    }

    @Test
    fun imprecise_sameTextWithinWindow_isDeduped_outsideWindowIsNew() = runBlocking {
        repo.ingest(parsed(msg("see you", ts = T0, precise = false)), T0)
        assertEquals(IngestResult(0, 0, 1, 0), repo.ingest(parsed(msg("see you", ts = T0 + 9 * MIN, precise = false)), T0).counts())
        assertEquals(IngestResult(0, 0, 1, 0), repo.ingest(parsed(msg("see you", ts = T0 - 9 * MIN, precise = false)), T0).counts())
        assertEquals(IngestResult(1, 0, 0, 0), repo.ingest(parsed(msg("see you", ts = T0 + 11 * MIN, precise = false)), T0).counts())
        // Different text at the same time is a different message (no update history for imprecise identity).
        assertEquals(IngestResult(1, 0, 0, 0), repo.ingest(parsed(msg("see you!!", ts = T0, precise = false)), T0).counts())
        assertEquals(3, all().size)
    }

    @Test
    fun imprecise_thenPrecise_upgradesInsteadOfDuplicating() = runBlocking {
        repo.ingest(parsed(msg("lunch?", ts = T0, precise = false)), T0)
        val r = repo.ingest(parsed(msg("lunch?", ts = T0 + 2 * MIN, precise = true)), T0)
        assertEquals(IngestResult(0, 0, 1, 0), r.counts())
        val m = all().single()
        assertEquals(T0 + 2 * MIN, m.messageTimestamp)
        // And now a precise duplicate is found by exact identity.
        assertEquals(IngestResult(0, 0, 1, 0), repo.ingest(parsed(msg("lunch?", ts = T0 + 2 * MIN)), T0).counts())
    }

    @Test
    fun differentSendersOrConversations_areDistinct() = runBlocking {
        repo.ingest(parsed(msg("yo", sender = "Alice"), msg("yo", sender = "Bob"), msg("yo", sender = null), msg("yo", key = "c2")), T0)
        assertEquals(4, all().size)
        assertEquals(IngestResult(0, 0, 4, 0), repo.ingest(parsed(msg("yo", sender = "Alice"), msg("yo", sender = "Bob"), msg("yo", sender = null), msg("yo", key = "c2")), T0).counts())
    }

    @Test
    fun placeholder_doesNotOverwriteRealText() = runBlocking {
        repo.ingest(parsed(msg("my real text")), T0)
        val r1 = repo.ingest(parsed(msg("Sensitive notification content hidden", kind = ContentKind.SYSTEM_REDACTED)), T0 + 1)
        val r2 = repo.ingest(parsed(msg("[filtered]", kind = ContentKind.FILTERED_SENSITIVE)), T0 + 2)
        assertEquals(IngestResult(0, 0, 1, 0), r1.counts())
        assertEquals(IngestResult(0, 0, 1, 0), r2.counts())
        val m = all().single()
        assertEquals("my real text", m.text)
        assertEquals(ContentKind.TEXT, m.contentKind)
        assertEquals(1, m.revisionCount)
    }

    @Test
    fun realText_replacesPlaceholder_withoutRevision() = runBlocking {
        repo.ingest(parsed(msg("Content hidden", kind = ContentKind.SYSTEM_REDACTED)), T0)
        assertEquals(IngestResult(0, 1, 0, 0), repo.ingest(parsed(msg("actual words")), T0 + 1).counts())
        val m = all().single()
        assertEquals("actual words", m.text)
        assertEquals(ContentKind.TEXT, m.contentKind)
        assertEquals(1, m.revisionCount)
        assertTrue(repo.observeMessage(m.id).first()!!.revisions.isEmpty())
    }

    @Test
    fun marker_exactIdentity_marksAndKeepsOriginalText() = runBlocking {
        repo.ingest(parsed(msg("secret plan", sender = "Bob", ts = T0)), T0)
        val r = repo.ingest(parsed(markers = listOf(marker("Bob", T0))), T0 + 60_000)
        assertEquals(IngestResult(0, 0, 0, 1), r.counts())
        val m = all().single()
        assertEquals(MessageStatus.POSSIBLY_DELETED, m.status)
        assertEquals("secret plan", m.text)
        val ev = m.deletionEvidence!!
        assertEquals("testadapter", ev.adapterId)
        assertEquals("This message was deleted", ev.markerText)
        assertEquals(T0 + 60_000, ev.observedAt)
        // Re-applying the marker is a no-op.
        assertEquals(IngestResult.EMPTY, repo.ingest(parsed(markers = listOf(marker("Bob", T0))), T0 + 70_000))
    }

    @Test
    fun possiblyDeleted_textIsNeverRewrittenByLaterPosts() = runBlocking {
        repo.ingest(parsed(msg("keep me", sender = "Bob", ts = T0)), T0)
        repo.ingest(parsed(markers = listOf(marker("Bob", T0))), T0 + 1)
        // A later notification shows the slot with the app's replacement text but no marker was recognised.
        assertEquals(IngestResult(0, 0, 1, 0), repo.ingest(parsed(msg("You deleted this message", sender = "Bob", ts = T0)), T0 + 2).counts())
        val m = all().single()
        assertEquals("keep me", m.text)
        assertEquals(MessageStatus.POSSIBLY_DELETED, m.status)
    }

    @Test
    fun marker_singleCandidateFromNamedSender_marks() = runBlocking {
        repo.ingest(parsed(msg("from bob", sender = "Bob", ts = T0 - 5 * MIN), msg("from alice", sender = "Alice", ts = T0 - 4 * MIN)), T0)
        val r = repo.ingest(parsed(markers = listOf(marker("Bob", T0))), T0)
        assertEquals(1, r.markedPossiblyDeleted)
        val byText = all().associateBy { it.text }
        assertEquals(MessageStatus.POSSIBLY_DELETED, byText.getValue("from bob").status)
        assertEquals(MessageStatus.ACTIVE, byText.getValue("from alice").status)
    }

    @Test
    fun marker_ambiguousOrOutOfWindow_marksNothing() = runBlocking {
        repo.ingest(
            parsed(
                msg("one", sender = "Bob", ts = T0 - 5 * MIN),
                msg("two", sender = "Bob", ts = T0 - 3 * MIN),
                msg("old", sender = "Carol", ts = T0 - 20 * MIN),
                msg("x", sender = "Dan", ts = T0 - 2 * MIN),
            ),
            T0,
        )
        val r = repo.ingest(
            parsed(
                markers = listOf(
                    marker("Bob", T0), // two candidates → ambiguous
                    marker("Carol", T0), // only message is older than 15 minutes
                    marker(null, T0), // no sender and no exact identity
                    marker("Dan", T0, key = "unknown-conversation"),
                    marker("Dan", T0 - 10 * MIN), // Dan's message is *after* the marker
                ),
            ),
            T0,
        )
        assertEquals(0, r.markedPossiblyDeleted)
        assertTrue(all().all { it.status == MessageStatus.ACTIVE })
    }

    @Test
    fun marker_textTruncatedAndMarkerNeverArchivedAsMessage() = runBlocking {
        repo.ingest(parsed(msg("payload", sender = "Bob", ts = T0)), T0)
        val longMarker = "Bob deleted this message " + "x".repeat(300)
        // The same notification also lists the marker as a MessagingStyle message at the same identity.
        val r = repo.ingest(
            parsed(msg(longMarker, sender = "Bob", ts = T0), markers = listOf(marker("Bob", T0, text = longMarker))),
            T0 + 1,
        )
        assertEquals(1, r.markedPossiblyDeleted)
        assertEquals(0, r.updated)
        val m = all().single()
        assertEquals("payload", m.text)
        assertEquals(120, m.deletionEvidence!!.markerText.length)
    }

    @Test
    fun removal_isRecorded_statusNeverChanges() = runBlocking {
        repo.ingest(parsed(msg("a"), notificationKey = "k1"), T0)
        repo.ingest(parsed(msg("b", ts = T0 + 1), notificationKey = "k2"), T0)
        repo.recordRemoval(RemovalEvent(Fx.PKG, "k1", RemovalReason.APP_CANCELLED, T0 + 500))
        val byText = all().associateBy { it.text }
        assertEquals(RemovalReason.APP_CANCELLED, byText.getValue("a").lastRemovalReason)
        assertEquals(T0 + 500, byText.getValue("a").lastRemovedAt)
        assertEquals(MessageStatus.ACTIVE, byText.getValue("a").status)
        assertNull(byText.getValue("b").lastRemovalReason)

        // A later notification re-carrying "a" moves its key; a removal of k1 no longer touches it.
        repo.ingest(parsed(msg("a"), notificationKey = "k3"), T0)
        repo.recordRemoval(RemovalEvent(Fx.PKG, "k3", RemovalReason.USER_DISMISSED, T0 + 900))
        assertEquals(RemovalReason.USER_DISMISSED, all().first { it.text == "a" }.lastRemovalReason)
    }

    @Test
    fun conversationAggregates_followIngest() = runBlocking {
        repo.ingest(parsed(msg("old", ts = T0, title = "Alice")), T0)
        repo.ingest(parsed(msg("new", ts = T0 + MIN, title = "Alice B.", isGroup = true)), T0)
        val c = repo.observeConversations().first().single()
        assertEquals(2, c.messageCount)
        assertEquals(T0 + MIN, c.lastMessageAt)
        assertEquals("new", c.lastMessagePreview)
        assertEquals("Alice B.", c.title)
        assertTrue(c.isGroup)
        assertEquals(Fx.LABEL, c.appLabel)
        assertEquals(c, repo.observeConversation(c.id).first())
    }

    // Regression (found on a Pixel 6 / Android 17 with `cmd notification post -S messaging`): the poster re-stamps
    // every message with the post time on each update, and gives all messages of one post the same timestamp.
    @Test
    fun restampedRepost_isDeduped_andNewMessageAdded() = runBlocking {
        repo.ingest(parsed(msg("Draft is ready", sender = "Alex", ts = T0), msg("Will check", sender = "Sam", ts = T0)), T0)
        val r = repo.ingest(
            parsed(
                msg("Draft is ready", sender = "Alex", ts = T0 + 30_000),
                msg("Will check", sender = "Sam", ts = T0 + 30_000),
                msg("Thanks!", sender = "Alex", ts = T0 + 30_000),
            ),
            T0 + 30_000,
        )
        assertEquals(IngestResult(1, 0, 2, 0), r.counts())
        val messages = all()
        assertEquals(listOf("Draft is ready", "Thanks!", "Will check"), messages.map { it.text }.sorted())
        assertTrue(messages.all { it.revisionCount == 1 })
    }

    @Test
    fun twoMessagesSharingSenderAndTimestamp_areDistinct_notAnEdit() = runBlocking {
        repo.ingest(parsed(msg("one", ts = T0), msg("two", ts = T0)), T0)
        repo.ingest(parsed(msg("one", ts = T0), msg("two", ts = T0)), T0 + 1_000)
        val messages = all()
        assertEquals(listOf("one", "two"), messages.map { it.text }.sorted())
        assertTrue(messages.all { it.revisionCount == 1 })
    }

    @Test
    fun genuinelyRepeatedText_keepsBothCopies_acrossReposts() = runBlocking {
        repo.ingest(parsed(msg("ok", ts = T0), msg("ok", ts = T0 + 2_000)), T0)
        assertEquals(IngestResult(0, 0, 2, 0), repo.ingest(parsed(msg("ok", ts = T0), msg("ok", ts = T0 + 2_000)), T0 + 5_000).counts())
        assertEquals(2, all().size)
    }

    @Test
    fun editStillDetected_whenOldTextNoLongerShown() = runBlocking {
        repo.ingest(parsed(msg("meet at 5", ts = T0), msg("bring snacks", sender = "Sam", ts = T0)), T0)
        val r = repo.ingest(parsed(msg("meet at 6", ts = T0), msg("bring snacks", sender = "Sam", ts = T0)), T0 + 1_000)
        assertEquals(IngestResult(0, 1, 1, 0), r.counts())
        assertEquals(2, all().single { it.sender == "Alice" }.revisionCount)
    }
}
