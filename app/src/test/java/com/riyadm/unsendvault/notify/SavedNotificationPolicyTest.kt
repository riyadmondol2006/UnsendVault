package com.riyadm.unsendvault.notify

import com.riyadm.unsendvault.capture.CapturedMessage
import com.riyadm.unsendvault.capture.IngestResult
import com.riyadm.unsendvault.capture.ParsedNotification
import com.riyadm.unsendvault.core.model.ContentKind
import com.riyadm.unsendvault.core.model.VaultSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedNotificationPolicyTest {
    private val now = 1_700_000_000_000L
    private val policy = SavedNotificationPolicy(selfSender = "You")
    private val on = VaultSettings(savedMessageNotifications = true)

    private fun msg(text: String, sender: String? = "Alex", ts: Long = now - 1_000, key: String = "alex", group: Boolean = false) =
        CapturedMessage("com.whatsapp", key, if (group) "Team" else "Alex", group, sender, text, ContentKind.TEXT, ts, true)

    private fun parsed(vararg m: CapturedMessage, postTime: Long = now - 500) =
        ParsedNotification("com.whatsapp", "WhatsApp", "k", postTime, "whatsapp", m.toList())

    private fun result(vararg refs: Pair<Long, CapturedMessage>, deletedIn: Set<Long> = emptySet()) =
        IngestResult(refs.size, 0, 0, deletedIn.size, refs.map { IngestResult.SavedRef(it.first, it.second) }, deletedIn)

    @Test
    fun offByDefault_andWhilePaused() {
        val m = msg("hi")
        assertTrue(policy.updates(VaultSettings(), parsed(m), result(1L to m), now).isEmpty())
        assertTrue(policy.updates(on.copy(captureEnabled = false), parsed(m), result(1L to m), now).isEmpty())
    }

    @Test
    fun liveMessage_oneUpdatePerChat() {
        val a = msg("hi")
        val b = msg("there", ts = now - 500)
        val t = msg("lunch?", sender = "Sam", key = "team", group = true)
        val updates = policy.updates(on, parsed(a, b, t), result(1L to b, 1L to a, 2L to t), now)
        assertEquals(listOf(1L, 2L), updates.map { it.conversationId })
        val alex = updates[0]
        assertEquals(2, alex.newMessages)
        assertEquals(listOf("hi", "there"), alex.lines) // oldest first
        assertEquals("Alex", alex.conversationTitle)
        assertEquals("WhatsApp", alex.appLabel)
        assertEquals(listOf("Sam: lunch?"), updates[1].lines) // groups name the sender
    }

    @Test
    fun catchUpAfterReboot_isSilent() {
        val m = msg("old", ts = now - 60 * 60_000L)
        val p = parsed(m, postTime = now - 60 * 60_000L)
        assertTrue(policy.updates(on, p, result(1L to m), now).isEmpty())
    }

    @Test
    fun oldHistoryInARepost_isSkipped_newMessageKept() {
        val old = msg("from yesterday", ts = now - 24 * 60 * 60_000L)
        val fresh = msg("new one")
        val updates = policy.updates(on, parsed(old, fresh), result(1L to old, 1L to fresh), now)
        assertEquals(listOf("new one"), updates.single().lines)
    }

    @Test
    fun ownMessages_neverNotify() {
        val mine = msg("sent from here", sender = "You")
        assertTrue(policy.updates(on, parsed(mine), result(1L to mine), now).isEmpty())
    }

    @Test
    fun possiblyDeleted_isReportedEvenWithoutNewMessages() {
        val update = policy.updates(on, parsed(msg("x")), result(deletedIn = setOf(5L)), now).single()
        assertEquals(5L, update.conversationId)
        assertEquals(0, update.newMessages)
        assertEquals(1, update.possiblyDeleted)
    }

    @Test
    fun state_accumulatesUntilDismissed_andCapsLines() {
        val state = SavedNotificationState(maxLines = 3)
        fun u(vararg lines: String) = SavedNotificationPolicy.Update(1, "p", "App", "Alex", false, lines.toList(), lines.size, 0, now)
        state.merge(u("a", "b"), stillShowing = false)
        val second = state.merge(u("c", "d"), stillShowing = true)
        assertEquals(4, second.totalNew)
        assertEquals(listOf("b", "c", "d"), second.lines)
        val afterDismiss = state.merge(u("e"), stillShowing = false)
        assertEquals(1, afterDismiss.totalNew)
        assertEquals(listOf("e"), afterDismiss.lines)
        state.remove(1)
        assertEquals(0, state.activeCount())
    }
}
