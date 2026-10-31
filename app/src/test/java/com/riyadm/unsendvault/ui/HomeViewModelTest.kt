package com.riyadm.unsendvault.ui

import app.cash.turbine.test
import com.riyadm.unsendvault.core.model.StartTab
import com.riyadm.unsendvault.core.model.VaultSettings
import com.riyadm.unsendvault.data.fake.InMemorySettingsRepository
import com.riyadm.unsendvault.data.fake.InMemoryVaultRepository
import com.riyadm.unsendvault.ui.home.CaptureStatus
import com.riyadm.unsendvault.ui.home.HomeTab
import com.riyadm.unsendvault.ui.home.HomeViewModel
import com.riyadm.unsendvault.ui.home.captureStatusOf
import com.riyadm.unsendvault.ui.home.toHomeTab
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val vault = InMemoryVaultRepository(Fixtures.conversations, Fixtures.messages)
    private val settings = InMemorySettingsRepository(VaultSettings(onboardingCompleted = true))
    private val listener = FakeListenerStatus()

    private fun vm() = HomeViewModel(vault, settings, listener)

    @Test
    fun captureStatusPriority() {
        assertEquals(CaptureStatus.NO_ACCESS, captureStatusOf(accessGranted = false, captureEnabled = false, connected = false))
        assertEquals(CaptureStatus.PAUSED, captureStatusOf(true, captureEnabled = false, connected = false))
        assertEquals(CaptureStatus.DISCONNECTED, captureStatusOf(true, true, connected = false))
        assertEquals(CaptureStatus.ACTIVE, captureStatusOf(true, true, true))
    }

    @Test
    fun populatedStateNewestFirst() = runTest {
        vm().state.testState {
            val state = awaitMatching { !it.loading }
            assertEquals(listOf(2L, 3L, 4L, 1L), state.recent.map { it.id })
            assertEquals(3, state.conversations.size)
            assertEquals(2, state.apps.size)
            assertEquals(CaptureStatus.ACTIVE, state.captureStatus)
            assertEquals(4, state.stats?.totalMessages)
        }
    }

    @Test
    fun appFilterAppliesToRecentAndConversations() = runTest {
        val vm = vm()
        vm.state.testState {
            awaitMatching { !it.loading }
            vm.togglePackage(Fixtures.TELEGRAM)
            val filtered = awaitMatching { it.selectedPackages == setOf(Fixtures.TELEGRAM) && it.recent.size == 1 && it.conversations.size == 1 }
            assertEquals(listOf(4L), filtered.recent.map { it.id })
            assertEquals(listOf(3L), filtered.conversations.map { it.id })
            vm.clearPackageFilter()
            assertEquals(4, awaitMatching { it.selectedPackages.isEmpty() && it.recent.size == 4 }.recent.size)
        }
    }

    @Test
    fun openAppSwitchesToConversations() = runTest {
        val vm = vm()
        vm.state.testState {
            awaitMatching { !it.loading }
            vm.openApp(Fixtures.WHATSAPP)
            val state = awaitMatching { it.tab == HomeTab.CONVERSATIONS && it.conversations.size == 2 }
            assertTrue(state.conversations.all { it.packageName == Fixtures.WHATSAPP })
        }
    }

    @Test
    fun emptyArchiveWithoutAccess() = runTest {
        listener.granted = false
        HomeViewModel(InMemoryVaultRepository(), settings, listener).state.testState {
            val state = awaitMatching { !it.loading }
            assertTrue(state.recent.isEmpty())
            assertEquals(CaptureStatus.NO_ACCESS, state.captureStatus)
        }
    }

    @Test
    fun accessRefreshedOnResumeAndPausedCaptureCanResume() = runTest {
        listener.granted = false
        val vm = vm()
        vm.state.testState {
            assertEquals(CaptureStatus.NO_ACCESS, awaitMatching { !it.loading }.captureStatus)
            listener.granted = true
            vm.refreshAccess()
            awaitMatching { it.captureStatus == CaptureStatus.ACTIVE }
            settings.update { it.copy(captureEnabled = false) }
            awaitMatching { it.captureStatus == CaptureStatus.PAUSED }
            vm.resumeCapture()
            awaitMatching { it.captureStatus == CaptureStatus.ACTIVE }
            listener.connected.value = false
            awaitMatching { it.captureStatus == CaptureStatus.DISCONNECTED }
            vm.reconnect()
            assertEquals(1, listener.rebindRequests)
        }
    }

    @Test
    fun filterForDeletedAppIsDropped() = runTest {
        val vm = vm()
        vm.state.testState {
            awaitMatching { !it.loading }
            vm.togglePackage(Fixtures.TELEGRAM)
            awaitMatching { it.selectedPackages == setOf(Fixtures.TELEGRAM) }
            vault.deleteApp(Fixtures.TELEGRAM)
            val state = awaitMatching { it.apps.size == 1 && it.selectedPackages.isEmpty() && it.recent.size == 3 }
            assertEquals(3, state.recent.size)
        }
    }

    @Test
    fun selectedConversationClearedWhenDeleted() = runTest {
        val vm = vm()
        vm.state.testState {
            awaitMatching { !it.loading }
            vm.selectConversation(Fixtures.club.id)
            awaitMatching { it.selectedConversationId == Fixtures.club.id }
            vault.deleteConversation(Fixtures.club.id)
            assertNull(awaitMatching { it.conversations.size == 2 }.selectedConversationId)
        }
    }

    @Test
    fun startTabSettingSelectsInitialTab_untilUserPicks() = runTest {
        settings.update { it.copy(startTab = StartTab.CHATS) }
        val vm = vm()
        vm.state.testState {
            assertEquals(HomeTab.CONVERSATIONS, awaitMatching { !it.loading }.tab)
            vm.selectTab(HomeTab.APPS)
            assertEquals(HomeTab.APPS, awaitMatching { it.tab == HomeTab.APPS }.tab)
            // Changing the setting later doesn't yank the user off the tab they picked.
            settings.update { it.copy(startTab = StartTab.RECENT) }
            expectNoEvents()
        }
        assertEquals(HomeTab.RECENT, StartTab.RECENT.toHomeTab())
        assertEquals(HomeTab.APPS, StartTab.APPS.toHomeTab())
    }

    @Test
    fun defaultStartTabIsRecent() = runTest {
        vm().state.testState { assertEquals(HomeTab.RECENT, awaitMatching { !it.loading }.tab) }
    }
}
