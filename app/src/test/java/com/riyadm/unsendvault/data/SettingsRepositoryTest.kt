package com.riyadm.unsendvault.data

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import com.riyadm.unsendvault.core.model.AppsToSave
import com.riyadm.unsendvault.core.model.StartTab
import com.riyadm.unsendvault.core.model.ThemeMode
import com.riyadm.unsendvault.core.model.VaultSettings
import com.riyadm.unsendvault.data.settings.DataStoreSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private fun repo(file: File, s: CoroutineScope = scope) = DataStoreSettingsRepository(
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = s,
            produceFile = { file },
        ),
        s,
    )

    @Test
    fun defaults_whenEmpty() = runBlocking {
        val r = repo(File(tmp.root, "a.preferences_pb"))
        assertEquals(VaultSettings(), r.settings.first())
    }

    @Test
    fun everyField_roundTrips_andSurvivesReopen() = runBlocking {
        val file = File(tmp.root, "b.preferences_pb")
        val expected = VaultSettings(
            onboardingCompleted = true,
            captureEnabled = false,
            excludedPackages = setOf("a.b", "c.d"),
            messagesOnly = false,
            retentionDays = 90,
            filterOtp = false,
            filterSensitive = false,
            appLockEnabled = false,
            lockTimeoutSeconds = 45,
            themeMode = ThemeMode.DARK,
            amoledBlack = true,
            dynamicColor = false,
            widgetShowContent = true,
            lastExportAt = 123_456L,
            backupReminderDays = 14,
            secureScreen = false,
            startTab = StartTab.CHATS,
            savedMessageNotifications = true,
            notificationShowContent = true,
            appsToSave = AppsToSave.SELECTED_APPS,
            selectedPackages = setOf("com.whatsapp", "com.viber.voip"),
            newApps = listOf("com.b.sms", "com.a.chat"),
        )
        val s1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val r1 = repo(file, s1)
        r1.update { expected }
        assertEquals(expected, r1.settings.first())
        withTimeout(5_000) { while (r1.current != expected) delay(10) }
        s1.cancel()
        delay(100)

        val r2 = repo(file)
        assertEquals(expected, r2.settings.first())
        r2.update { it.copy(lastExportAt = null) }
        assertNull(r2.settings.first().lastExportAt)
    }

    @Test
    fun widgetContent_forcedOffWhileAppLocked() = runBlocking {
        val r = repo(File(tmp.root, "c.preferences_pb"))
        r.update { it.copy(widgetShowContent = true, appLockEnabled = true) }
        assertFalse(r.settings.first().widgetShowContent)
    }

    @Test
    fun notificationContent_forcedOffWhileAppLocked() = runBlocking {
        val r = repo(File(tmp.root, "n.preferences_pb"))
        r.update { it.copy(savedMessageNotifications = true, notificationShowContent = true, appLockEnabled = true) }
        val s = r.settings.first()
        assertTrue(s.savedMessageNotifications)
        assertFalse(s.notificationShowContent)
    }

    @Test
    fun newSettings_defaultOff_andRecentTab() = runBlocking {
        val s = repo(File(tmp.root, "d.preferences_pb")).settings.first()
        assertEquals(StartTab.RECENT, s.startTab)
        assertFalse(s.savedMessageNotifications)
        assertFalse(s.notificationShowContent)
    }

    @Test
    fun corruptFile_fallsBackToDefaults() = runBlocking {
        val file = File(tmp.root, "d.preferences_pb").apply { writeBytes(byteArrayOf(0x7F, 0x00, 0x13, 0x37, 0x42, 0x01)) }
        val r = repo(file)
        assertEquals(VaultSettings(), r.settings.first())
        r.update { it.copy(retentionDays = 7) }
        assertEquals(7, r.settings.first().retentionDays)
    }

    @Test
    fun negativeValues_areClamped() = runBlocking {
        val r = repo(File(tmp.root, "e.preferences_pb"))
        r.update { it.copy(retentionDays = -5, lockTimeoutSeconds = -1, backupReminderDays = -3) }
        val s = r.settings.first()
        assertEquals(0, s.retentionDays)
        assertEquals(0, s.lockTimeoutSeconds)
        assertEquals(0, s.backupReminderDays)
    }

    @Test
    fun newApps_keepOrder_dedupe_andCap() = runBlocking {
        val r = repo(File(tmp.root, "apps.preferences_pb"))
        val many = (1..25).map { "com.app$it" }
        r.update { it.copy(newApps = listOf("com.app1") + many) }
        val stored = r.settings.first().newApps
        assertEquals(VaultSettings.MAX_NEW_APPS, stored.size)
        assertEquals("com.app6", stored.first())
        assertEquals("com.app25", stored.last())
        assertEquals(AppsToSave.ALL_CHAT_APPS, repo(File(tmp.root, "fresh.preferences_pb")).settings.first().appsToSave)
    }
}
