package com.riyadm.unsendvault.reply

/**
 * Sends a reply through the chat app's *own* notification reply action (the same RemoteInput an Android Auto / Wear OS
 * reply uses). UnsendVault never talks to any messaging server. A reply is possible only while that app's notification
 * for the chat is still showing: once it is dismissed or the chat is read, the app withdraws the action and there is
 * nothing to send through.
 */
interface ReplyGateway {
    suspend fun availability(packageName: String, conversationKey: String): ReplyAvailability

    /** Hands [text] to the app's reply action. [ReplyResult.SENT] means the app accepted the intent, not that it delivered. */
    suspend fun send(packageName: String, conversationKey: String, text: String): ReplyResult
}

enum class ReplyAvailability {
    /** The chat's notification is showing and offers a free-text reply action. */
    AVAILABLE,

    /** No showing notification for this chat offers a reply (dismissed, read, or the app has no reply action). */
    NO_NOTIFICATION,

    /** UnsendVault's listener isn't connected (capture paused or Notification Access off), so it can't see notifications. */
    LISTENER_OFF,
}

enum class ReplyResult { SENT, UNAVAILABLE, FAILED, EMPTY }
