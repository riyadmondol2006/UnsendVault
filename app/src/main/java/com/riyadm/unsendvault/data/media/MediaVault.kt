package com.riyadm.unsendvault.data.media

import com.riyadm.unsendvault.core.model.MediaLink
import com.riyadm.unsendvault.core.model.MessageMedia
import com.riyadm.unsendvault.data.security.FieldCipher
import com.riyadm.unsendvault.data.security.FieldCipher.Field
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Encrypted media of archived messages, at most two files per message:
 * - `<dir>/<messageId>.media`: a picture copied from the notification or from the phone;
 * - `<dir>/<messageId>.link`: a reference (MediaStore URI + MIME type) to a video/audio file on the phone that the user
 *   linked with "Find on phone". The file itself is never copied.
 *
 * plus `<dir>/<messageId>.removed`, an empty marker left when the user removes a message's picture or link, so the
 * picture is not copied again from the notification (chat apps re-post the same messages with every new one).
 *
 * Kept outside the database so pictures never bloat the WAL, the search scans or the schema (no migration). Each file
 * is one [FieldCipher] blob ([Field.MEDIA_CONTENT] / [Field.MEDIA_LINK], owner [FieldCipher.forMedia]) under the same
 * vault key as the database, so it is as protected as the message text and unreadable once the vault key is gone. The
 * AAD binds the message id, so a file renamed to another id fails authentication.
 *
 * Message ids come from an AUTOINCREMENT primary key and are never reused while the database exists, so a stale file
 * can never be shown for another message; orphans are removed by [prune], and [clear] wipes everything when the
 * archive is deleted or reset. Pure JVM (unit-testable); callers do the I/O off the main thread.
 */
class MediaVault(private val dir: File) {

    fun has(messageId: Long): Boolean = file(messageId, PICTURE).isFile

    fun hasLink(messageId: Long): Boolean = file(messageId, LINK).isFile

    /** True when the user removed this message's picture or link (see [markRemoved]). */
    fun isRemoved(messageId: Long): Boolean = file(messageId, REMOVED).isFile

    /**
     * Deletes the picture and link of [messageId] and remembers that the user removed them, so automatic capture does
     * not bring the picture back while the source notification is still showing. Choosing a file again still works.
     */
    fun markRemoved(messageId: Long) {
        delete(listOf(messageId))
        try {
            if (dir.isDirectory || dir.mkdirs()) file(messageId, REMOVED).createNewFile()
        } catch (e: IOException) {
            // Best effort: without the marker the picture may only be copied again from a still-showing notification.
        } catch (e: SecurityException) {
        }
    }

    /** Ids that have a stored picture, from file names only (no decryption). */
    fun storedIds(): List<Long> = idsWith(PICTURE)

    /** Ids that have a linked phone file. */
    fun linkedIds(): List<Long> = idsWith(LINK)

    /** Encrypts and stores [bytes]; replaces an existing picture atomically. Returns false on I/O failure. */
    fun write(messageId: Long, mimeType: String, bytes: ByteArray, cipher: FieldCipher): Boolean {
        val mime = mimeBytes(mimeType)
        val plain = ByteArray(1 + mime.size + bytes.size)
        plain[0] = mime.size.toByte()
        System.arraycopy(mime, 0, plain, 1, mime.size)
        System.arraycopy(bytes, 0, plain, 1 + mime.size, bytes.size)
        val blob = try {
            cipher.encryptBytes(plain, Field.MEDIA_CONTENT, FieldCipher.forMedia(messageId))
        } finally {
            plain.fill(0)
        }
        return writeAtomically(file(messageId, PICTURE), blob)
    }

    /** The decrypted picture, or null when there is none or it cannot be read/authenticated. */
    fun read(messageId: Long, cipher: FieldCipher): MessageMedia? {
        val blob = readFile(file(messageId, PICTURE)) ?: return null
        val plain = cipher.decryptBytesOrNull(blob, Field.MEDIA_CONTENT, FieldCipher.forMedia(messageId)) ?: return null
        val mimeLength = plain.firstOrNull()?.toInt()?.and(0xFF) ?: return null
        if (plain.size < 1 + mimeLength) return null
        val mime = String(plain, 1, mimeLength, UTF_8)
        val bytes = plain.copyOfRange(1 + mimeLength, plain.size)
        plain.fill(0)
        return MessageMedia(mime, bytes)
    }

    /** Stores the reference to a phone file; replaces an existing one. */
    fun writeLink(messageId: Long, link: MediaLink, cipher: FieldCipher): Boolean {
        if (link.uri.length > MAX_URI || '\n' in link.uri) return false
        val plain = (String(mimeBytes(link.mimeType), UTF_8) + "\n" + link.uri).toByteArray(UTF_8)
        val blob = cipher.encryptBytes(plain, Field.MEDIA_LINK, FieldCipher.forMedia(messageId))
        return writeAtomically(file(messageId, LINK), blob)
    }

    fun readLink(messageId: Long, cipher: FieldCipher): MediaLink? {
        val blob = readFile(file(messageId, LINK)) ?: return null
        val plain = cipher.decryptOrNull(blob, Field.MEDIA_LINK, FieldCipher.forMedia(messageId)) ?: return null
        val split = plain.indexOf('\n')
        if (split < 0 || split == plain.length - 1) return null
        return MediaLink(uri = plain.substring(split + 1), mimeType = plain.substring(0, split))
    }

    /** Deletes the picture, the link and the removed marker of each id. */
    fun delete(ids: Collection<Long>) {
        ids.forEach {
            file(it, PICTURE).delete()
            file(it, LINK).delete()
            file(it, REMOVED).delete()
        }
    }

    /** Deletes every file whose message no longer exists. [existing] returns which of the given ids still exist. */
    suspend fun prune(existing: suspend (List<Long>) -> Collection<Long>) {
        dir.listFiles()?.filter { it.name.endsWith(TMP) }?.forEach { it.delete() }
        val stored = (idsWith(PICTURE) + idsWith(LINK) + idsWith(REMOVED)).distinct()
        if (stored.isEmpty()) return
        stored.chunked(CHUNK).forEach { chunk ->
            val keep = existing(chunk).toSet()
            delete(chunk.filter { it !in keep })
        }
    }

    /** Removes every stored picture and link (archive deleted or vault reset). */
    fun clear() {
        dir.deleteRecursively()
    }

    private fun idsWith(suffix: String): List<Long> =
        dir.listFiles()?.mapNotNull { f -> f.name.takeIf { it.endsWith(suffix) }?.removeSuffix(suffix)?.toLongOrNull() }
            ?: emptyList()

    private fun mimeBytes(mimeType: String): ByteArray =
        mimeType.filter { it != '\n' }.take(MAX_MIME).toByteArray(UTF_8).let { if (it.size > MAX_MIME) it.copyOf(MAX_MIME) else it }

    private fun writeAtomically(target: File, blob: ByteArray): Boolean = try {
        if (!dir.isDirectory && !dir.mkdirs()) {
            false
        } else {
            val tmp = File(dir, target.name + TMP)
            tmp.writeBytes(blob)
            if (tmp.renameTo(target)) {
                true
            } else {
                tmp.delete()
                false
            }
        }
    } catch (e: IOException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun readFile(file: File): ByteArray? {
        if (!file.isFile) return null
        return try {
            file.readBytes()
        } catch (e: IOException) {
            null
        }
    }

    private fun file(messageId: Long, suffix: String) = File(dir, "$messageId$suffix")

    companion object {
        const val DIR_NAME = "media"
        private const val PICTURE = ".media"
        private const val LINK = ".link"
        private const val TMP = ".tmp"
        private const val REMOVED = ".removed"
        private const val MAX_MIME = 100
        private const val MAX_URI = 2_000
        private const val CHUNK = 500
    }
}
