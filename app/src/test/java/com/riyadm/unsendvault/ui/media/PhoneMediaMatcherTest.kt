package com.riyadm.unsendvault.ui.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMediaMatcherTest {
    private val t0 = 1_700_000_000_000L
    private val wa = "com.whatsapp"

    private fun file(
        name: String,
        path: String,
        offsetMs: Long,
        kind: PhoneMediaKind = PhoneMediaKind.IMAGE,
        owner: String? = null,
    ) = PhoneMediaFile("content://media/external/images/media/$name", kind, "image/jpeg", t0 + offsetMs, path, owner, name)

    private fun pick(files: List<PhoneMediaFile>, text: String = "📷 Photo", anyApp: Boolean = false, pkg: String = wa, label: String = "WhatsApp") =
        PhoneMediaMatcher.candidates(files, pkg, label, t0, text, anyApp).map { it.name }

    @Test
    fun onlyTheAppsOwnFolders_android11Layout() {
        val files = listOf(
            file("wa", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", 2_000),
            file("business", "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images/", 1_000),
            file("camera", "DCIM/Camera/", 500),
            file("viber", "Pictures/Viber/", 700),
        )
        assertEquals(listOf("wa"), pick(files))
        assertEquals(listOf("business"), pick(files, pkg = "com.whatsapp.w4b", label = "WA Business"))
        assertEquals(listOf("viber"), pick(files, pkg = "com.viber.voip", label = "Viber"))
    }

    @Test
    fun legacyLayout_andOwnerPackage() {
        val files = listOf(
            file("legacy", "/storage/emulated/0/WhatsApp/Media/WhatsApp Images/IMG-1.jpg", 1_000),
            file("legacyBusiness", "/storage/emulated/0/WhatsApp Business/Media/WhatsApp Business Images/IMG-2.jpg", 1_000),
            file("owned", "Pictures/Somewhere/", 3_000, owner = wa),
            file("ownedByOther", "Pictures/WhatsApp/", 3_000, owner = "com.whatsapp.w4b"),
        )
        assertEquals(listOf("legacy", "owned"), pick(files))
    }

    @Test
    fun anotherAppsFolder_isNeverOurs_evenWhenWeOwnTheFile() {
        val files = listOf(
            file("ownBusinessFolder", "Pictures/WhatsApp Business/", 1_000, owner = wa),
            file("ownViberFolder", "Pictures/Viber/", 1_000, owner = wa),
            file("own", "Pictures/WhatsApp/", 2_000, owner = wa),
        )
        assertEquals(listOf("own"), pick(files))
        // Both Telegram builds use the "Telegram" folder: it stays theirs.
        val telegram = listOf(file("tg", "Pictures/Telegram/", 1_000))
        assertEquals(listOf("tg"), pick(telegram, pkg = "org.telegram.messenger.web", label = "Telegram"))
        assertEquals(listOf("tg"), pick(telegram, pkg = "org.telegram.messenger", label = "Telegram"))
    }

    @Test
    fun excludesSentAndStatuses() {
        val files = listOf(
            file("sent", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/", 1_000),
            file("status", "Android/media/com.whatsapp/WhatsApp/Media/.Statuses/", 1_000),
            file("received", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", 9_000),
        )
        assertEquals(listOf("received"), pick(files))
        assertEquals(listOf("received"), pick(files, anyApp = true))
    }

    @Test
    fun timeWindow_andClosestFirst() {
        val dir = "Pictures/Viber/"
        val files = listOf(
            file("tooEarly", dir, -PhoneMediaMatcher.BEFORE_MS - 1),
            file("justBefore", dir, -30_000),
            file("late", dir, 3 * 60 * 60_000L),
            file("closest", dir, 4_000),
            file("tooLate", dir, PhoneMediaMatcher.AFTER_MS + 1),
        )
        assertEquals(listOf("closest", "justBefore", "late"), pick(files, pkg = "com.viber.voip", label = "Viber"))
    }

    @Test
    fun expectedKindFirst() {
        val dir = "Pictures/Viber/"
        val files = listOf(
            file("photo", dir, 1_000),
            file("video", dir, 60_000, kind = PhoneMediaKind.VIDEO),
        )
        assertEquals(listOf("video", "photo"), pick(files, text = "🎥 Video", pkg = "com.viber.voip", label = "Viber"))
        assertEquals(listOf("photo", "video"), pick(files, text = "📷 Photo", pkg = "com.viber.voip", label = "Viber"))
    }

    @Test
    fun anyApp_includesOtherFolders() {
        val files = listOf(file("camera", "DCIM/Camera/", 500), file("wa", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", 2_000))
        assertEquals(listOf("camera", "wa"), pick(files, anyApp = true))
    }

    @Test
    fun shortOrUnknownLabels_doNotMatchEverything() {
        val files = listOf(file("x", "Pictures/X/", 100))
        assertFalse(PhoneMediaMatcher.isFromApp(files[0], "com.example.x", "X"))
        assertTrue(PhoneMediaMatcher.isFromApp(file("s", "Pictures/Session/", 1), "network.loki.messenger", "Session"))
    }

    @Test
    fun expectedKind_fromPlaceholders() {
        assertEquals(PhoneMediaKind.IMAGE, PhoneMediaMatcher.expectedKind("📷 Photo"))
        assertEquals(PhoneMediaKind.VIDEO, PhoneMediaMatcher.expectedKind("🎥 Video"))
        assertEquals(PhoneMediaKind.AUDIO, PhoneMediaMatcher.expectedKind("🎤 Voice message (0:12)"))
        assertEquals(PhoneMediaKind.AUDIO, PhoneMediaMatcher.expectedKind("Glasovna poruka"))
        assertNull(PhoneMediaMatcher.expectedKind("Sent an attachment"))
    }
}
