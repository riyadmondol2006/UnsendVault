package com.riyadm.unsendvault.capture

import android.app.Application
import android.app.Notification
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.os.Process
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riyadm.unsendvault.capture.adapter.AdapterRegistry
import com.riyadm.unsendvault.capture.parser.ParseOutcome
import com.riyadm.unsendvault.core.model.ContentKind
import com.riyadm.unsendvault.core.model.RemovalReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

private const val T0 = 1_700_000_000_000L
private const val PKG = "com.example.chat"

/**
 * Builds real Notification objects (platform + AndroidX styles) and checks what [SnapshotFactory] copies out of them.
 * Runs on API 26 (Bundle-based Person, pre-P MessagingStyle) and API 34 (android.app.Person). API 36 is skipped:
 * Robolectric 4.17 cannot initialise its SDK 36 sandbox on JDK 25 (FileDescriptor internals).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26, 34], application = Application::class)
class SnapshotFactoryRobolectricTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun builder() = NotificationCompat.Builder(context, "chat")
        .setSmallIcon(android.R.drawable.ic_dialog_info)

    private fun snap(n: Notification, postTime: Long = T0 + 10_000) =
        SnapshotFactory.fromNotification(PKG, "0|$PKG|1|null|10001", 1, null, postTime, n)

    @Test
    fun messagingStyleGroupWithSelfHistoricAndMedia() {
        val me = Person.Builder().setName("Jordan").build()
        val alex = Person.Builder().setName("Alex Rivera").build()
        val photo = NotificationCompat.MessagingStyle.Message("", T0 + 2, alex)
            .setData("image/jpeg", Uri.parse("content://com.example.chat/media/1"))
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle("Project Team")
            .setGroupConversation(true)
            .addHistoricMessage(NotificationCompat.MessagingStyle.Message("Earlier", T0 - 100, alex))
            .addMessage("Standup in 5", T0, alex)
            .addMessage("On my way", T0 + 1, null as Person?)
            .addMessage(photo)
        val n = builder().setStyle(style).setCategory(NotificationCompat.CATEGORY_MESSAGE).setShortcutId("conv-1").build()

        val s = snap(n)!!
        assertTrue(s.template!!.contains("MessagingStyle"))
        assertEquals("Project Team", s.conversationTitle)
        assertTrue(s.isGroupConversation)
        assertEquals("Jordan", s.selfName)
        assertEquals("msg", s.category)
        assertEquals("chat", s.channelId)
        assertEquals("conv-1", s.shortcutId)
        assertEquals(listOf("Standup in 5", "On my way", ""), s.messages.map { it.text.orEmpty() })
        assertEquals(listOf("Alex Rivera", null, "Alex Rivera"), s.messages.map { it.sender })
        assertEquals(listOf(T0, T0 + 1, T0 + 2), s.messages.map { it.timestamp })
        assertEquals("image/jpeg", s.messages[2].dataMimeType)
        assertEquals("content://com.example.chat/media/1", s.messages[2].dataUri)
        assertNull(s.messages[0].dataUri)
        assertEquals(listOf("Earlier"), s.historicMessages.map { it.text })
        assertFalse(s.looksSystemRedacted)

        // End to end through the generic parser.
        val parsed = AdapterRegistry.createDefault().parse(s).outcome as ParseOutcome.Parsed
        assertEquals(listOf("Earlier", "Standup in 5", "On my way", "📷 Photo"), parsed.messages.map { it.text })
        assertEquals(listOf("Alex Rivera", "Alex Rivera", "You", "Alex Rivera"), parsed.messages.map { it.sender })
        assertEquals(ContentKind.MEDIA_PLACEHOLDER, parsed.messages.last().contentKind)
        assertEquals("content://com.example.chat/media/1", parsed.messages.last().mediaUri)
        assertEquals("image/jpeg", parsed.messages.last().mediaMimeType)
        assertTrue(parsed.messages.dropLast(1).all { it.mediaUri == null })
        assertTrue(parsed.messages.all { it.conversationKey == "shortcut:conv-1" && it.isGroup })
    }

    @Test
    fun oneToOneMessagingStyle() {
        val me = Person.Builder().setName("Jordan").build()
        val alex = Person.Builder().setName("Alex Rivera").build()
        val n = builder()
            .setContentTitle("Alex Rivera")
            .setStyle(NotificationCompat.MessagingStyle(me).addMessage("Hey", T0, alex))
            .build()
        val s = snap(n)!!
        assertFalse(s.isGroupConversation)
        assertEquals("Hey", s.messages.single().text)
        assertEquals("Alex Rivera", s.messages.single().sender)
    }

    @Test
    fun plainBigTextAndInboxStyles() {
        val plain = snap(builder().setContentTitle("Alex Rivera").setContentText("hi").setWhen(T0).build())!!
        assertEquals("Alex Rivera", plain.title)
        assertEquals("hi", plain.text)
        assertEquals(T0, plain.whenTime)
        assertTrue(plain.messages.isEmpty())

        val big = snap(
            builder().setContentTitle("Alex Rivera").setContentText("short…")
                .setStyle(NotificationCompat.BigTextStyle().bigText("The full, long message body")).build(),
        )!!
        assertEquals("The full, long message body", big.bigText)
        assertTrue(big.template!!.contains("BigTextStyle"))

        val inbox = snap(
            builder().setContentTitle("Project Team")
                .setStyle(NotificationCompat.InboxStyle().addLine("Alex Rivera: one").addLine("Sam Lee: two").setSummaryText("2 new"))
                .build(),
        )!!
        assertEquals(listOf("Alex Rivera: one", "Sam Lee: two"), inbox.textLines)
        assertEquals("2 new", inbox.summaryText)
    }

    @Test
    fun cheapRejectsAndFlags() {
        assertNull(snap(builder().setContentTitle("Music").setOngoing(true).build()))
        val summary = snap(builder().setContentTitle("Chat").setGroup("g").setGroupSummary(true).build())!!
        assertTrue(summary.isGroupSummary)
        assertEquals("g", summary.groupKey)
    }

    @Test
    fun fromStatusBarNotificationSkipsOwnPackage() {
        val n = builder().setContentTitle("Alex Rivera").setContentText("hi").build()

        @Suppress("DEPRECATION")
        val sbn = StatusBarNotification(PKG, PKG, 7, "tag", 10001, 0, 0, n, Process.myUserHandle(), T0)
        val s = SnapshotFactory.fromStatusBarNotification(sbn, ownPackage = "com.riyadm.unsendvault")!!
        assertEquals(PKG, s.packageName)
        assertEquals(7, s.id)
        assertEquals("tag", s.tag)
        assertEquals(T0, s.postTime)
        assertNotNull(s.key)
        assertNull(SnapshotFactory.fromStatusBarNotification(sbn, ownPackage = PKG))
    }

    @Test
    fun malformedMessageBundlesNeverThrow() {
        val wrongTypes = Bundle().apply {
            putInt("text", 5)
            putString("time", "not a number")
            putInt("sender", 3)
        }
        val compatPerson = Bundle().apply {
            putCharSequence("text", "hello")
            putLong("time", T0)
            putBundle("person", Bundle().apply { putCharSequence("name", "Alex Rivera") })
        }
        val noText = Bundle().apply { putLong("time", T0) }
        val out = SnapshotFactory.readMessages(arrayOf<Parcelable>(wrongTypes, compatPerson, noText))
        assertEquals(2, out.size)
        assertNull(out[0].text)
        assertEquals(0L, out[0].timestamp)
        assertEquals("Alex Rivera", out[1].sender)
        assertTrue(SnapshotFactory.readMessages("garbage").isEmpty())
        assertTrue(SnapshotFactory.readMessages(null).isEmpty())
    }

    @Test
    fun platformPersonSender() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        val b = Bundle().apply {
            putCharSequence("text", "hi")
            putLong("time", T0)
            putParcelable("sender_person", android.app.Person.Builder().setName("Alex Rivera").build())
        }
        assertEquals("Alex Rivera", SnapshotFactory.messageFromBundle(b)!!.sender)
    }

    @Test
    fun systemRedactedTextIsFlagged() {
        val s = snap(builder().setContentTitle("Bank").setContentText("Sensitive notification content hidden").build())!!
        assertTrue(s.looksSystemRedacted)
    }

    @Test
    fun removalReasonMapping() {
        assertEquals(RemovalReason.CLICKED, RemovalReasons.fromListenerReason(NotificationListenerService.REASON_CLICK))
        assertEquals(RemovalReason.USER_DISMISSED, RemovalReasons.fromListenerReason(NotificationListenerService.REASON_CANCEL))
        assertEquals(RemovalReason.USER_DISMISSED, RemovalReasons.fromListenerReason(NotificationListenerService.REASON_CANCEL_ALL))
        assertEquals(RemovalReason.APP_CANCELLED, RemovalReasons.fromListenerReason(NotificationListenerService.REASON_APP_CANCEL))
        assertEquals(RemovalReason.LISTENER_CANCELLED, RemovalReasons.fromListenerReason(NotificationListenerService.REASON_LISTENER_CANCEL))
        assertEquals(
            RemovalReason.GROUP_SUMMARY_CANCELED,
            RemovalReasons.fromListenerReason(NotificationListenerService.REASON_GROUP_SUMMARY_CANCELED),
        )
        assertEquals(RemovalReason.TIMEOUT, RemovalReasons.fromListenerReason(NotificationListenerService.REASON_TIMEOUT))
        assertEquals(RemovalReason.CHANNEL_DISABLED, RemovalReasons.fromListenerReason(NotificationListenerService.REASON_CHANNEL_BANNED))
        assertEquals(RemovalReason.PACKAGE_CHANGED, RemovalReasons.fromListenerReason(NotificationListenerService.REASON_PACKAGE_CHANGED))
        assertEquals(RemovalReason.USER_STOPPED, RemovalReasons.fromListenerReason(NotificationListenerService.REASON_USER_STOPPED))
        assertEquals(RemovalReason.OTHER, RemovalReasons.fromListenerReason(NotificationListenerService.REASON_SNOOZED))
        assertEquals(RemovalReason.UNKNOWN, RemovalReasons.fromListenerReason(9_999))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [26, 34], application = Application::class)
class NotificationAccessRobolectricTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun settingsIntentCandidatesPerApiLevel() {
        val intents = NotificationAccess.settingsIntents(context)
        val flattened = NotificationAccess.component(context).flattenToString()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, intents.first().action)
            assertEquals(flattened, intents.first().getStringExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME))
            assertEquals(3, intents.size)
        } else {
            assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, intents.first().action)
            assertEquals(2, intents.size)
        }
        val list = intents.first { it.action == Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS }
        assertEquals(flattened, list.getStringExtra(":settings:fragment_args_key"))
        assertEquals(flattened, list.getBundleExtra(":settings:show_fragment_args")!!.getString(":settings:fragment_args_key"))
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intents.last().action)
        assertEquals("package:${context.packageName}", intents.last().dataString)
        assertNotNull(AndroidListenerStatus(context).accessSettingsIntent())
    }

    @Test
    fun accessGrantedFollowsEnabledListeners() {
        val status = AndroidListenerStatus(context)
        assertFalse(status.isAccessGranted())
        status.requestRebind() // no access: silent no-op
        Settings.Secure.putString(
            context.contentResolver,
            "enabled_notification_listeners",
            NotificationAccess.component(context).flattenToString(),
        )
        assertTrue(status.isAccessGranted())
    }

    @Test
    fun listenerLifecycleUpdatesConnectedState() {
        val service = Robolectric.buildService(VaultNotificationListener::class.java).create().get()
        service.onListenerConnected()
        assertTrue(ListenerConnectionState.connected.value)
        // Engine unavailable under a plain Application: posting must still be harmless.
        val n = NotificationCompat.Builder(context, "chat").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Alex Rivera").setContentText("hi").build()

        @Suppress("DEPRECATION")
        val sbn = StatusBarNotification(PKG, PKG, 1, null, 10001, 0, 0, n, Process.myUserHandle(), T0)
        service.onNotificationPosted(sbn)
        service.onNotificationRemoved(sbn, null, NotificationListenerService.REASON_CANCEL)
        service.onListenerDisconnected()
        assertFalse(ListenerConnectionState.connected.value)
    }
}
