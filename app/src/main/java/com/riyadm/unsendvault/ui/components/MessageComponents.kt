package com.riyadm.unsendvault.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.core.model.ArchivedMessage
import com.riyadm.unsendvault.core.model.ContentKind
import com.riyadm.unsendvault.core.model.MessageStatus

/** Small labelled badge: icon + text, so state is never conveyed by color alone. */
@Composable
fun StatusBadge(icon: ImageVector, text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun MessageBadges(
    message: ArchivedMessage,
    modifier: Modifier = Modifier,
    pictureSaved: Boolean = false,
    linkedFromPhone: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val hasAny = message.status == MessageStatus.POSSIBLY_DELETED || message.revisionCount > 1 ||
        message.contentKind != ContentKind.TEXT
    if (!hasAny) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (message.status == MessageStatus.POSSIBLY_DELETED) {
            StatusBadge(Icons.Outlined.EventBusy, stringResource(R.string.badge_possibly_deleted), colors.tertiaryContainer, colors.onTertiaryContainer)
        }
        if (message.revisionCount > 1) {
            StatusBadge(
                Icons.Outlined.History,
                pluralStringResource(R.plurals.badge_versions, message.revisionCount, message.revisionCount),
                colors.secondaryContainer, colors.onSecondaryContainer,
            )
        }
        when (message.contentKind) {
            ContentKind.MEDIA_PLACEHOLDER -> StatusBadge(
                Icons.Outlined.Image,
                stringResource(
                    when {
                        linkedFromPhone -> R.string.badge_linked_from_phone
                        pictureSaved -> R.string.badge_picture_saved
                        else -> R.string.badge_media
                    },
                ),
                colors.surfaceContainerHighest,
                colors.onSurfaceVariant,
            )
            ContentKind.SYSTEM_REDACTED ->
                StatusBadge(Icons.Outlined.VisibilityOff, stringResource(R.string.badge_hidden_by_android), colors.surfaceContainerHighest, colors.onSurfaceVariant)
            ContentKind.FILTERED_SENSITIVE ->
                StatusBadge(Icons.Outlined.Shield, stringResource(R.string.badge_filtered), colors.surfaceContainerHighest, colors.onSurfaceVariant)
            ContentKind.TEXT -> Unit
        }
    }
}

/** Text to show for a message body; non-text kinds fall back to an explanatory label when the stored text is blank. */
@Composable
fun messageBodyText(message: ArchivedMessage): String = when {
    message.text.isNotBlank() -> message.text
    message.contentKind == ContentKind.MEDIA_PLACEHOLDER -> stringResource(R.string.kind_media_short)
    message.contentKind == ContentKind.SYSTEM_REDACTED -> stringResource(R.string.kind_redacted_short)
    message.contentKind == ContentKind.FILTERED_SENSITIVE -> stringResource(R.string.kind_filtered_short)
    else -> ""
}

/**
 * Highlights every case-insensitive occurrence of [query] in [text] with bold + underline + container color, so the
 * match is visible without relying on color.
 */
fun highlightMatches(text: String, query: String?, highlight: SpanStyle): AnnotatedString {
    val needle = query?.trim().orEmpty()
    if (needle.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var start = 0
        while (start < text.length) {
            val index = text.indexOf(needle, start, ignoreCase = true)
            if (index < 0) break
            append(text, start, index)
            withStyle(highlight) { append(text, index, index + needle.length) }
            start = index + needle.length
        }
        if (start < text.length) append(text, start, text.length)
    }
}

@Composable
fun rememberHighlightStyle(): SpanStyle {
    val colors = MaterialTheme.colorScheme
    return remember(colors.secondaryContainer, colors.onSecondaryContainer) {
        SpanStyle(
            background = colors.secondaryContainer,
            color = colors.onSecondaryContainer,
            fontWeight = FontWeight.Bold,
            textDecoration = TextDecoration.Underline,
        )
    }
}

@Immutable
data class MessageRowConfig(
    val showConversation: Boolean = true,
    val showApp: Boolean = true,
    val highlight: String? = null,
    val maxLines: Int = 3,
)

/** Content of a list row for one archived message (the caller provides click handling / selection). */
@Composable
fun MessageRowContent(
    message: ArchivedMessage,
    timeLabel: String,
    config: MessageRowConfig,
    modifier: Modifier = Modifier,
) {
    val highlightStyle = rememberHighlightStyle()
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        if (config.showApp) {
            AppAvatar(message.packageName, message.appLabel)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val title = if (config.showConversation) message.conversationTitle else message.sender ?: message.conversationTitle
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        highlightMatches(title, config.highlight, highlightStyle),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (message.isFavorite) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = stringResource(R.string.cd_favorite),
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    timeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (config.showApp) {
                Text(
                    message.appLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val body = messageBodyText(message)
            val sender = message.sender
            val prefix = if (config.showConversation && sender != null && sender != message.conversationTitle) "$sender: " else ""
            Text(
                highlightMatches(prefix + body, config.highlight, highlightStyle),
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = if (message.contentKind == ContentKind.TEXT) FontStyle.Normal else FontStyle.Italic,
                maxLines = config.maxLines,
                overflow = TextOverflow.Ellipsis,
            )
            MessageBadges(message, Modifier.padding(top = 4.dp))
        }
    }
}
