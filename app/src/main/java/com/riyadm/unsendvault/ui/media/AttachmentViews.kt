package com.riyadm.unsendvault.ui.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.ui.components.MessagePicture

/** The picture and/or linked phone file of a media message, inside its chat bubble. */
@Composable
fun MessageAttachmentView(
    attachment: MessageAttachment,
    onOpenLinked: (LinkedFile) -> Unit,
    onRemove: () -> Unit,
) {
    val linked = attachment.linked
    if (linked == null) {
        attachment.picture?.let { MessagePicture(it, onRemove = onRemove) }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            !linked.available -> {
                attachment.picture?.let { MessagePicture(it) }
                Text(
                    stringResource(R.string.media_link_missing),
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            linked.kind == PhoneMediaKind.AUDIO -> AudioRow { onOpenLinked(linked) }
            else -> PreviewTile(
                preview = attachment.picture ?: linked.thumbnail,
                isVideo = linked.kind == PhoneMediaKind.VIDEO,
                onClick = { onOpenLinked(linked) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.PhoneAndroid,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.media_from_phone),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
            TextButton(onClick = onRemove) {
                Icon(Icons.Outlined.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.action_unlink), modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

@Composable
private fun PreviewTile(preview: ImageBitmap?, isVideo: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (isVideo) R.string.action_play_video else R.string.action_view_picture)
    Box(
        Modifier
            .widthIn(min = 160.dp, max = 280.dp)
            .heightIn(min = 100.dp, max = 320.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (preview != null) {
            Image(bitmap = preview, contentDescription = label, contentScale = ContentScale.Fit)
        } else {
            Icon(Icons.Outlined.Movie, contentDescription = label, modifier = Modifier.size(48.dp))
        }
        if (isVideo) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).padding(8.dp),
            )
        }
    }
}

@Composable
private fun AudioRow(onPlay: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.AudioFile, contentDescription = null, modifier = Modifier.size(28.dp))
        Text(
            stringResource(R.string.media_audio_file),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        TextButton(onClick = onPlay) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.action_play), modifier = Modifier.padding(start = 4.dp))
        }
    }
}
