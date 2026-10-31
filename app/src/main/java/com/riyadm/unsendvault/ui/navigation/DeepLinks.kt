package com.riyadm.unsendvault.ui.navigation

import android.content.Intent
import com.riyadm.unsendvault.widget.WidgetIntents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A navigation request carried by a widget or saved-message notification tap. Consumed once, only after the lock gate has opened. */
sealed interface DeepLink {
    data object Home : DeepLink
    data object Favorites : DeepLink
    data object NotificationAccess : DeepLink
    data class Message(val id: Long) : DeepLink
    data class Conversation(val id: Long) : DeepLink
}

/** Holds the latest unconsumed [DeepLink]. Written by MainActivity (onCreate/onNewIntent), read by the main scaffold. */
object DeepLinks {
    private val pending = MutableStateFlow<DeepLink?>(null)
    val requests: StateFlow<DeepLink?> = pending

    fun offer(intent: Intent?) {
        parse(intent)?.let { pending.value = it }
    }

    fun consume(link: DeepLink) {
        pending.compareAndSet(link, null)
    }

    /** Extras may come from any app (MainActivity is exported); they only ever select an in-app screen. */
    fun parse(intent: Intent?): DeepLink? {
        val destination = intent?.getStringExtra(WidgetIntents.EXTRA_DESTINATION) ?: return null
        return when (destination) {
            WidgetIntents.DESTINATION_FAVORITES -> DeepLink.Favorites
            WidgetIntents.DESTINATION_NOTIFICATION_ACCESS -> DeepLink.NotificationAccess
            WidgetIntents.DESTINATION_MESSAGE ->
                intent.getLongExtra(WidgetIntents.EXTRA_MESSAGE_ID, -1L).takeIf { it > 0 }?.let(DeepLink::Message) ?: DeepLink.Home
            WidgetIntents.DESTINATION_CONVERSATION ->
                intent.getLongExtra(WidgetIntents.EXTRA_CONVERSATION_ID, -1L).takeIf { it > 0 }?.let(DeepLink::Conversation) ?: DeepLink.Home
            else -> DeepLink.Home
        }
    }
}
