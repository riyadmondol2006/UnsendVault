package com.riyadm.unsendvault.ui.home

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.core.model.AppSummary
import com.riyadm.unsendvault.core.model.Conversation
import com.riyadm.unsendvault.ui.common.LocalExternalActivityGuard
import com.riyadm.unsendvault.ui.common.TimeFormatter
import com.riyadm.unsendvault.ui.common.appDetailsIntent
import com.riyadm.unsendvault.ui.common.rememberTimeFormatter
import com.riyadm.unsendvault.ui.common.startFirstAvailable
import com.riyadm.unsendvault.ui.components.AppAvatar
import com.riyadm.unsendvault.ui.components.AppScaffold
import com.riyadm.unsendvault.ui.components.EmptyState
import com.riyadm.unsendvault.ui.components.MessageRowConfig
import com.riyadm.unsendvault.ui.components.MessageRowContent

@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    isWide: Boolean,
    onOpenMessage: (Long) -> Unit,
    onOpenConversation: (Long) -> Unit,
    onOpenReliabilityHelp: () -> Unit,
    onLockNow: () -> Unit,
    conversationPane: @Composable (conversationId: Long, onClose: () -> Unit) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val guard = LocalExternalActivityGuard.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshAccess() }
    HomeScreen(
        state = state,
        isWide = isWide,
        onSelectTab = viewModel::selectTab,
        onTogglePackage = viewModel::togglePackage,
        onClearPackages = viewModel::clearPackageFilter,
        onOpenApp = viewModel::openApp,
        onOpenMessage = onOpenMessage,
        onOpenConversation = { id -> if (isWide) viewModel.selectConversation(id) else onOpenConversation(id) },
        onGrantAccess = {
            guard()
            context.startFirstAvailable(
                viewModel.accessSettingsIntent(),
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                context.appDetailsIntent(),
            )
        },
        onResume = viewModel::resumeCapture,
        onReconnect = viewModel::reconnect,
        onReliabilityHelp = onOpenReliabilityHelp,
        onLockNow = onLockNow,
        conversationPane = { id -> conversationPane(id) { viewModel.selectConversation(null) } },
    )
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    isWide: Boolean,
    onSelectTab: (HomeTab) -> Unit,
    onTogglePackage: (String) -> Unit,
    onClearPackages: () -> Unit,
    onOpenApp: (String) -> Unit,
    onOpenMessage: (Long) -> Unit,
    onOpenConversation: (Long) -> Unit,
    onGrantAccess: () -> Unit,
    onResume: () -> Unit,
    onReconnect: () -> Unit,
    onReliabilityHelp: () -> Unit,
    onLockNow: () -> Unit,
    conversationPane: @Composable (Long) -> Unit = {},
) {
    val formatter = rememberTimeFormatter()
    AppScaffold(
        title = stringResource(R.string.home_title),
        actions = {
            if (state.appLockEnabled) {
                IconButton(onClick = onLockNow) {
                    Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.action_lock_now))
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
                HomeTab.entries.forEach { tab ->
                    Tab(
                        selected = state.tab == tab,
                        onClick = { onSelectTab(tab) },
                        text = {
                            Text(
                                stringResource(
                                    when (tab) {
                                        HomeTab.RECENT -> R.string.home_tab_recent
                                        HomeTab.CONVERSATIONS -> R.string.home_tab_conversations
                                        HomeTab.APPS -> R.string.home_tab_apps
                                    },
                                ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
            val header: LazyListScope.() -> Unit = {
                // Only when capture needs attention; a healthy archive starts with the list.
                if (state.captureStatus != CaptureStatus.ACTIVE) {
                    item(key = "status", contentType = "status") {
                        CaptureStatusCard(
                            state.captureStatus, onGrantAccess, onResume, onReconnect, onReliabilityHelp,
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
                // Also with a single app while it is selected (e.g. after picking it in the Apps tab), so the filter
                // stays visible and can be cleared.
                if (state.tab != HomeTab.APPS && (state.apps.size > 1 || state.selectedPackages.isNotEmpty())) {
                    item(key = "chips", contentType = "chips") {
                        AppFilterChips(state.apps, state.selectedPackages, onTogglePackage, onClearPackages)
                    }
                }
            }
            val showSplit = isWide && state.tab == HomeTab.CONVERSATIONS
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(if (showSplit) 0.42f else 1f).fillMaxHeight()) {
                    when (state.tab) {
                        HomeTab.RECENT -> RecentList(state, formatter, header, onOpenMessage)
                        HomeTab.CONVERSATIONS -> ConversationList(state, formatter, header, isWide, onOpenConversation)
                        HomeTab.APPS -> AppList(state, formatter, header, onOpenApp)
                    }
                }
                if (showSplit) {
                    VerticalDivider()
                    Box(Modifier.weight(0.58f).fillMaxHeight()) {
                        val selected = state.selectedConversationId
                        if (selected != null) {
                            conversationPane(selected)
                        } else {
                            EmptyState(
                                Icons.Outlined.Forum,
                                stringResource(R.string.home_pick_conversation_title),
                                stringResource(R.string.home_pick_conversation_body),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppFilterChips(
    apps: List<AppSummary>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    ) {
        item(key = "all") {
            FilterChip(
                selected = selected.isEmpty(),
                onClick = onClear,
                label = { Text(stringResource(R.string.filter_all_apps)) },
                leadingIcon = if (selected.isEmpty()) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else {
                    null
                },
            )
        }
        items(apps, key = { it.packageName }) { app ->
            val isSelected = app.packageName in selected
            FilterChip(
                selected = isSelected,
                onClick = { onToggle(app.packageName) },
                label = { Text(app.appLabel, maxLines = 1) },
                leadingIcon = if (isSelected) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else {
                    { AppAvatar(app.packageName, app.appLabel, size = 18.dp) }
                },
                modifier = Modifier.testTag(HomeTestTags.appChip(app.packageName)),
            )
        }
    }
}

@Composable
private fun RecentList(
    state: HomeUiState,
    formatter: TimeFormatter,
    header: LazyListScope.() -> Unit,
    onOpenMessage: (Long) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().testTag(HomeTestTags.LIST)) {
        header()
        if (!state.loading && state.recent.isEmpty()) {
            item(key = "empty", contentType = "empty") { HomeEmpty(state) }
        }
        items(state.recent, key = { it.id }, contentType = { "message" }) { message ->
            MessageRowContent(
                message = message,
                timeLabel = formatter.short(message.messageTimestamp),
                config = MessageRowConfig(),
                modifier = Modifier.clickable(role = Role.Button) { onOpenMessage(message.id) },
            )
            HorizontalDivider(Modifier.padding(start = 72.dp))
        }
    }
}

@Composable
private fun ConversationList(
    state: HomeUiState,
    formatter: TimeFormatter,
    header: LazyListScope.() -> Unit,
    isWide: Boolean,
    onOpen: (Long) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().testTag(HomeTestTags.LIST)) {
        header()
        if (!state.loading && state.conversations.isEmpty()) {
            item(key = "empty", contentType = "empty") { HomeEmpty(state) }
        }
        items(state.conversations, key = { it.id }, contentType = { "conversation" }) { conversation ->
            ConversationRow(
                conversation = conversation,
                timeLabel = formatter.short(conversation.lastMessageAt),
                selected = isWide && state.selectedConversationId == conversation.id,
                onClick = { onOpen(conversation.id) },
            )
        }
    }
}

@Composable
fun ConversationRow(conversation: Conversation, timeLabel: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        overlineContent = { Text(conversation.appLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                conversation.lastMessagePreview?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Text(
                    pluralStringResource(R.plurals.stats_messages, conversation.messageCount, conversation.messageCount),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
        leadingContent = {
            Box {
                AppAvatar(conversation.packageName, conversation.appLabel)
                if (conversation.isGroup) {
                    Icon(
                        Icons.Outlined.Group,
                        contentDescription = stringResource(R.string.cd_group_conversation),
                        modifier = Modifier.size(16.dp).align(Alignment.BottomEnd),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        trailingContent = { Text(timeLabel, style = MaterialTheme.typography.labelMedium) },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        ),
        modifier = Modifier.selectable(selected = selected, role = Role.Button, onClick = onClick),
    )
}

@Composable
private fun AppList(
    state: HomeUiState,
    formatter: TimeFormatter,
    header: LazyListScope.() -> Unit,
    onOpenApp: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().testTag(HomeTestTags.LIST)) {
        header()
        if (!state.loading && state.apps.isEmpty()) {
            item(key = "empty", contentType = "empty") { HomeEmpty(state) }
        }
        items(state.apps, key = { it.packageName }, contentType = { "app" }) { app ->
            ListItem(
                headlineContent = { Text(app.appLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        pluralStringResource(R.plurals.stats_messages, app.messageCount, app.messageCount) + " · " +
                            pluralStringResource(R.plurals.stats_conversations, app.conversationCount, app.conversationCount),
                    )
                },
                leadingContent = { AppAvatar(app.packageName, app.appLabel) },
                trailingContent = app.lastMessageAt?.let { { Text(formatter.short(it), style = MaterialTheme.typography.labelMedium) } },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(role = Role.Button) { onOpenApp(app.packageName) },
            )
        }
    }
}

@Composable
private fun HomeEmpty(state: HomeUiState) {
    val filtered = state.selectedPackages.isNotEmpty()
    val (title, body) = when {
        filtered -> R.string.home_empty_filtered_title to R.string.home_empty_filtered_body
        state.captureStatus == CaptureStatus.NO_ACCESS -> R.string.home_empty_no_access_title to R.string.home_empty_no_access_body
        else -> R.string.home_empty_title to R.string.home_empty_body
    }
    EmptyState(
        icon = if (state.tab == HomeTab.APPS) Icons.Outlined.Apps else Icons.Outlined.Inbox,
        title = stringResource(title),
        body = stringResource(body),
        modifier = Modifier.testTag(HomeTestTags.EMPTY).padding(vertical = 24.dp),
        scrollable = false,
    )
}
