package com.riyadm.unsendvault.ui

import android.app.Application
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riyadm.unsendvault.ui.navigation.DeepLink
import com.riyadm.unsendvault.ui.navigation.DeepLinks
import com.riyadm.unsendvault.widget.WidgetIntents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class DeepLinksTest {
    private fun intent(destination: String?) = Intent().apply { destination?.let { putExtra(WidgetIntents.EXTRA_DESTINATION, it) } }

    @Test
    fun conversationLink_fromSavedMessageNotification() {
        val i = intent(WidgetIntents.DESTINATION_CONVERSATION).putExtra(WidgetIntents.EXTRA_CONVERSATION_ID, 42L)
        assertEquals(DeepLink.Conversation(42), DeepLinks.parse(i))
    }

    @Test
    fun badOrMissingIds_fallBackToHome() {
        assertEquals(DeepLink.Home, DeepLinks.parse(intent(WidgetIntents.DESTINATION_CONVERSATION)))
        assertEquals(DeepLink.Home, DeepLinks.parse(intent(WidgetIntents.DESTINATION_CONVERSATION).putExtra(WidgetIntents.EXTRA_CONVERSATION_ID, -3L)))
        assertEquals(DeepLink.Message(7), DeepLinks.parse(intent(WidgetIntents.DESTINATION_MESSAGE).putExtra(WidgetIntents.EXTRA_MESSAGE_ID, 7L)))
        assertNull(DeepLinks.parse(intent(null)))
        assertNull(DeepLinks.parse(null))
    }
}
