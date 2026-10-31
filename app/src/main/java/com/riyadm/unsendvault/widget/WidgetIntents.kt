package com.riyadm.unsendvault.widget

import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import com.riyadm.unsendvault.MainActivity

/**
 * Contract between widgets and [MainActivity]. Every widget tap opens MainActivity (singleTask, so an existing task
 * receives it in onNewIntent) with these extras; the app lock still applies before anything is shown.
 */
object WidgetIntents {
    /** String. Which screen the tap asks for; one of the DESTINATION_* values. Always present. */
    const val EXTRA_DESTINATION = "com.riyadm.unsendvault.extra.WIDGET_DESTINATION"

    /** Long. Archived message id to open (with [DESTINATION_MESSAGE]). */
    const val EXTRA_MESSAGE_ID = "com.riyadm.unsendvault.extra.MESSAGE_ID"

    /** Long. Conversation id to open (with [DESTINATION_CONVERSATION]); used by saved-message notifications. */
    const val EXTRA_CONVERSATION_ID = "com.riyadm.unsendvault.extra.CONVERSATION_ID"

    /** String. Source-app package to filter the archive by (with [DESTINATION_APP]). */
    const val EXTRA_PACKAGE_NAME = "com.riyadm.unsendvault.extra.PACKAGE_NAME"

    const val DESTINATION_HOME = "home"
    const val DESTINATION_RECENT = "recent"
    const val DESTINATION_FAVORITES = "favorites"
    const val DESTINATION_APPS = "apps"
    const val DESTINATION_MESSAGE = "message"
    const val DESTINATION_APP = "app"
    const val DESTINATION_CONVERSATION = "conversation"
    const val DESTINATION_NOTIFICATION_ACCESS = "notification_access"

    val DestinationKey = ActionParameters.Key<String>(EXTRA_DESTINATION)
    val MessageIdKey = ActionParameters.Key<Long>(EXTRA_MESSAGE_ID)
    val PackageNameKey = ActionParameters.Key<String>(EXTRA_PACKAGE_NAME)

    fun openDestination(destination: String): Action =
        actionStartActivity<MainActivity>(actionParametersOf(DestinationKey to destination))

    fun openMessage(messageId: Long): Action =
        actionStartActivity<MainActivity>(actionParametersOf(DestinationKey to DESTINATION_MESSAGE, MessageIdKey to messageId))

    fun openApp(packageName: String): Action =
        actionStartActivity<MainActivity>(actionParametersOf(DestinationKey to DESTINATION_APP, PackageNameKey to packageName))
}
