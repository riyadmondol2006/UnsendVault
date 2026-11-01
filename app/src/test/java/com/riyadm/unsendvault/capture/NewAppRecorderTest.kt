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
}
