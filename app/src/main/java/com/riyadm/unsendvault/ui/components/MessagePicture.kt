package com.riyadm.unsendvault.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.riyadm.unsendvault.R

/** Decodes a saved notification picture, downscaled so large images never exhaust memory. */
object PictureDecoder {
    /** Longest side after decoding; enough for full-screen viewing on phones and tablets. */
    private const val MAX_SIDE = 2048

    fun decode(bytes: ByteArray, maxSide: Int = MAX_SIDE): ImageBitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
        }
    } catch (e: OutOfMemoryError) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}

/** A saved picture inside a chat bubble; tapping it opens [PictureViewer]. */
@Composable
fun MessagePicture(picture: ImageBitmap, modifier: Modifier = Modifier, onRemove: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.cd_saved_picture)
    Image(
        bitmap = picture,
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .widthIn(max = 280.dp)
            .heightIn(max = 320.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Image, onClickLabel = stringResource(R.string.action_view_picture)) { open = true },
    )
    if (open) {
        PictureViewer(
            picture,
            description,
            onDismiss = { open = false },
            onRemove = onRemove?.let { remove -> { open = false; remove() } },
        )
    }
}

/**
 * Full-screen view of a saved picture. The dialog window inherits FLAG_SECURE from the activity (default
 * SecureFlagPolicy.Inherit), so "Secure screen" still blocks screenshots here.
 */
@Composable
fun PictureViewer(picture: ImageBitmap, description: String, onDismiss: () -> Unit, onRemove: (() -> Unit)? = null) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = picture,
                contentDescription = description,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close), tint = Color.White)
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.action_remove_picture), tint = Color.White)
                }
            }
        }
    }
}
