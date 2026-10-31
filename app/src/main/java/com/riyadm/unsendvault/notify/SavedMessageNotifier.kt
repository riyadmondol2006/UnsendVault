package com.riyadm.unsendvault.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.riyadm.unsendvault.MainActivity
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.capture.CaptureLog
import com.riyadm.unsendvault.capture.IngestResult
import com.riyadm.unsendvault.capture.ParsedNotification
import com.riyadm.unsendvault.core.model.VaultSettings
import com.riyadm.unsendvault.widget.WidgetIntents

/**
 * UnsendVault's own, opt-in "messages saved" notifications: one per chat (tag [TAG], id derived from the conversation
 * id), grouped under a summary, silent ([NotificationManager.IMPORTANCE_LOW]: the chat app already alerted). Tapping
 * opens that chat behind the app lock. Content (chat name, text) appears only when the user allowed it and the app lock
 * is off; the lock-screen version never shows it. Posting is a local call from the capture worker: no wake lock, no
 * alarm, no extra work when the feature is off.
 */
class SavedMessageNotifier(
    context: Context,
    private val policy: SavedNotificationPolicy,
) {
    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)
    private val state = SavedNotificationState()

    @Volatile
    private var visibleConversationId: Long? = null

    fun onIngested(settings: VaultSettings, parsed: ParsedNotification, result: IngestResult, capturedAt: Long) {
        if (!settings.savedMessageNotifications) return
        val updates = policy.updates(settings, parsed, result, capturedAt)
            .filter { it.conversationId != visibleConversationId }
        if (updates.isEmpty() || !canPost()) return
        try {
            ensureChannel()
            val showing = activeConversationIds()
            val showContent = settings.notificationShowContent && !settings.appLockEnabled
            for (update in updates) {
                val entry = state.merge(update, stillShowing = update.conversationId in showing)
                manager.notify(TAG, notificationId(update.conversationId), build(entry, showContent))
            }
            manager.notify(TAG, SUMMARY_ID, buildSummary())
        } catch (e: SecurityException) {
            CaptureLog.d("saved notification refused: ${e.javaClass.simpleName}")
        } catch (e: RuntimeException) {
            CaptureLog.d("saved notification failed: ${e.javaClass.simpleName}")
        }
    }

    /** The chat is on screen: clear its notification and don't post new ones for it until it is left. */
    fun onConversationVisible(conversationId: Long) {
        visibleConversationId = conversationId
        cancel(conversationId)
    }

    fun onConversationHidden(conversationId: Long) {
        if (visibleConversationId == conversationId) visibleConversationId = null
    }

    fun cancel(conversationId: Long) {
        state.remove(conversationId)
        try {
            manager.cancel(TAG, notificationId(conversationId))
            if (state.activeCount() == 0) manager.cancel(TAG, SUMMARY_ID)
        } catch (e: RuntimeException) {
            // Nothing to cancel.
        }
    }

    /** Removes every saved-message notification (feature turned off, content hidden, app lock turned on, data wiped). */
    fun cancelAll() {
        state.clear()
        try {
            manager.activeNotifications.filter { it.tag == TAG }.forEach { manager.cancel(TAG, it.id) }
        } catch (e: RuntimeException) {
            // Nothing to cancel.
        }
    }

    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return manager.areNotificationsEnabled()
    }

    private fun activeConversationIds(): Set<Int> = try {
        manager.activeNotifications.filter { it.tag == TAG && it.id != SUMMARY_ID }.mapTo(HashSet()) { it.id }
    } catch (e: RuntimeException) {
        emptySet()
    }

    private operator fun Set<Int>.contains(conversationId: Long) = contains(notificationId(conversationId))

    private fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_saved), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.notif_channel_saved_description)
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    private fun build(entry: SavedNotificationState.Entry, showContent: Boolean): android.app.Notification {
        val u = entry.update
        val res = context.resources
        val countText = when {
            entry.totalNew > 0 && entry.totalDeleted > 0 -> res.getString(
                R.string.notif_saved_and_deleted,
                res.getQuantityString(R.plurals.notif_saved_count, entry.totalNew, entry.totalNew),
                res.getQuantityString(R.plurals.notif_deleted_count, entry.totalDeleted, entry.totalDeleted),
            )
            entry.totalNew > 0 -> res.getQuantityString(R.plurals.notif_saved_count, entry.totalNew, entry.totalNew)
            else -> res.getQuantityString(R.plurals.notif_deleted_count, entry.totalDeleted, entry.totalDeleted)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.widget_ic_vault)
            .setGroup(GROUP)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setWhen(u.latestAt)
            .setShowWhen(true)
            .setNumber(entry.totalNew)
            .setContentIntent(openConversation(u.conversationId))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion())
        if (showContent) {
            builder.setContentTitle(u.conversationTitle)
                .setSubText(u.appLabel)
                .setContentText(if (entry.totalNew == 1 && entry.totalDeleted == 0) entry.lines.lastOrNull() ?: countText else countText)
            if (entry.lines.isNotEmpty()) {
                val style = NotificationCompat.InboxStyle().setSummaryText(countText)
                entry.lines.forEach { style.addLine(it) }
                builder.setStyle(style)
            }
        } else {
            builder.setContentTitle(res.getString(R.string.notif_saved_title_private, u.appLabel))
                .setContentText(countText)
        }
        return builder.build()
    }

    private fun buildSummary(): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.widget_ic_vault)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setSilent(true)
            .setContentTitle(context.getString(R.string.notif_public_title))
            .setContentIntent(openHome())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion())
            .build()

    private fun publicVersion(): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.widget_ic_vault)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_public_title))
            .build()

    private fun openConversation(conversationId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_CONVERSATION + conversationId) // distinct per chat so PendingIntents never merge
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(WidgetIntents.EXTRA_DESTINATION, WidgetIntents.DESTINATION_CONVERSATION)
            .putExtra(WidgetIntents.EXTRA_CONVERSATION_ID, conversationId)
        return PendingIntent.getActivity(
            context,
            notificationId(conversationId),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun openHome(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(WidgetIntents.EXTRA_DESTINATION, WidgetIntents.DESTINATION_HOME)
        return PendingIntent.getActivity(context, SUMMARY_ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CHANNEL_ID = "saved_messages"
        const val TAG = "saved_conversation"
        private const val GROUP = "com.riyadm.unsendvault.SAVED"
        private const val SUMMARY_ID = 0
        private const val ACTION_OPEN_CONVERSATION = "com.riyadm.unsendvault.action.OPEN_CONVERSATION."

        /** Never 0 (the summary). Conversation ids are positive Room row ids. */
        fun notificationId(conversationId: Long): Int = ((conversationId % Int.MAX_VALUE).toInt()).let { if (it == 0) Int.MAX_VALUE else it }
    }
}
