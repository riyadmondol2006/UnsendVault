package com.riyadm.unsendvault.capture.engine

import com.riyadm.unsendvault.capture.Fixtures.T0
import com.riyadm.unsendvault.capture.Fixtures.msg
import com.riyadm.unsendvault.capture.Fixtures.snapshot
import com.riyadm.unsendvault.capture.ParsedNotification
import com.riyadm.unsendvault.capture.RemovalEvent
import com.riyadm.unsendvault.capture.adapter.AdapterRegistry
import com.riyadm.unsendvault.capture.adapter.AppLabelResolver
import com.riyadm.unsendvault.capture.adapter.KnownApps
import com.riyadm.unsendvault.capture.filter.SensitiveContentFilter
import com.riyadm.unsendvault.core.model.ContentKind
import com.riyadm.unsendvault.core.model.RemovalReason
import com.riyadm.unsendvault.core.model.AppsToSave
import com.riyadm.unsendvault.core.model.VaultSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCaptureEngineTest {
    private val own = "com.riyadm.unsendvault"

    private class RecordingSink : CaptureSink {
        val ingested = mutableListOf<Pair<ParsedNotification, Long>>()
        val removals = mutableListOf<RemovalEvent>()
        var failNext = false
        override suspend fun ingest(parsed: ParsedNotification, capturedAt: Long) {
            if (failNext) {
                failNext = false
                error("disk full")
            }
            ingested += parsed to capturedAt
        }

        override suspend fun recordRemoval(event: RemovalEvent) {
            removals += event
        }
    }

    private fun TestScope.engine(
        sink: CaptureSink = RecordingSink(),
        settings: () -> VaultSettings = { VaultSettings() },
        scope: CoroutineScope = backgroundScope,
        capacity: Int = DefaultCaptureEngine.DEFAULT_CAPACITY,
        window: Long = 0L,
    ) = DefaultCaptureEngine(
        scope = scope,
        dispatcher = StandardTestDispatcher(testScheduler),
        settingsProvider = { settings() },
        sink = sink,
        registry = AdapterRegistry.createDefault(),
        labels = AppLabelResolver { null },
        sensitiveFilter = SensitiveContentFilter(),
        ownPackage = own,
        clock = { 42L },
        capacity = capacity,
        coalesceWindowMillis = window,
        catchUpWindowMillis = window,
        monotonicClock = { testScheduler.currentTime },
    )

    private val waMessage = snapshot(
        packageName = KnownApps.WHATSAPP,
        title = "Alex Rivera",
        messages = listOf(msg("Alex Rivera", "Hello", T0)),
    )

    @Test
    fun processBuildsParsedNotification() = runTest {
        val parsed = engine().process(waMessage, VaultSettings())
        assertNotNull(parsed)
        parsed!!
        assertEquals(KnownApps.WHATSAPP, parsed.packageName)
        assertEquals("WhatsApp", parsed.appLabel)
        assertEquals("whatsapp", parsed.adapterId)
        assertEquals(waMessage.key, parsed.notificationKey)
        assertEquals(waMessage.postTime, parsed.postTime)
        assertEquals("Hello", parsed.messages.single().text)
    }

    @Test
    fun pausedExcludedOwnAndOngoingAreDropped() = runTest {
        val e = engine()
        assertNull(e.process(waMessage, VaultSettings(captureEnabled = false)))
        assertNull(e.process(waMessage, VaultSettings(excludedPackages = setOf(KnownApps.WHATSAPP))))
        assertNull(e.process(waMessage.copy(packageName = own), VaultSettings()))
        assertNull(e.process(waMessage.copy(isOngoing = true), VaultSettings()))
    }

    @Test
    fun onlySelectedApps_dropsEveryOtherApp() = runTest {
        val e = engine()
        val selectedMode = VaultSettings(appsToSave = AppsToSave.SELECTED_APPS)
        assertNull(e.process(waMessage, selectedMode))
        assertNotNull(e.process(waMessage, selectedMode.copy(selectedPackages = setOf(KnownApps.WHATSAPP))))
        // An exclusion still wins, and switching back to "all chat apps" ignores the selection.
        assertNull(e.process(waMessage, selectedMode.copy(selectedPackages = setOf(KnownApps.WHATSAPP), excludedPackages = setOf(KnownApps.WHATSAPP))))
        assertNotNull(e.process(waMessage, VaultSettings(selectedPackages = emptySet())))
    }

    @Test
    fun messagesOnlyGate() = runTest {
        val e = engine()
        val plain = snapshot(packageName = "com.example.shop", title = "Order update", text = "Your parcel arrives today")
        assertNull(e.process(plain, VaultSettings(messagesOnly = true)))
        assertEquals("Your parcel arrives today", e.process(plain, VaultSettings(messagesOnly = false))!!.messages.single().text)

        val categoryMsg = plain.copy(category = "msg")
        assertNotNull(e.process(categoryMsg, VaultSettings(messagesOnly = true)))

        val messagingStyle = snapshot(packageName = "com.example.chat", title = "Alex Rivera", messages = listOf(msg("Alex Rivera", "hi", T0)))
        assertNotNull(e.process(messagingStyle, VaultSettings(messagesOnly = true)))

        // A dedicated adapter claims flat notifications of its package.
        val flatWa = snapshot(packageName = KnownApps.WHATSAPP, title = "Alex Rivera", text = "hi")
        assertNotNull(e.process(flatWa, VaultSettings(messagesOnly = true)))

        // Mixed apps do not.
        val igLike = snapshot(packageName = KnownApps.INSTAGRAM, title = "Instagram", text = "alex.rivera liked your photo")
        assertNull(e.process(igLike, VaultSettings(messagesOnly = true)))
    }

    @Test
    fun sensitiveFilterAppliedPerSettings() = runTest {
        val sms = snapshot(packageName = KnownApps.GOOGLE_MESSAGES, title = "Bank", text = "483920 is your verification code")
        val filtered = engine().process(sms, VaultSettings())!!.messages.single()
        assertEquals(ContentKind.FILTERED_SENSITIVE, filtered.contentKind)
        assertTrue("483920" !in filtered.text)
        val raw = engine().process(sms, VaultSettings(filterOtp = false))!!.messages.single()
        assertEquals("483920 is your verification code", raw.text)
    }

    @Test
    fun noiseOnlyNotificationProducesNothing() = runTest {
        val summary = snapshot(packageName = KnownApps.WHATSAPP, title = "WhatsApp", text = "4 messages from 2 chats")
        assertNull(engine().process(summary, VaultSettings()))
    }

    @Test
    fun deletionMarkerOnlyNotificationIsStillIngested() = runTest {
        val s = snapshot(packageName = KnownApps.WHATSAPP, title = "Alex Rivera", messages = listOf(msg("Alex Rivera", "This message was deleted", T0)))
        val parsed = engine().process(s, VaultSettings())!!
        assertTrue(parsed.messages.isEmpty())
        assertEquals(1, parsed.deletionMarkers.size)
    }

    @Test
    fun pipelineIngestsInOrderAndSkipsIdenticalReposts() = runTest {
        val sink = RecordingSink()
        val e = engine(sink)
        e.onPosted(waMessage)
        runCurrent()
        e.onPosted(waMessage) // identical content after it was ingested: skipped
        runCurrent()
        val updated = waMessage.copy(messages = waMessage.messages + msg("Alex Rivera", "Second", T0 + 1))
        e.onPosted(updated)
        runCurrent()
        assertEquals(2, sink.ingested.size)
        assertEquals(listOf("Hello", "Second"), sink.ingested[1].first.messages.map { it.text })
        assertEquals(42L, sink.ingested[0].second)
    }

    @Test
    fun pipelineSurvivesSinkFailureAndReadsLatestSettings() = runTest {
        val sink = RecordingSink().apply { failNext = true }
        var settings = VaultSettings()
        val e = engine(sink, settings = { settings })
        e.onPosted(waMessage)
        runCurrent()
        assertEquals(0, sink.ingested.size)
        // Failure did not mark it as ingested: a re-post is retried.
        e.onPosted(waMessage)
        runCurrent()
        assertEquals(1, sink.ingested.size)

        settings = VaultSettings(captureEnabled = false)
        e.onPosted(waMessage.copy(key = "other", messages = listOf(msg("Alex Rivera", "while paused", T0 + 5))))
        runCurrent()
        assertEquals(1, sink.ingested.size)
    }

    @Test
    fun removalsAreRecordedButNeverIngested() = runTest {
        val sink = RecordingSink()
        val e = engine(sink)
        val event = RemovalEvent(KnownApps.WHATSAPP, waMessage.key, RemovalReason.APP_CANCELLED, 99L)
        e.onRemoved(event)
        e.onRemoved(event.copy(packageName = own))
        runCurrent()
        assertEquals(listOf(event), sink.removals)
        assertTrue(sink.ingested.isEmpty())
    }

    @Test
    fun boundedQueueDropsOldestUnderPressure() = runTest {
        val sink = RecordingSink()
        val e = engine(sink, capacity = 2)
        repeat(5) { i ->
            e.onPosted(waMessage.copy(key = "k$i", messages = listOf(msg("Alex Rivera", "m$i", T0 + i))))
        }
        runCurrent()
        // The consumer had not started yet, so only the newest items survive.
        assertTrue(sink.ingested.size <= 3)
        assertEquals("m4", sink.ingested.last().first.messages.single().text)
    }
}
