package com.riyadm.unsendvault.capture

import com.riyadm.unsendvault.core.model.AppsToSave
import com.riyadm.unsendvault.core.model.VaultSettings
import com.riyadm.unsendvault.data.fake.InMemorySettingsRepository
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NewAppRecorderTest {
    @Test
    fun recordsPackageNamesOnce_skipsSelectedExcludedAndAllMode_andCaps() = runTest {
        val settings = InMemorySettingsRepository(
            VaultSettings(appsToSave = AppsToSave.SELECTED_APPS, selectedPackages = setOf("com.chat"), excludedPackages = setOf("com.game")),
        )
        val recorder = NewAppRecorder(this) { settings }
        recorder.record("com.sms")
        recorder.record("com.sms")
        recorder.record("com.chat")
        recorder.record("com.game")
        advanceUntilIdle()
        assertEquals(listOf("com.sms"), settings.current.newApps)

        (1..25).forEach { recorder.record("com.app$it") }
        advanceUntilIdle()
        assertEquals(VaultSettings.MAX_NEW_APPS, settings.current.newApps.size)
        assertEquals("com.app25", settings.current.newApps.last())

        val allMode = InMemorySettingsRepository(VaultSettings())
        NewAppRecorder(this) { allMode }.record("com.sms")
        advanceUntilIdle()
        assertEquals(emptyList<String>(), allMode.current.newApps)
    }

    @Test
    fun recordsNothingWhenSuggestionsAreOff() = runTest {
        val off = InMemorySettingsRepository(VaultSettings(appsToSave = AppsToSave.SELECTED_APPS, suggestNewApps = false))
        NewAppRecorder(this) { off }.record("com.sms")
        advanceUntilIdle()
        assertEquals(emptyList<String>(), off.current.newApps)
    }

    @Test
    fun suggestedAgain_afterSuggestionsAreSwitchedOffAndOn() = runTest {
        val settings = InMemorySettingsRepository(VaultSettings(appsToSave = AppsToSave.SELECTED_APPS))
        val recorder = NewAppRecorder(this) { settings }
        recorder.record("com.sms")
        advanceUntilIdle()
        assertEquals(listOf("com.sms"), settings.current.newApps)
        // What the Settings switch does: off clears the list, on starts empty.
        settings.update { it.copy(suggestNewApps = false, newApps = emptyList()) }
        recorder.record("com.sms")
        advanceUntilIdle()
        assertEquals(emptyList<String>(), settings.current.newApps)
        settings.update { it.copy(suggestNewApps = true) }
        recorder.record("com.sms")
        advanceUntilIdle()
        assertEquals(listOf("com.sms"), settings.current.newApps)
    }
}
