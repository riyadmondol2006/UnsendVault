package com.riyadm.unsendvault.ui.media

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.core.model.ArchivedMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * "Find on phone": lists the files the chat app saved around the time of [message] and lets the user pick the one
 * that belongs to it. Selecting a tile only highlights it; nothing is attached until "Use this file" is pressed.
 */
@Composable
fun PhoneMediaPickerDialog(
    message: ArchivedMessage,
    partialAccess: Boolean,
    loadCandidates: suspend (anyApp: Boolean) -> List<PhoneMediaFile>,
    loadThumbnail: suspend (PhoneMediaFile) -> ImageBitmap?,
    onPick: (PhoneMediaFile) -> Unit,
    onDismiss: () -> Unit,
) {
    var anyApp by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<PhoneMediaFile?>(null) }
    var loading by remember { mutableStateOf(true) }
    var files by remember { mutableStateOf<List<PhoneMediaFile>>(emptyList()) }
    LaunchedEffect(anyApp) {
        loading = true
        selected = null
        files = loadCandidates(anyApp)
        loading = false
    }
    val timeFormat = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.find_on_phone_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.find_on_phone_intro,
                        message.appLabel,
                        timeFormat.format(Instant.ofEpochMilli(message.messageTimestamp)),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (partialAccess) {
                    Text(
                        stringResource(R.string.find_on_phone_partial),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Up to 320 dp, less when the dialog is short (landscape), so the checkbox below always stays visible.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(min = 96.dp, max = 320.dp)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        loading -> CircularProgressIndicator()
                        files.isEmpty() -> Text(
                            stringResource(if (anyApp) R.string.find_on_phone_empty_all else R.string.find_on_phone_empty, message.appLabel),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        else -> LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(files, key = { it.uri }) { file ->
                                CandidateTile(
                                    file = file,
                                    time = timeFormat.format(Instant.ofEpochMilli(file.addedAt)),
                                    isSelected = file == selected,
                                    loadThumbnail = loadThumbnail,
                                    onClick = { selected = if (selected == file) null else file },
                                )
                            }
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { anyApp = !anyApp },
                ) {
                    Checkbox(checked = anyApp, onCheckedChange = null)
                    Text(
                        stringResource(R.string.find_on_phone_all_apps),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected != null, onClick = { selected?.let(onPick) }) {
                Text(stringResource(R.string.find_on_phone_use))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun CandidateTile(
    file: PhoneMediaFile,
    time: String,
    isSelected: Boolean,
    loadThumbnail: suspend (PhoneMediaFile) -> ImageBitmap?,
    onClick: () -> Unit,
) {
    val thumbnail by produceState<ImageBitmap?>(null, file.uri) { value = loadThumbnail(file) }
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .semantics { selected = isSelected }
            .clickable(role = Role.Button, onClickLabel = file.name, onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surfaceContainerHighest)
                .then(if (isSelected) Modifier.border(BorderStroke(3.dp, colors.primary), RoundedCornerShape(8.dp)) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            val thumb = thumbnail
            if (thumb != null) {
                Image(thumb, contentDescription = file.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(
                    if (file.kind == PhoneMediaKind.AUDIO) Icons.Outlined.AudioFile else Icons.Outlined.Image,
                    contentDescription = file.name,
                    modifier = Modifier.size(32.dp),
                )
            }
            if (file.kind == PhoneMediaKind.VIDEO) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.5f)),
                )
            }
            if (isSelected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).background(colors.surface, RoundedCornerShape(11.dp)),
                )
            }
        }
        Text(
            time,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = colors.onSurfaceVariant,
        )
    }
}
