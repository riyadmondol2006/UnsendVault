package com.riyadm.unsendvault.ui.media

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.BaseColumns
import android.provider.MediaStore
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.riyadm.unsendvault.ui.components.PictureDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Reads media files the chat apps saved on the phone (MediaStore), for "Find on phone". Needs the media read
 * permission, which is requested only when the user taps "Find on phone"; without it every method returns nothing.
 * Never writes, moves or deletes anything. All I/O runs on [Dispatchers.IO]; no method throws.
 */
class PhoneMediaFinder(context: Context) {
    private val app = context.applicationContext
    private val resolver: ContentResolver get() = app.contentResolver

    /** Runtime permissions to request (any one of them is enough to list at least some files). */
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasAccess(): Boolean =
        permissions().any { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED }

    /** Android 14+: the user allowed only some photos/videos ("Select photos"), so the list may be incomplete. */
    fun hasPartialAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        fun granted(p: String) = ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED
        return granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) &&
            !granted(Manifest.permission.READ_MEDIA_IMAGES) && !granted(Manifest.permission.READ_MEDIA_VIDEO)
    }

    /** Files offered for a media message; see [PhoneMediaMatcher]. */
    suspend fun candidates(
        packageName: String,
        appLabel: String,
        messageTimestamp: Long,
        messageText: String,
        anyApp: Boolean,
    ): List<PhoneMediaFile> = withContext(Dispatchers.IO) {
        if (!hasAccess()) return@withContext emptyList()
        val rows = query(
            fromSeconds = (messageTimestamp - PhoneMediaMatcher.BEFORE_MS) / 1000,
            toSeconds = (messageTimestamp + PhoneMediaMatcher.AFTER_MS) / 1000 + 1,
        )
        PhoneMediaMatcher.candidates(rows, packageName, appLabel, messageTimestamp, messageText, anyApp)
    }

    private fun query(fromSeconds: Long, toSeconds: Long): List<PhoneMediaFile> {
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.MediaColumns.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            MediaStore.MediaColumns.DATA
        }
        val projection = buildList {
            add(BaseColumns._ID)
            add(MediaStore.Files.FileColumns.MEDIA_TYPE)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(pathColumn)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
        }.toTypedArray()
        val type = MediaStore.Files.FileColumns.MEDIA_TYPE
        val selection = "$type IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, " +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO}) " +
            "AND ${MediaStore.MediaColumns.DATE_ADDED} >= ? AND ${MediaStore.MediaColumns.DATE_ADDED} <= ?"
        val out = ArrayList<PhoneMediaFile>()
        try {
            resolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                selection,
                arrayOf(fromSeconds.toString(), toSeconds.toString()),
                "${MediaStore.MediaColumns.DATE_ADDED} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < MAX_ROWS) row(c, pathColumn)?.let(out::add)
            }
        } catch (e: SecurityException) {
            return emptyList()
        } catch (e: IllegalArgumentException) {
            return emptyList()
        } catch (e: IllegalStateException) {
            return emptyList()
        }
        return out
    }

    private fun row(c: Cursor, pathColumn: String): PhoneMediaFile? {
        val id = c.getLong(c.getColumnIndexOrThrow(BaseColumns._ID))
        val (kind, collection) = when (c.getInt(c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE))) {
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> PhoneMediaKind.IMAGE to MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> PhoneMediaKind.VIDEO to MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> PhoneMediaKind.AUDIO to MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> return null
        }
        val ownerIndex = c.getColumnIndex(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
        val defaultMime = when (kind) {
            PhoneMediaKind.IMAGE -> "image/*"
            PhoneMediaKind.VIDEO -> "video/*"
            PhoneMediaKind.AUDIO -> "audio/*"
        }
        return PhoneMediaFile(
            uri = ContentUris.withAppendedId(collection, id).toString(),
            kind = kind,
            mimeType = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)) ?: defaultMime,
            addedAt = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)) * 1000,
            path = c.getString(c.getColumnIndexOrThrow(pathColumn)).orEmpty(),
            ownerPackage = if (ownerIndex >= 0) c.getString(ownerIndex) else null,
            name = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)).orEmpty(),
        )
    }

    /** True while the file is still on the phone and readable. */
    suspend fun exists(uri: String): Boolean = withContext(Dispatchers.IO) {
        try {
            resolver.query(uri.toUri(), arrayOf(BaseColumns._ID), null, null, null)?.use { it.count > 0 } ?: false
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: IllegalStateException) {
            false
        }
    }

    /** Small preview of a picture or video (album art for audio, when there is any). */
    suspend fun thumbnail(uri: String, kind: PhoneMediaKind): ImageBitmap? = withContext(Dispatchers.IO) {
        try {
            val parsed = uri.toUri()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.loadThumbnail(parsed, Size(THUMB_PX, THUMB_PX), null).asImageBitmap()
            } else {
                when (kind) {
                    PhoneMediaKind.IMAGE -> readBytes(parsed, MAX_THUMB_SOURCE)?.let { PictureDecoder.decode(it, THUMB_PX) }
                    PhoneMediaKind.VIDEO -> {
                        @Suppress("DEPRECATION")
                        MediaStore.Video.Thumbnails.getThumbnail(
                            resolver,
                            ContentUris.parseId(parsed),
                            MediaStore.Video.Thumbnails.MINI_KIND,
                            null,
                        )?.asImageBitmap()
                    }
                    PhoneMediaKind.AUDIO -> null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // no thumbnail (audio without art, file gone, no permission)
        }
    }

    /** Bytes of [uri], or null when unreadable or larger than [maxBytes]. */
    suspend fun read(uri: String, maxBytes: Int): ByteArray? = withContext(Dispatchers.IO) {
        try {
            readBytes(uri.toUri(), maxBytes)
        } catch (e: SecurityException) {
            null
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun readBytes(uri: Uri, maxBytes: Int): ByteArray? = resolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
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

    /** Opens a linked file in the phone's own player/viewer. False when no app can open it. */
    fun open(context: Context, uri: String, mimeType: String): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri.toUri(), mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    companion object {
        private const val MAX_ROWS = 2_000
        private const val THUMB_PX = 320
        private const val MAX_THUMB_SOURCE = 30 * 1024 * 1024
    }
}
