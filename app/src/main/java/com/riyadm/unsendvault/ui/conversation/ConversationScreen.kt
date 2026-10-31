package com.riyadm.unsendvault.ui.conversation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.core.model.ArchivedMessage
import com.riyadm.unsendvault.core.model.ContentKind
import com.riyadm.unsendvault.reply.ReplyAvailability
import com.riyadm.unsendvault.reply.ReplyResult
import com.riyadm.unsendvault.ui.common.LocalScreenInsets
import com.riyadm.unsendvault.ui.common.TimeFormatter
import com.riyadm.unsendvault.ui.common.rememberTimeFormatter
import com.riyadm.unsendvault.ui.components.AppScaffold
import com.riyadm.unsendvault.ui.components.BackButton
import com.riyadm.unsendvault.ui.components.ConfirmDialog
import com.riyadm.unsendvault.ui.components.EmptyState
import com.riyadm.unsendvault.ui.components.MessageBadges
import com.riyadm.unsendvault.ui.components.messageBodyText

object ConversationTestTags {
    const val LIST = "conversation_list"
    const val DELETE_SELECTED = "conversation_delete_selected"
    const val REPLY_FIELD = "conversation_reply_field"
    const val REPLY_SEND = "conversation_reply_send"
    const val REPLY_UNAVAILABLE = "conversation_reply_unavailable"
    fun message(id: Long) = "conversation_message_$id"
}

@Composable
fun ConversationRoute(
    viewModel: ConversationViewModel,
    onBack: (() -> Unit)?,
    onClosed: () -> Unit,
    onOpenMessage: (Long) -> Unit,
    /** Called with true while the chat is resumed on screen, false when it leaves (saved-message notifications). */
    onVisibleChanged: (Boolean) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val context = LocalContext.current
    var replyDraft by rememberSaveable { mutableStateOf("") }
    LifecycleResumeEffect(viewModel) {
        onVisibleChanged(true)
        viewModel.refreshReply()
        onPauseOrDispose { onVisibleChanged(false) }
    }
    val appLabel = state.conversation?.appLabel.orEmpty()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ConversationEvent.ConversationDeleted -> onClosed()
                is ConversationEvent.MessagesDeleted -> snackbar.showSnackbar(
                    resources.getQuantityString(R.plurals.messages_deleted, event.count, event.count),
                )
                is ConversationEvent.Reply -> when (event.result) {
                    ReplyResult.SENT -> {
                        replyDraft = ""
                        snackbar.showSnackbar(resources.getString(R.string.reply_sent, appLabel))
                    }
                    ReplyResult.UNAVAILABLE -> snackbar.showSnackbar(resources.getString(R.string.reply_gone, appLabel))
                    ReplyResult.FAILED -> snackbar.showSnackbar(resources.getString(R.string.reply_failed, appLabel))
                    ReplyResult.EMPTY -> Unit
                }
            }
        }
    }
    val packageName = state.conversation?.packageName
    val launchIntent = remember(packageName) {
        packageName?.let { pkg ->
            try {
                context.packageManager.getLaunchIntentForPackage(pkg)
            } catch (e: Exception) {
                null
            }
        }
    }
    ConversationScreen(
        replyDraft = replyDraft,
        onReplyDraftChange = { replyDraft = it },
        onSendReply = { viewModel.sendReply(replyDraft) },
        onOpenSourceApp = launchIntent?.let { intent ->
            {
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    // App vanished or blocked its launcher activity; nothing else to do.
                }
            }
        },
        state = state,
        snackbarHostState = snackbar,
        onBack = onBack,
        onOpenMessage = onOpenMessage,
        onToggleSelection = viewModel::toggleSelection,
        onSelectAll = viewModel::selectAll,
        onClearSelection = viewModel::clearSelection,
        onDeleteSelected = viewModel::deleteSelected,
        onDeleteConversation = viewModel::deleteConversation,
    )
}

@Composable
fun ConversationScreen(
    state: ConversationUiState,
    onBack: (() -> Unit)?,
    onOpenMessage: (Long) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onDeleteConversation: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
    replyDraft: String = "",
    onReplyDraftChange: (String) -> Unit = {},
    onSendReply: () -> Unit = {},
    onOpenSourceApp: (() -> Unit)? = null,
) {
    val formatter = rememberTimeFormatter()
    var confirmDeleteSelected by rememberSaveable { mutableStateOf(false) }
    var confirmDeleteConversation by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val insets = LocalScreenInsets.current
    BackHandler(enabled = state.selecting, onBack = onClearSelection)

    val title = state.conversation?.title ?: ""
    AppScaffold(
        title = title,
        snackbarHostState = snackbarHostState,
        scrollBehavior = null,
        topBar = {
            if (state.selecting) {
                TopAppBar(
                    title = { Text(pluralStringResource(R.plurals.selected_count, state.selection.size, state.selection.size)) },
                    navigationIcon = {
                        IconButton(onClick = onClearSelection) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear_selection))
                        }
                    },
                    actions = {
                        IconButton(onClick = onSelectAll) {
                            Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(R.string.action_select_all))
                        }
                        IconButton(onClick = { confirmDeleteSelected = true }, modifier = Modifier.testTag(ConversationTestTags.DELETE_SELECTED)) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.action_delete_selected))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    windowInsets = insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
            } else {
                TopAppBar(
                    title = {
                        Column(Modifier.semantics(mergeDescendants = true) { heading() }) {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            state.conversation?.let {
                                Text(it.appLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    },
                    navigationIcon = { if (onBack != null) BackButton(onBack) },
                    actions = {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_delete_conversation)) },
                                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        confirmDeleteConversation = true
                                    },
                                )
                            }
                        }
                    },
                    windowInsets = insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
            }
        },
    ) { padding ->
      Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
        if (!state.loading && state.messages.isEmpty()) {
            EmptyState(
                Icons.Outlined.Forum,
                stringResource(R.string.conversation_empty_title),
                stringResource(R.string.conversation_empty_body),
                Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).testTag(ConversationTestTags.LIST),
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.timeline, key = { it.key }, contentType = { it::class }) { item ->
                    when (item) {
                        is TimelineItem.DayHeader -> DayHeader(formatter.dayHeader(item.millis))
                        is TimelineItem.Message -> MessageBubble(
                            message = item.message,
                            showSender = state.conversation?.isGroup == true || item.message.sender != state.conversation?.title,
                            selected = item.message.id in state.selection,
                            formatter = formatter,
                            onClick = {
                                if (state.selecting) onToggleSelection(item.message.id) else onOpenMessage(item.message.id)
                            },
                            onLongClick = { onToggleSelection(item.message.id) },
                        )
                    }
                }
            }
        }
        val conversation = state.conversation
        if (!state.selecting && conversation != null && state.reply != null) {
            ReplyBar(
                availability = state.reply,
                appLabel = conversation.appLabel,
                draft = replyDraft,
                sending = state.sendingReply,
                onDraftChange = onReplyDraftChange,
                onSend = onSendReply,
                onOpenSourceApp = onOpenSourceApp,
            )
        }
      }
    }

    if (confirmDeleteSelected) {
        val count = state.selection.size
        ConfirmDialog(
            title = pluralStringResource(R.plurals.delete_messages_title, count, count),
            body = stringResource(R.string.delete_messages_body),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                confirmDeleteSelected = false
                onDeleteSelected()
            },
            onDismiss = { confirmDeleteSelected = false },
        )
    }
    if (confirmDeleteConversation) {
        ConfirmDialog(
            title = stringResource(R.string.delete_conversation_title),
            body = stringResource(R.string.delete_conversation_body, title),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                confirmDeleteConversation = false
                onDeleteConversation()
            },
            onDismiss = { confirmDeleteConversation = false },
        )
    }
}

@Composable
private fun DayHeader(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).semantics { heading() },
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: ArchivedMessage,
    showSender: Boolean,
    selected: Boolean,
    formatter: TimeFormatter,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val selectLabel = stringResource(R.string.action_select)
    Row(
        Modifier.fillMaxWidth().testTag(ConversationTestTags.message(message.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.padding(end = 8.dp).size(24.dp),
            )
        }
        Surface(
            color = if (selected) colors.secondaryContainer else colors.surfaceContainerHigh,
            contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface,
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp),
            modifier = Modifier
                .widthIn(max = 560.dp)
                .weight(1f, fill = false)
                .semantics { this.selected = selected }
                .combinedClickable(
                    role = Role.Button,
                    onLongClickLabel = selectLabel,
                    onLongClick = onLongClick,
                    onClick = onClick,
                ),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val sender = message.sender
                if (showSender && sender != null) {
                    Text(sender, style = MaterialTheme.typography.labelLarge, color = colors.primary)
                }
                Text(
                    messageBodyText(message),
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = if (message.contentKind == ContentKind.TEXT) FontStyle.Normal else FontStyle.Italic,
                )
                MessageBadges(message)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (message.isFavorite) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = stringResource(R.string.cd_favorite),
                            tint = colors.tertiary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        formatter.time(message.messageTimestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReplyBar(
    availability: ReplyAvailability,
    appLabel: String,
    draft: String,
    sending: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onOpenSourceApp: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider()
        when (availability) {
            ReplyAvailability.AVAILABLE -> Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { if (it.length <= MAX_REPLY_LENGTH) onDraftChange(it) },
                        placeholder = { Text(stringResource(R.string.reply_hint, appLabel)) },
                        maxLines = 4,
                        enabled = !sending,
                        modifier = Modifier.weight(1f).testTag(ConversationTestTags.REPLY_FIELD),
                    )
                    IconButton(
                        onClick = onSend,
                        enabled = draft.isNotBlank() && !sending,
                        modifier = Modifier.testTag(ConversationTestTags.REPLY_SEND),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.reply_send))
                    }
                }
                Text(
                    stringResource(R.string.reply_caption, appLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            ReplyAvailability.NO_NOTIFICATION, ReplyAvailability.LISTENER_OFF -> Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp).testTag(ConversationTestTags.REPLY_UNAVAILABLE),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (availability == ReplyAvailability.LISTENER_OFF) {
                        stringResource(R.string.reply_listener_off)
                    } else {
                        stringResource(R.string.reply_unavailable, appLabel)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (onOpenSourceApp != null) {
                    TextButton(onClick = onOpenSourceApp) { Text(stringResource(R.string.reply_open_app, appLabel)) }
                }
            }
        }
    }
}

private const val MAX_REPLY_LENGTH = 4_000
