package com.riyadm.unsendvault.ui.media

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

/** What a chat bubble shows for a media message: a saved picture, a linked phone file, or both (video + preview). */
@Immutable
class MessageAttachment(val picture: ImageBitmap?, val linked: LinkedFile?)

/** A phone file linked with "Find on phone". [available] is false once the file was deleted from the phone. */
@Immutable
data class LinkedFile(
    val uri: String,
    val mimeType: String,
    val kind: PhoneMediaKind,
    val thumbnail: ImageBitmap?,
    val available: Boolean,
)

fun kindOfMime(mimeType: String): PhoneMediaKind = when {
    mimeType.startsWith("video/", ignoreCase = true) -> PhoneMediaKind.VIDEO
    mimeType.startsWith("audio/", ignoreCase = true) -> PhoneMediaKind.AUDIO
    else -> PhoneMediaKind.IMAGE
}
