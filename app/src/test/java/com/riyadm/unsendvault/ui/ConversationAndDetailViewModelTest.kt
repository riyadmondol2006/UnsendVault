package com.riyadm.unsendvault.ui

import app.cash.turbine.test
import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.data.fake.InMemorySettingsRepository
import com.riyadm.unsendvault.data.fake.InMemoryVaultRepository
import com.riyadm.unsendvault.reply.ReplyAvailability
import com.riyadm.unsendvault.reply.ReplyGateway
import com.riyadm.unsendvault.reply.ReplyResult
import com.riyadm.unsendvault.ui.conversation.ConversationEvent
import com.riyadm.unsendvault.ui.conversation.ConversationViewModel
import com.riyadm.unsendvault.ui.conversation.TimelineItem
import com.riyadm.unsendvault.ui.conversation.buildTimeline
import com.riyadm.unsendvault.ui.debug.DemoData
import com.riyadm.unsendvault.ui.detail.MessageDetailViewModel
import com.riyadm.unsendvault.ui.favorites.FavoritesViewModel
import com.riyadm.unsendvault.ui.onboarding.OnboardingStep
import com.riyadm.unsendvault.ui.onboarding.OnboardingViewModel
import com.riyadm.unsendvault.ui.settings.Vendor
import com.riyadm.unsendvault.ui.settings.vendorOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ConversationAndDetailViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val vault = InMemoryVaultRepository(Fixtures.conversations, Fixtures.messages)

    @Test
    fun timelineInsertsDayHeadersAboveEachDay() {
        val day1 = LocalDate.of(2025, 10, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val day2 = day1 + 86_400_000L
        val newestFirst = listOf(
            Fixtures.message(3, Fixtures.alex, "c", day2 + 5_000),
            Fixtures.message(2, Fixtures.alex, "b", day1 + 9_000),
            Fixtures.message(1, Fixtures.alex, "a", day1 + 1_000),
        )
        val timeline = buildTimeline(newestFirst, ZoneOffset.UTC)
        assertEquals(listOf("m3", "d2025-10-02", "m2", "m1", "d2025-10-01"), timeline.map { it.key })
        assertTrue(timeline[1] is TimelineItem.DayHeader)
    }

    @Test
    fun multiSelectDelete() = runTest {
        val vm = ConversationViewModel(Fixtures.alex.id, vault, ZoneOffset.UTC)
        vm.state.testState {
            assertEquals(2, awaitMatching { !it.loading }.messages.size)
            vm.toggleSelection(1)
            vm.toggleSelection(2)
            vm.toggleSelection(2)
            assertEquals(setOf(1L), awaitMatching { it.selection == setOf(1L) }.selection)
            vm.selectAll()
            awaitMatching { it.selection == setOf(1L, 2L) }
            vm.toggleSelection(2)
            awaitMatching { it.selection == setOf(1L) }
            vm.events.test {
                vm.deleteSelected()
                assertEquals(ConversationEvent.MessagesDeleted(1), awaitItem())
            }
            val after = awaitMatching { it.messages.size == 1 }
            assertFalse(after.selecting)
            assertEquals(2L, after.messages.single().id)
        }
    }

    @Test
    fun deleteConversationEmitsEvent() = runTest {
        val vm = ConversationViewModel(Fixtures.team.id, vault, ZoneOffset.UTC)
        vm.events.test {
            vm.deleteConversation()
            assertEquals(ConversationEvent.ConversationDeleted, awaitItem())
        }
        assertNull(vault.observeConversation(Fixtures.team.id).first())
    }

    @Test
    fun detailShowsEvidenceTogglesFavoriteAndDeletes() = runTest {
        val vm = MessageDetailViewModel(3, vault)
        vm.state.testState {
            val detail = awaitMatching { !it.loading }.detail!!
            assertEquals("This message was deleted", detail.message.deletionEvidence?.markerText)
            vm.toggleFavorite()
            assertTrue(awaitMatching { it.detail?.message?.isFavorite == true }.detail!!.message.isFavorite)
            vm.deleted.test {
                vm.delete()
                awaitItem()
            }
            assertNull(awaitMatching { it.detail == null }.detail)
        }
    }

    @Test
    fun favoritesListAndRemove() = runTest {
        val vm = FavoritesViewModel(vault)
        vm.state.testState {
            assertEquals(listOf(2L), awaitMatching { !it.loading }.messages.map { it.id })
            vm.removeFavorite(2)
            assertTrue(awaitMatching { it.messages.isEmpty() }.messages.isEmpty())
        }
    }

    @Test
    fun onboardingStepsAndCompletion() = runTest {
        val settings = InMemorySettingsRepository()
        val listener = FakeListenerStatus(granted = false)
        val vm = OnboardingViewModel(settings, listener)
        vm.state.testState {
            assertEquals(OnboardingStep.WELCOME, awaitItem().step)
            vm.back()
            vm.next()
            vm.next()
            vm.next()
            val access = awaitMatching { it.step == OnboardingStep.ACCESS }
            assertFalse(access.accessGranted)
            listener.granted = true
            vm.refreshAccess()
            awaitMatching { it.accessGranted }
            vm.next()
            vm.next()
            assertTrue(awaitMatching { it.step == OnboardingStep.LOCK }.isLast)
            vm.setAppLockEnabled(true)
            awaitMatching { it.appLockEnabled }
            vm.finish()
        }
        assertTrue(settings.current.onboardingCompleted)
        assertTrue(settings.current.appLockEnabled)
    }

    @Test
    fun demoDataIsSyntheticAndCoversApps() = runTest {
        val demo = InMemoryVaultRepository()
        val inserted = DemoData.insert(demo, now = 1_761_696_000_000L)
        assertTrue(inserted >= 12)
        val all = demo.observeMessages(MessageQuery()).first()
        assertEquals(
            setOf("com.whatsapp", "com.instagram.android", "com.facebook.orca", "org.telegram.messenger"),
            all.map { it.packageName }.toSet(),
        )
        assertEquals(2, all.count { it.isFavorite })
        assertTrue(all.any { it.sender == "Alex Rivera" })
    }

    @Test
    fun vendorDetection() {
        assertEquals(Vendor.XIAOMI, vendorOf("Xiaomi", "Redmi"))
        assertEquals(Vendor.XIAOMI, vendorOf("POCO"))
        assertEquals(Vendor.SAMSUNG, vendorOf("samsung"))
        assertEquals(Vendor.HUAWEI, vendorOf("HONOR"))
        assertEquals(Vendor.ONEPLUS, vendorOf("OnePlus"))
        assertEquals(Vendor.OPPO, vendorOf("realme"))
        assertEquals(Vendor.VIVO, vendorOf("vivo"))
        assertEquals(Vendor.OTHER, vendorOf("Google", "google"))
    }

    private class FakeReplyGateway(var availability: ReplyAvailability, var result: ReplyResult = ReplyResult.SENT) : ReplyGateway {
        val sent = mutableListOf<Triple<String, String, String>>()
        override suspend fun availability(packageName: String, conversationKey: String) = availability
        override suspend fun send(packageName: String, conversationKey: String, text: String): ReplyResult {
            sent += Triple(packageName, conversationKey, text)
            return result
        }
    }

    @Test
    fun replyAvailability_andSend() = runTest {
        val gateway = FakeReplyGateway(ReplyAvailability.AVAILABLE)
        val vm = ConversationViewModel(Fixtures.alex.id, vault, ZoneOffset.UTC, replyGateway = gateway)
        vm.state.testState {
            awaitMatching { it.reply == ReplyAvailability.AVAILABLE }
            vm.events.test {
                vm.sendReply("on my way")
                assertEquals(ConversationEvent.Reply(ReplyResult.SENT), awaitItem())
            }
            assertEquals(listOf(Triple(Fixtures.alex.packageName, Fixtures.alex.conversationKey, "on my way")), gateway.sent)
            gateway.availability = ReplyAvailability.NO_NOTIFICATION
            vm.refreshReply()
            awaitMatching { it.reply == ReplyAvailability.NO_NOTIFICATION }
        }
    }

    @Test
    fun noGateway_noReplyBar() = runTest {
        val vm = ConversationViewModel(Fixtures.alex.id, vault, ZoneOffset.UTC)
        vm.state.testState { assertNull(awaitMatching { !it.loading }.reply) }
    }
}
