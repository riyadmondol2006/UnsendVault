package com.riyadm.unsendvault.ui

import app.cash.turbine.test
import com.riyadm.unsendvault.apps.ProfileKind
import com.riyadm.unsendvault.core.model.AppsToSave
import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.core.model.ThemeMode
import com.riyadm.unsendvault.core.model.VaultSettings
import com.riyadm.unsendvault.data.fake.InMemorySettingsRepository
import com.riyadm.unsendvault.data.fake.InMemoryVaultRepository
import com.riyadm.unsendvault.ui.settings.AppPickerMode
import com.riyadm.unsendvault.ui.settings.ExclusionCandidate
import com.riyadm.unsendvault.ui.settings.InstalledApp
import com.riyadm.unsendvault.ui.settings.InstalledAppsSource
import com.riyadm.unsendvault.ui.settings.SettingsEvent
import com.riyadm.unsendvault.ui.settings.SettingsUiState
import com.riyadm.unsendvault.ui.settings.SettingsViewModel
import com.riyadm.unsendvault.ui.settings.orderAppChoices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val settings = InMemorySettingsRepository(VaultSettings(onboardingCompleted = true))
    private val vault = InMemoryVaultRepository(Fixtures.conversations, Fixtures.messages)
    private val scheduler = FakeRetentionScheduler(deletes = 3)
    private val launcher = listOf(InstalledApp("com.example.notes", "Notes"), InstalledApp(Fixtures.WHATSAPP, "WhatsApp"))
    private var demoCalls = 0

    private fun vm(withDemo: Boolean = false) = SettingsViewModel(
        settings, vault, scheduler, { launcher },
        demoDataInserter = if (withDemo) ({ demoCalls++; 7 }) else null,
    )

    @Test
    fun retentionUpdatesSettingsAndReschedules() = runTest {
        val vm = vm()
        vm.setRetentionDays(90)
        assertEquals(90, settings.current.retentionDays)
        assertEquals(listOf(90), scheduler.scheduled)
        vm.setRetentionDays(0)
        assertEquals(listOf(90, 0), scheduler.scheduled)
    }

    @Test
    fun runCleanupNowReportsDeletedCount() = runTest {
        val vm = vm()
        vm.events.test {
            vm.runCleanupNow()
            assertEquals(SettingsEvent.CleanupFinished(3), awaitItem())
        }
        assertEquals(1, scheduler.runs)
    }

    @Test
    fun excludingAppWithMessagesOffersDeletion() = runTest {
        val vm = vm()
        vm.state.testState {
            val state = awaitMatching { it.settings != null && it.archiveApps.size == 2 }
            val telegram = state.exclusionCandidates.first { it.packageName == Fixtures.TELEGRAM }
            vm.events.test {
                vm.setExcluded(telegram, true)
                assertEquals(SettingsEvent.OfferDeleteAppData(Fixtures.TELEGRAM, "Telegram", 1), awaitItem())
                vm.deleteAppData(Fixtures.TELEGRAM, "Telegram")
                assertEquals(SettingsEvent.AppDataDeleted("Telegram"), awaitItem())
            }
        }
        assertTrue(Fixtures.TELEGRAM in settings.current.excludedPackages)
        assertTrue(vault.observeMessages(MessageQuery(packages = setOf(Fixtures.TELEGRAM))).first().isEmpty())
    }

    @Test
    fun excludingAppWithoutMessagesDoesNotOffer() = runTest {
        val vm = vm()
        vm.loadLauncherApps()
        vm.state.testState {
            val state = awaitMatching { it.launcherApps != null && it.settings != null }
            val notes = state.exclusionCandidates.first { it.packageName == "com.example.notes" }
            vm.events.test {
                vm.setExcluded(notes, true)
                expectNoEvents()
            }
            val updated = awaitMatching { s -> s.exclusionCandidates.first { it.packageName == "com.example.notes" }.excluded }
            // Archive apps first, then other launcher apps; WhatsApp is not duplicated.
            assertEquals(listOf("org.telegram.messenger", "com.whatsapp", "com.example.notes").sorted(), updated.exclusionCandidates.map { it.packageName }.sorted())
            assertEquals(3, updated.exclusionCandidates.size)
            assertEquals("com.example.notes", updated.exclusionCandidates.last().packageName)
            vm.setExcluded(notes.copy(excluded = true), false)
            awaitMatching { s -> !s.exclusionCandidates.first { it.packageName == "com.example.notes" }.excluded }
        }
    }

    @Test
    fun appLockForcesWidgetContentOff() = runTest {
        val vm = vm()
        vm.setWidgetShowContent(true)
        assertTrue(settings.current.widgetShowContent)
        vm.setAppLockEnabled(true)
        assertTrue(settings.current.appLockEnabled)
        assertFalse(settings.current.widgetShowContent)
        vm.setWidgetShowContent(true)
        assertFalse("widget content must stay hidden while locked", settings.current.widgetShowContent)
        vm.setAppLockEnabled(false)
        vm.setWidgetShowContent(true)
        assertTrue(settings.current.widgetShowContent)
    }

    @Test
    fun simpleTogglesPersist() = runTest {
        val vm = vm()
        vm.setCaptureEnabled(false)
        vm.setMessagesOnly(false)
        vm.setFilterOtp(false)
        vm.setFilterSensitive(false)
        vm.setSecureScreen(false)
        vm.setLockTimeout(300)
        vm.setThemeMode(ThemeMode.DARK)
        vm.setAmoledBlack(true)
        vm.setDynamicColor(false)
        vm.setBackupReminderDays(14)
        val s = settings.current
        assertFalse(s.captureEnabled)
        assertFalse(s.messagesOnly)
        assertFalse(s.filterOtp)
        assertFalse(s.filterSensitive)
        assertFalse(s.secureScreen)
        assertEquals(300, s.lockTimeoutSeconds)
        assertEquals(ThemeMode.DARK, s.themeMode)
        assertTrue(s.amoledBlack)
        assertFalse(s.dynamicColor)
        assertEquals(14, s.backupReminderDays)
    }

    @Test
    fun deleteAllClearsArchiveButKeepsSettings() = runTest {
        settings.update { it.copy(retentionDays = 7) }
        val vm = vm()
        vm.events.test {
            vm.deleteAll()
            assertEquals(SettingsEvent.AllDataDeleted, awaitItem())
        }
        assertTrue(vault.observeMessages(MessageQuery()).first().isEmpty())
        assertEquals(7, settings.current.retentionDays)
    }

    @Test
    fun demoDataOnlyWhenInserterProvided() = runTest {
        assertFalse(vm().canInsertDemoData)
        val vm = vm(withDemo = true)
        assertTrue(vm.canInsertDemoData)
        vm.events.test {
            vm.insertDemoData()
            assertEquals(SettingsEvent.DemoDataInserted(7), awaitItem())
        }
        assertEquals(1, demoCalls)
    }

    @Test
    fun savedNotifications_cancelledWhenOffContentHiddenOrLocked() = runTest {
        var cancels = 0
        val vm = SettingsViewModel(settings, vault, scheduler, { launcher }, cancelSavedNotifications = { cancels++ })
        vm.setSavedMessageNotifications(true)
        vm.setNotificationShowContent(true)
        advanceUntilIdle()
        assertEquals(0, cancels)
        assertTrue(settings.current.savedMessageNotifications && settings.current.notificationShowContent)

        vm.setAppLockEnabled(true)
        advanceUntilIdle()
        assertEquals(1, cancels)
        assertFalse(settings.current.notificationShowContent)
        vm.setNotificationShowContent(true) // refused while locked
        advanceUntilIdle()
        assertFalse(settings.current.notificationShowContent)

        vm.setSavedMessageNotifications(false)
        advanceUntilIdle()
        assertFalse(settings.current.savedMessageNotifications)
        assertTrue(cancels >= 2)
    }

    @Test
    fun startTabIsStored() = runTest {
        val vm = vm()
        vm.setStartTab(com.riyadm.unsendvault.core.model.StartTab.APPS)
        advanceUntilIdle()
        assertEquals(com.riyadm.unsendvault.core.model.StartTab.APPS, settings.current.startTab)
    }

    @Test
    fun exclusionCandidates_carryProfiles_andFixPackageNameLabels() {
        val state = SettingsUiState(
            settings = com.riyadm.unsendvault.core.model.VaultSettings(excludedPackages = setOf("com.viber")),
            archiveApps = listOf(
                com.riyadm.unsendvault.core.model.AppSummary("com.workchat", "com.workchat", 3, 1, 1L),
                com.riyadm.unsendvault.core.model.AppSummary("com.whatsapp", "WhatsApp", 5, 2, 2L),
            ),
            launcherApps = listOf(
                InstalledApp("com.workchat", "Work Chat", ProfileKind.WORK),
                InstalledApp("com.whatsapp", "WhatsApp", ProfileKind.PERSONAL, setOf(ProfileKind.WORK)),
                InstalledApp("com.viber", "Viber", ProfileKind.WORK),
            ),
        )
        val byPkg = state.exclusionCandidates.associateBy { it.packageName }
        assertEquals("Work Chat", byPkg.getValue("com.workchat").label) // stored label was the package name
        assertEquals(ProfileKind.WORK, byPkg.getValue("com.workchat").profile)
        assertEquals(3, byPkg.getValue("com.workchat").archivedMessages)
        assertEquals(setOf(ProfileKind.WORK), byPkg.getValue("com.whatsapp").alsoIn)
        assertTrue(byPkg.getValue("com.viber").excluded)
        assertEquals(ProfileKind.WORK, byPkg.getValue("com.viber").profile)
    }

    @Test
    fun selectedMode_firstSwitchPreTicksArchiveAndKnownChatApps_minusExclusions() = runTest {
        settings.update { it.copy(excludedPackages = setOf(Fixtures.TELEGRAM)) }
        val vm = vm()
        vm.setAppsToSave(AppsToSave.SELECTED_APPS)
        advanceUntilIdle()
        assertEquals(AppsToSave.SELECTED_APPS, settings.current.appsToSave)
        // WhatsApp: in the archive and a known chat app; Telegram: excluded; Notes: not a chat app.
        assertEquals(setOf(Fixtures.WHATSAPP), settings.current.selectedPackages)

        // Back to all apps and again: the earlier choice is kept, not re-seeded.
        vm.setSelected(ExclusionCandidate("com.example.notes", "Notes", 0, false), true)
        vm.setAppsToSave(AppsToSave.ALL_CHAT_APPS)
        vm.setAppsToSave(AppsToSave.SELECTED_APPS)
        advanceUntilIdle()
        assertEquals(setOf(Fixtures.WHATSAPP, "com.example.notes"), settings.current.selectedPackages)
    }

    @Test
    fun ticking_liftsExclusionAndNewMark_untickingOffersDelete() = runTest {
        settings.update {
            it.copy(appsToSave = AppsToSave.SELECTED_APPS, excludedPackages = setOf("com.sms"), newApps = listOf("com.sms", "com.other"))
        }
        val vm = vm()
        vm.state.testState {
            val state = awaitMatching { it.settings?.newApps?.size == 2 }
            val sms = state.exclusionCandidates.single { it.packageName == "com.sms" }
            assertTrue(sms.isNew)
            vm.setSelected(sms, true)
            val after = awaitMatching { s -> s.exclusionCandidates.single { it.packageName == "com.sms" }.selected }
            assertFalse(after.exclusionCandidates.single { it.packageName == "com.sms" }.isNew)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("com.other"), settings.current.newApps)
        assertFalse("com.sms" in settings.current.excludedPackages)

        vm.events.test {
            vm.setSelected(ExclusionCandidate(Fixtures.WHATSAPP, "WhatsApp", 3, false, selected = true), false)
            assertEquals(SettingsEvent.OfferDeleteAppData(Fixtures.WHATSAPP, "WhatsApp", 3), awaitItem())
        }
    }

    @Test
    fun appChoiceOrder_newFirst_thenTickedAtOpen_thenRest_withoutJumping() {
        fun c(pkg: String, label: String, selected: Boolean = false, excluded: Boolean = false, isNew: Boolean = false) =
            ExclusionCandidate(pkg, label, 0, excluded, selected = selected, isNew = isNew)
        val list = listOf(c("a", "Alpha"), c("b", "Bravo", selected = true), c("z", "Zulu SMS", isNew = true), c("c", "Charlie", excluded = true), c("n", "Nova", isNew = true))
        val newAtOpen = setOf("z", "n")
        assertEquals(listOf("n", "z", "b", "a", "c"), orderAppChoices(list, AppPickerMode.SELECT, setOf("b"), newAtOpen).map { it.packageName })
        // Ticking rows after opening (a plain one and a new one, which loses its "New" mark) moves nothing.
        val ticked = list.map {
            when (it.packageName) {
                "a" -> it.copy(selected = true)
                "z" -> it.copy(selected = true, isNew = false)
                else -> it
            }
        }
        assertEquals(listOf("n", "z", "b", "a", "c"), orderAppChoices(ticked, AppPickerMode.SELECT, setOf("b"), newAtOpen).map { it.packageName })
        assertEquals(listOf("c", "a", "b", "z", "n"), orderAppChoices(list, AppPickerMode.EXCLUDE, pinned = setOf("c")).map { it.packageName })
    }

    @Test
    fun appsWithoutLauncherEntry_getTheirRealName() = runTest {
        settings.update { it.copy(appsToSave = AppsToSave.SELECTED_APPS, newApps = listOf("com.android.shell")) }
        val source = object : InstalledAppsSource {
            override suspend fun launcherApps() = launcher
            override suspend fun labelFor(packageName: String) = if (packageName == "com.android.shell") "Shell" else null
        }
        val vm = SettingsViewModel(settings, vault, scheduler, source)
        vm.state.testState {
            vm.loadLauncherApps()
            val state = awaitMatching { s -> s.exclusionCandidates.any { it.packageName == "com.android.shell" && it.label == "Shell" } }
            assertTrue(state.exclusionCandidates.single { it.packageName == "com.android.shell" }.isNew)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
