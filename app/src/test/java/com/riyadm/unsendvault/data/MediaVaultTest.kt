package com.riyadm.unsendvault.data

import com.riyadm.unsendvault.core.model.MediaLink
import com.riyadm.unsendvault.data.media.MediaVault
import com.riyadm.unsendvault.data.security.FieldCipher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Plain JVM tests of the encrypted picture store. */
class MediaVaultTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val cipher = FieldCipher.random()
    private val data = ByteArray(300_000) { (it * 7).toByte() }

    @Test
    fun roundTrip_keepsMimeAndBytes_andNothingReadableOnDisk() {
        val dir = tmp.newFolder("media")
        val vault = MediaVault(dir)
        assertTrue(vault.write(42, "image/jpeg", data, cipher))
        assertTrue(vault.has(42))
        val read = vault.read(42, cipher)!!
        assertEquals("image/jpeg", read.mimeType)
        assertArrayEquals(data, read.bytes)
        val raw = String(File(dir, "42.media").readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains("image/jpeg"))
    }

    @Test
    fun fileMovedToAnotherMessage_orOtherKey_isRejected() {
        val dir = tmp.newFolder("media")
        val vault = MediaVault(dir)
        vault.write(42, "image/png", data, cipher)
        File(dir, "42.media").copyTo(File(dir, "43.media"))
        assertNull(vault.read(43, cipher))
        assertNull(vault.read(42, FieldCipher.random()))
    }

    @Test
    fun markRemoved_deletesFiles_andIsForgottenWithTheMessage() = runBlocking {
        val dir = tmp.newFolder("media")
        val vault = MediaVault(dir)
        vault.write(5, "image/png", data, cipher)
        vault.writeLink(5, MediaLink("content://media/external/video/media/1", "video/mp4"), cipher)
        vault.markRemoved(5)
        assertFalse(vault.has(5))
        assertFalse(vault.hasLink(5))
        assertTrue(vault.isRemoved(5))
        // A file chosen again later is still kept.
        assertTrue(vault.write(5, "image/png", data, cipher))
        assertTrue(vault.has(5))
        vault.prune { emptyList() }
        assertFalse(vault.isRemoved(5))
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun prune_keepsOnlyExistingMessages_andDropsTempFiles() = runBlocking {
        val dir = tmp.newFolder("media")
        val vault = MediaVault(dir)
        vault.write(1, "image/png", data, cipher)
        vault.write(2, "image/png", data, cipher)
        File(dir, "3.media.tmp").writeText("partial")
        vault.prune { ids -> ids.filter { it == 2L } }
        assertEquals(listOf(2L), vault.storedIds())
        assertFalse(File(dir, "3.media.tmp").exists())
    }

    @Test
    fun link_roundTrip_independentOfPicture_andDeletedTogether() = runBlocking {
        val dir = tmp.newFolder("media")
        val vault = MediaVault(dir)
        val link = MediaLink("content://media/external/video/media/77", "video/mp4")
        assertTrue(vault.writeLink(5, link, cipher))
        assertTrue(vault.write(5, "image/jpeg", data, cipher))
        assertEquals(link, vault.readLink(5, cipher))
        assertTrue(vault.has(5) && vault.hasLink(5))
        assertNull(vault.readLink(5, FieldCipher.random()))
        File(dir, "5.link").copyTo(File(dir, "6.link"))
        assertNull(vault.readLink(6, cipher))
        vault.delete(listOf(6L))

        vault.writeLink(9, link, cipher)
        vault.prune { ids -> ids.filter { it == 5L } }
        assertEquals(listOf(5L), vault.linkedIds())
        vault.delete(listOf(5L))
        assertFalse(vault.has(5) || vault.hasLink(5))
    }

    @Test
    fun link_withNewline_isRefused() {
        val vault = MediaVault(tmp.newFolder("media"))
        assertFalse(vault.writeLink(1, MediaLink("content://a\nb", "video/mp4"), cipher))
    }

    @Test
    fun clear_removesEverything() {
        val dir = tmp.newFolder("media")
        val vault = MediaVault(dir)
        vault.write(1, "image/png", data, cipher)
        vault.clear()
        assertTrue(vault.storedIds().isEmpty())
        assertFalse(vault.has(1))
    }
}
