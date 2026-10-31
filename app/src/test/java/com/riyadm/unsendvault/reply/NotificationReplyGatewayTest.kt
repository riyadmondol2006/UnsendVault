package com.riyadm.unsendvault.reply

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riyadm.unsendvault.capture.SnapshotFactory
import com.riyadm.unsendvault.capture.adapter.AdapterRegistry
import com.riyadm.unsendvault.capture.parser.CapturePlaceholders
import com.riyadm.unsendvault.capture.parser.ParseOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import androidx.core.app.RemoteInput as CompatRemoteInput

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class NotificationReplyGatewayTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val registry = AdapterRegistry.createDefault(CapturePlaceholders())
    private var nextId = 1

    private fun replyIntent(code: Int) = PendingIntent.getBroadcast(
        context, code, Intent(ACTION_REPLY).setPackage(context.packageName).putExtra(EXTRA_CODE, code), PendingIntent.FLAG_MUTABLE,
    )

    private fun replyAction(code: Int, semantic: Boolean = true) =
        NotificationCompat.Action.Builder(0, "Reply", replyIntent(code))
            .addRemoteInput(CompatRemoteInput.Builder(RESULT_KEY).setLabel("Reply").build())
            .apply { if (semantic) setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY) }
            .build()

    private fun chat(name: String, text: String, vararg actions: NotificationCompat.Action, summary: Boolean = false): Notification {
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Me").build())
            .addMessage(text, T0, Person.Builder().setName(name).build())
        return NotificationCompat.Builder(context, "chat")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle(name)
            .setContentText(text)
            .setStyle(style)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setGroup("chats")
            .setGroupSummary(summary)
            .apply { actions.forEach { addAction(it) } }
            .build()
    }

    @Suppress("DEPRECATION")
    private fun sbn(n: Notification, postTime: Long = T0) =
        StatusBarNotification(PKG, PKG, nextId++, null, 10001, 0, 0, n, Process.myUserHandle(), postTime)

    private fun keyOf(s: StatusBarNotification): String {
        val parsed = registry.parse(SnapshotFactory.fromStatusBarNotification(s, context.packageName)!!).outcome as ParseOutcome.Parsed
        return parsed.messages.map { it.conversationKey }.distinct().single()
    }

    private fun gateway(active: List<StatusBarNotification>?) = NotificationReplyGateway(context, { registry }, { active })

    @Test
    fun replyAction_prefersSemanticReply_andNeedsFreeFormInput() {
        val markRead = NotificationCompat.Action.Builder(0, "Mark read", replyIntent(9)).build()
        val plain = replyAction(1, semantic = false)
        val semantic = replyAction(2, semantic = true)
        val n = chat("Alex", "hi", markRead, plain, semantic)
        assertEquals(RESULT_KEY, ReplyActions.replyAction(n)!!.remoteInputs.single().resultKey)
        assertEquals(n.actions[2].actionIntent, ReplyActions.replyAction(n)!!.actionIntent)
        assertNull(ReplyActions.replyAction(chat("Alex", "hi", markRead)))
        assertNull(ReplyActions.replyAction(null))
    }

    @Test
    fun availability_matchesOnlyThisChat() = runBlocking {
        val alex = sbn(chat("Alex", "hi", replyAction(1)))
        val sam = sbn(chat("Sam", "yo", replyAction(2)))
        val g = gateway(listOf(alex, sam))
        assertEquals(ReplyAvailability.AVAILABLE, g.availability(PKG, keyOf(alex)))
        assertEquals(ReplyAvailability.AVAILABLE, g.availability(PKG, keyOf(sam)))
        assertEquals(ReplyAvailability.NO_NOTIFICATION, g.availability(PKG, "someone-else"))
    }

    @Test
    fun noReplyAction_orGroupSummary_orListenerOff_isUnavailable() = runBlocking {
        val noAction = sbn(chat("Alex", "hi"))
        val summaryOnly = sbn(chat("Alex", "hi", replyAction(1), summary = true))
        assertEquals(ReplyAvailability.NO_NOTIFICATION, gateway(listOf(noAction)).availability(PKG, keyOf(noAction)))
        assertEquals(ReplyAvailability.NO_NOTIFICATION, gateway(listOf(summaryOnly)).availability(PKG, keyOf(noAction)))
        assertEquals(ReplyAvailability.LISTENER_OFF, gateway(null).availability(PKG, keyOf(noAction)))
    }

    @Test
    fun send_firesTheChatsOwnReplyAction_withTheText() = runBlocking {
        val alex = sbn(chat("Alex", "hi", replyAction(1)))
        val sam = sbn(chat("Sam", "yo", replyAction(2)))
        val g = gateway(listOf(sam, alex))
        assertEquals(ReplyResult.EMPTY, g.send(PKG, keyOf(alex), "   "))
        assertEquals(ReplyResult.UNAVAILABLE, g.send(PKG, "someone-else", "hello"))
        assertEquals(ReplyResult.SENT, g.send(PKG, keyOf(alex), "  on my way  "))

        val sent = shadowOf(context as Application).broadcastIntents.single { it.action == ACTION_REPLY }
        val results = RemoteInput.getResultsFromIntent(sent)
        assertNotNull(results)
        assertEquals("on my way", results!!.getCharSequence(RESULT_KEY).toString())
        assertEquals(RemoteInput.SOURCE_FREE_FORM_INPUT, RemoteInput.getResultsSource(sent))
        assertEquals(1, sent.getIntExtra(EXTRA_CODE, -1)) // Alex's action, not Sam's (2)
    }

    companion object {
        private const val PKG = "com.whatsapp"
        private const val ACTION_REPLY = "test.REPLY"
        private const val RESULT_KEY = "reply_text"
        private const val EXTRA_CODE = "code"
        private const val T0 = 1_700_000_000_000L
    }
}
