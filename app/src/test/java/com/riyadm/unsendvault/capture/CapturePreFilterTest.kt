package com.riyadm.unsendvault.capture

import android.app.Notification
import com.riyadm.unsendvault.capture.CapturePreFilter.Result
import com.riyadm.unsendvault.capture.Fixtures.MESSAGING_TEMPLATE
import com.riyadm.unsendvault.capture.Fixtures.T0
import com.riyadm.unsendvault.capture.Fixtures.msg
import com.riyadm.unsendvault.capture.Fixtures.snapshot
import com.riyadm.unsendvault.capture.adapter.AdapterRegistry
import com.riyadm.unsendvault.capture.adapter.AppLabelResolver
import com.riyadm.unsendvault.capture.adapter.KnownApps
import com.riyadm.unsendvault.capture.engine.CaptureSink
import com.riyadm.unsendvault.capture.engine.DefaultCaptureEngine
import com.riyadm.unsendvault.capture.filter.SensitiveContentFilter
import com.riyadm.unsendvault.core.model.AppsToSave
import com.riyadm.unsendvault.core.model.VaultSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePreFilterTest {
    private val own = "com.riyadm.unsendvault"
    private val registry = AdapterRegistry.createDefault()
    private val filter = CapturePreFilter(registry, own)
    private val defaults = VaultSettings()

    private fun decide(
        pkg: String = "com.example.chat",
        flags: Int = 0,
        category: String? = null,
        channelId: String? = null,
        template: String? = null,
        hasMessages: Boolean = false,
        hasHistoric: Boolean = false,
        settings: VaultSettings? = defaults,
    ) = filter.decide(pkg, flags, category, channelId, template, hasMessages, hasHistoric, settings)

    @Test
    fun headerRejects() {
        assertEquals(Result.OWN_PACKAGE, decide(pkg = own, template = MESSAGING_TEMPLATE))
        assertEquals(Result.ONGOING, decide(flags = Notification.FLAG_ONGOING_EVENT, template = MESSAGING_TEMPLATE))
        assertEquals(Result.ONGOING, decide(flags = Notification.FLAG_FOREGROUND_SERVICE, template = MESSAGING_TEMPLATE))
        assertEquals(Result.NON_MESSAGE_CATEGORY, decide(category = "call", template = MESSAGING_TEMPLATE))
        assertEquals(Result.NON_MESSAGE_CATEGORY, decide(category = "missed_call"))
        assertEquals(Result.NON_MESSAGE_CATEGORY, decide(category = "transport"))
        assertEquals(Result.NON_MESSAGE_CATEGORY, decide(category = "progress", pkg = KnownApps.WHATSAPP))
    }

    @Test
    fun settingsRejectsOnlyWhenSettingsAreLoaded() {
        val paused = VaultSettings(captureEnabled = false)
        val excluded = VaultSettings(excludedPackages = setOf(KnownApps.WHATSAPP))
        assertEquals(Result.PAUSED, decide(pkg = KnownApps.WHATSAPP, settings = paused))
        assertEquals(Result.EXCLUDED, decide(pkg = KnownApps.WHATSAPP, settings = excluded))
        assertTrue(Result.PAUSED.isSettingsReject && Result.EXCLUDED.isSettingsReject && Result.OWN_PACKAGE.isSettingsReject)

        // Cold start: unknown settings never cause a settings-based reject (the consumer decides with real settings).
        assertEquals(Result.ACCEPT, decide(pkg = KnownApps.WHATSAPP, settings = null))
        assertEquals(Result.ACCEPT, decide(pkg = "com.example.shop", settings = null)) // messages-only gate skipped too
    }

    @Test
    fun groupSummaries() {
        val summary = Notification.FLAG_GROUP_SUMMARY
        assertEquals(Result.GROUP_SUMMARY_WITHOUT_MESSAGES, decide(pkg = KnownApps.WHATSAPP, flags = summary))
        // Apps that only post a MessagingStyle summary keep working.
        assertEquals(Result.ACCEPT, decide(pkg = KnownApps.WHATSAPP, flags = summary, template = MESSAGING_TEMPLATE, hasMessages = true))
        assertEquals(Result.ACCEPT, decide(pkg = KnownApps.WHATSAPP, flags = summary, hasHistoric = true))
    }

    @Test
    fun messagesOnlyGate() {
        // Unknown app, plain notification → dropped before any snapshot.
        assertEquals(Result.NOT_A_MESSAGE, decide(pkg = "com.example.shop"))
        assertEquals(Result.ACCEPT, decide(pkg = "com.example.shop", settings = VaultSettings(messagesOnly = false)))
        assertEquals(Result.ACCEPT, decide(pkg = "com.example.chat", template = MESSAGING_TEMPLATE))
        assertEquals(Result.ACCEPT, decide(pkg = "com.example.chat", template = "androidx.core.app.NotificationCompat\$MessagingStyle"))
        assertEquals(Result.ACCEPT, decide(pkg = "com.example.chat", hasMessages = true))
        assertEquals(Result.ACCEPT, decide(pkg = "com.example.chat", category = "msg"))
        // Dedicated messenger claims flat notifications; mixed apps don't, except Instagram's direct channel.
        assertEquals(Result.ACCEPT, decide(pkg = KnownApps.WHATSAPP))
        assertEquals(Result.NOT_A_MESSAGE, decide(pkg = KnownApps.INSTAGRAM))
        assertEquals(Result.ACCEPT, decide(pkg = KnownApps.INSTAGRAM, channelId = "ig_direct"))
        assertEquals(Result.NOT_A_MESSAGE, decide(pkg = KnownApps.SLACK, template = "android.app.Notification\$BigTextStyle"))
    }

    /** Never rejects what the engine would capture: checked over a matrix of snapshots × settings. */
    @Test
    fun preFilterIsConsistentWithTheEngine() {
        val engine = DefaultCaptureEngine(
            scope = CoroutineScope(Job() + Dispatchers.Unconfined),
            dispatcher = Dispatchers.Unconfined,
            settingsProvider = { defaults },
            sink = object : CaptureSink {
                override suspend fun ingest(parsed: com.riyadm.unsendvault.capture.ParsedNotification, capturedAt: Long) = Unit
                override suspend fun recordRemoval(event: RemovalEvent) = Unit
            },
            registry = registry,
            labels = AppLabelResolver { null },
            sensitiveFilter = SensitiveContentFilter(),
            ownPackage = own,
        )
        val packages = listOf(own, KnownApps.WHATSAPP, KnownApps.INSTAGRAM, KnownApps.FACEBOOK, KnownApps.SLACK, "com.example.shop")
        val shapes = packages.flatMap { pkg ->
            listOf(
                snapshot(packageName = pkg, title = "Alex Rivera", text = "hi there"),
                snapshot(packageName = pkg, title = "Alex Rivera", messages = listOf(msg("Alex Rivera", "hello", T0))),
                snapshot(packageName = pkg, title = "Alex Rivera", text = "hi", category = "msg"),
                snapshot(packageName = pkg, title = "Alex Rivera", text = "hi", channelId = "ig_direct"),
                snapshot(packageName = pkg, title = "Alex Rivera", text = "hi", category = "call"),
                snapshot(packageName = pkg, title = "Alex Rivera", text = "hi", category = "progress"),
                snapshot(packageName = pkg, title = "App", text = "3 new messages", isGroupSummary = true),
                snapshot(
                    packageName = pkg, title = "Group", isGroupSummary = true,
                    messages = listOf(msg("Sam Lee", "summary-only app", T0)),
                ),
            )
        }
        val settingsList = listOf(
            VaultSettings(),
            VaultSettings(messagesOnly = false),
            VaultSettings(captureEnabled = false),
            VaultSettings(excludedPackages = setOf(KnownApps.WHATSAPP)),
        )
        var rejected = 0
        for (s in shapes) for (settings in settingsList) {
            val flags = (if (s.isGroupSummary) Notification.FLAG_GROUP_SUMMARY else 0)
            val verdict = filter.decide(
                s.packageName, flags, s.category, s.channelId, s.template,
                hasMessages = s.messages.isNotEmpty(), hasHistoricMessages = s.historicMessages.isNotEmpty(), settings = settings,
            )
            if (!verdict.accept) {
                rejected++
                assertNull("pre-filter rejected ($verdict) what the engine captures: $s / $settings", engine.process(s, settings))
            }
        }
        assertTrue("matrix should exercise rejects", rejected > 50)
    }

    @Test
    fun onlySelectedApps_rejectsUnselectedAsSettingsReject() {
        val selected = VaultSettings(appsToSave = AppsToSave.SELECTED_APPS, selectedPackages = setOf("com.example.chat"))
        assertEquals(Result.ACCEPT, decide(pkg = "com.example.chat", template = MESSAGING_TEMPLATE, settings = selected))
        val other = decide(pkg = "com.example.sms", template = MESSAGING_TEMPLATE, settings = selected)
        assertEquals(Result.NOT_SELECTED, other)
        assertTrue(other.isSettingsReject)
        // Exclusion is reported first; unknown settings (cold start) leave the decision to the engine.
        assertEquals(Result.EXCLUDED, decide(pkg = "com.example.chat", template = MESSAGING_TEMPLATE, settings = selected.copy(excludedPackages = setOf("com.example.chat"))))
        assertEquals(Result.ACCEPT, decide(pkg = "com.example.sms", template = MESSAGING_TEMPLATE, settings = null))
        assertTrue(VaultSettings().savesApp("anything"))
    }
}
