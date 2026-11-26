package com.riyadm.unsendvault.capture

import android.content.ContentResolver
import android.net.Uri
import androidx.core.net.toUri
import com.riyadm.unsendvault.data.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Copies the pictures chat apps attach to MessagingStyle messages into the encrypted media store (opt-in,
 * [com.riyadm.unsendvault.core.model.VaultSettings.savePictures]).
 *
 * Android 11+ grants notification listeners temporary read access to those URIs only while the notification is
 * showing, so this runs right after the message is archived. Only `image/` types are copied: that is all the system
 * itself renders in a messaging notification, and chat apps never attach full videos, voice messages or documents.
 * Anything unreadable (no grant, the app revoked it, the notification is gone, too large) is skipped silently and the
 * message keeps its "📷 Photo" placeholder. Never throws.
 */
class MediaCapture(
    private val resolver: ContentResolver,
    private val vault: () -> VaultRepository,
    private val maxBytes: Int = MAX_BYTES,
) {
    suspend fun copy(refs: List<IngestResult.MediaRef>) {
        for (ref in refs) {
            if (!isSupported(ref.mimeType)) continue
            try {
                val repo = vault()
                // Already kept, or the user removed it: chat apps re-post the same messages with every new one.
                if (repo.hasMedia(ref.messageId) || repo.isMediaRemoved(ref.messageId)) continue
                val bytes = withContext(Dispatchers.IO) { read(ref.uri.toUri()) } ?: continue
                if (repo.saveMedia(ref.messageId, ref.mimeType, bytes)) {
                    CaptureLog.d("picture saved (${bytes.size / 1024} KiB)")
                }
                bytes.fill(0)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                CaptureLog.d("picture not saved: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Bytes behind [uri], or null when it cannot be read or exceeds [maxBytes]. */
    private fun read(uri: Uri): ByteArray? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        return try {
            resolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return null
                    out.write(buffer, 0, n)
                }
                out.toByteArray().takeIf { it.isNotEmpty() }
            }
        } catch (e: SecurityException) {
            null // no (or no longer a) URI grant
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IllegalStateException) {
            null
        }
    }

    companion object {
        /** Notification pictures are previews; anything bigger is not something a chat app put in a notification. */
        const val MAX_BYTES = 15 * 1024 * 1024
        private const val BUFFER = 64 * 1024

        fun isSupported(mimeType: String): Boolean = mimeType.startsWith("image/", ignoreCase = true)
    }
}
