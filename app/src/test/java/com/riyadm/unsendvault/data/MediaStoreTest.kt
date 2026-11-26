package com.riyadm.unsendvault.data

import com.riyadm.unsendvault.core.model.ContentKind
import com.riyadm.unsendvault.core.model.MediaLink
import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.data.Fx.T0
import com.riyadm.unsendvault.data.Fx.msg
import com.riyadm.unsendvault.data.Fx.parsed
import com.riyadm.unsendvault.data.media.MediaVault
import com.riyadm.unsendvault.data.repo.RoomVaultRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pictures of media messages: candidates from ingest, encrypted storage, and cleanup when messages go away. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaStoreTest : RoomTestBase() {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var media: MediaVault
    private lateinit var mediaRepo: RoomVaultRepository

    @Before
    fun openMedia() {
        media = MediaVault(tmp.newFolder("media"))
        mediaRepo = RoomVaultRepository(db, media)
    }

    private fun photo(ts: Long = T0, uri: String? = "content://chat/media/$ts", kind: ContentKind = ContentKind.MEDIA_PLACEHOLDER) =
        msg("📷 Photo", ts = ts, kind = kind).copy(mediaUri = uri, mediaMimeType = uri?.let { "image/jpeg" })

    private val bytes = ByteArray(4_096) { it.toByte() }

    @Test
    fun ingest_reportsMediaCandidates_forNewAndKnownRows() = runBlocking {
        val first = mediaRepo.ingest(parsed(photo(), msg("hi", ts = T0 + 1)), T0)
        val ref = first.mediaCandidates.single()
        assertEquals(first.newMessages.first { it.message.mediaUri != null }.message.mediaUri, ref.uri)
        assertEquals("image/jpeg", ref.mimeType)
        // A re-post of the same message (picture not yet copied) offers it again, for the same row.
        val again = mediaRepo.ingest(parsed(photo(), msg("hi", ts = T0 + 1)), T0 + 1_000)
        assertEquals(listOf(ref.messageId), again.mediaCandidates.map { it.messageId })
    }

    @Test
    fun ingest_noCandidate_withoutUri_orForFilteredText() = runBlocking {
        assertTrue(mediaRepo.ingest(parsed(photo(uri = null)), T0).mediaCandidates.isEmpty())
        val filtered = photo(ts = T0 + 5, kind = ContentKind.FILTERED_SENSITIVE)
        assertTrue(mediaRepo.ingest(parsed(filtered), T0).mediaCandidates.isEmpty())
    }

    @Test
    fun saveAndLoad_roundTrip_encryptedOnDisk() = runBlocking {
        val id = mediaRepo.ingest(parsed(photo()), T0).mediaCandidates.single().messageId
        assertFalse(mediaRepo.hasMedia(id))
        assertTrue(mediaRepo.saveMedia(id, "image/jpeg", bytes))
        assertTrue(mediaRepo.hasMedia(id))
        val loaded = mediaRepo.loadMedia(id)!!
        assertEquals("image/jpeg", loaded.mimeType)
        assertArrayEquals(bytes, loaded.bytes)
        assertFalse(media.storedIds().isEmpty())
    }

    @Test
    fun save_forUnknownMessage_isRefused() = runBlocking {
        assertFalse(mediaRepo.saveMedia(9_999, "image/png", bytes))
        assertTrue(media.storedIds().isEmpty())
    }

    @Test
    fun deletes_removeTheirPictures() = runBlocking {
        val a = mediaRepo.ingest(parsed(photo(ts = T0)), T0).mediaCandidates.single().messageId
        val b = mediaRepo.ingest(parsed(photo(ts = T0 + 20 * Fx.MIN)), T0).mediaCandidates.single().messageId
        mediaRepo.saveMedia(a, "image/jpeg", bytes)
        mediaRepo.saveMedia(b, "image/jpeg", bytes)

        mediaRepo.deleteMessages(listOf(a))
        assertEquals(listOf(b), media.storedIds())
        assertNull(mediaRepo.loadMedia(a))

        val conversationId = mediaRepo.observeMessages(MessageQuery()).first().single().conversationId
        mediaRepo.deleteConversation(conversationId)
        assertTrue(media.storedIds().isEmpty())
    }

    @Test
    fun retentionAndDeleteAll_removePictures() = runBlocking {
        val old = mediaRepo.ingest(parsed(photo(ts = T0)), T0).mediaCandidates.single().messageId
        val recent = mediaRepo.ingest(parsed(photo(ts = T0 + 20 * Fx.MIN)), T0).mediaCandidates.single().messageId
        mediaRepo.saveMedia(old, "image/jpeg", bytes)
        mediaRepo.saveMedia(recent, "image/jpeg", bytes)

        assertEquals(1, mediaRepo.deleteOlderThan(T0 + 1))
        assertEquals(listOf(recent), media.storedIds())

        mediaRepo.deleteAll()
        assertTrue(media.storedIds().isEmpty())
    }

    @Test
    fun links_saveLoadRemove_andPrunedWithTheMessage() = runBlocking {
        mediaRepo.ingest(parsed(photo(uri = null)), T0)
        val id = mediaRepo.observeMessages(MessageQuery()).first().single().id
        val link = MediaLink("content://media/external/video/media/12", "video/mp4")
        assertFalse(mediaRepo.saveMediaLink(9_999, link))
        assertTrue(mediaRepo.saveMediaLink(id, link))
        assertEquals(link, mediaRepo.loadMediaLink(id))

        assertFalse(mediaRepo.isMediaRemoved(id))
        mediaRepo.removeMedia(id)
        assertNull(mediaRepo.loadMediaLink(id))
        // Remembered, so a still-showing notification does not bring the picture back.
        assertTrue(mediaRepo.isMediaRemoved(id))

        mediaRepo.saveMediaLink(id, link)
        mediaRepo.deleteMessages(listOf(id))
        assertTrue(media.linkedIds().isEmpty())
    }
}
