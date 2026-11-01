package com.riyadm.unsendvault.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.ui.common.rememberTimeFormatter
import com.riyadm.unsendvault.ui.components.AppAvatar
import com.riyadm.unsendvault.ui.components.AppScaffold
import com.riyadm.unsendvault.ui.components.ChoiceDialog
import com.riyadm.unsendvault.ui.components.EmptyState
import com.riyadm.unsendvault.ui.components.MessageRowConfig
import com.riyadm.unsendvault.ui.components.MessageRowContent
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object SearchTestTags {
    const val FIELD = "search_field"
    const val RESULTS = "search_results"
    const val FAVORITES = "search_filter_favorites"
    const val DELETED = "search_filter_deleted"
    const val COUNT = "search_count"
    fun app(pkg: String) = "search_app_$pkg"
}

@Composable
fun SearchRoute(viewModel: SearchViewModel, onOpenMessage: (Long) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SearchScreen(
        query = viewModel.query,
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onTogglePackage = viewModel::togglePackage,
        onConversation = viewModel::setConversation,
        onDayRange = viewModel::setDayRange,
        onFavoritesOnly = viewModel::setFavoritesOnly,
        onPossiblyDeletedOnly = viewModel::setPossiblyDeletedOnly,
        onClearFilters = viewModel::clearFilters,
        onOpenMessage = onOpenMessage,
    )
}

@Composable
fun SearchScreen(
    query: String,
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onTogglePackage: (String) -> Unit,
    onConversation: (Long?) -> Unit,
    onDayRange: (DayRange?) -> Unit,
    onFavoritesOnly: (Boolean) -> Unit,
    onPossiblyDeletedOnly: (Boolean) -> Unit,
    onClearFilters: () -> Unit,
    onOpenMessage: (Long) -> Unit,
) {
    val formatter = rememberTimeFormatter()
    val focus = LocalFocusManager.current
    var showConversationPicker by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    val filters = state.filters

    AppScaffold(title = stringResource(R.string.search_title)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(SearchTestTags.FIELD),
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.action_clear_search))
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                shape = MaterialTheme.shapes.extraLarge,
            )
            if (state.apps.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.apps, key = { it.packageName }) { app ->
                        val selected = app.packageName in filters.packages
                        FilterChip(
                            selected = selected,
                            onClick = { onTogglePackage(app.packageName) },
                            label = { Text(app.appLabel, maxLines = 1) },
                            leadingIcon = if (selected) {
                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else {
                                { AppAvatar(app.packageName, app.appLabel, size = 18.dp) }
                            },
                            modifier = Modifier.testTag(SearchTestTags.app(app.packageName)),
                        )
                    }
                }
            }
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val conversationLabel = filters.conversationId?.let { id -> state.conversations.firstOrNull { it.id == id }?.title }
                FilterChip(
                    selected = filters.conversationId != null,
                    onClick = { showConversationPicker = true },
                    label = { Text(conversationLabel ?: stringResource(R.string.search_filter_conversation), maxLines = 1) },
                    leadingIcon = { Icon(Icons.Outlined.Forum, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                val range = filters.dayRange
                FilterChip(
                    selected = range != null,
                    onClick = { showDatePicker = true },
                    label = { Text(range?.let { formatDayRange(it) } ?: stringResource(R.string.search_filter_dates), maxLines = 1) },
                    leadingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                FilterChip(
                    selected = filters.favoritesOnly,
                    onClick = { onFavoritesOnly(!filters.favoritesOnly) },
                    label = { Text(stringResource(R.string.search_filter_favorites)) },
                    leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.testTag(SearchTestTags.FAVORITES),
                )
                FilterChip(
                    selected = filters.possiblyDeletedOnly,
                    onClick = { onPossiblyDeletedOnly(!filters.possiblyDeletedOnly) },
                    label = { Text(stringResource(R.string.search_filter_possibly_deleted)) },
                    leadingIcon = { Icon(Icons.Outlined.EventBusy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.testTag(SearchTestTags.DELETED),
                )
                if (filters.active) {
                    InputChip(
                        selected = false,
                        onClick = onClearFilters,
                        label = { Text(stringResource(R.string.search_clear_filters)) },
                        leadingIcon = { Icon(Icons.Outlined.FilterList, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
            if (!state.hasCriteria) {
                EmptyState(Icons.Filled.Search, stringResource(R.string.search_start_title), stringResource(R.string.search_start_body))
            } else {
                Text(
                    pluralStringResource(R.plurals.search_result_count, state.results.size, state.results.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag(SearchTestTags.COUNT),
                )
                if (state.results.isEmpty()) {
                    EmptyState(Icons.Filled.Search, stringResource(R.string.search_no_results_title), stringResource(R.string.search_no_results_body))
                } else {
                    LazyColumn(Modifier.fillMaxSize().testTag(SearchTestTags.RESULTS)) {
                        items(state.results, key = { it.id }, contentType = { "message" }) { message ->
                            MessageRowContent(
                                message = message,
                                timeLabel = formatter.short(message.messageTimestamp),
                                config = MessageRowConfig(highlight = state.appliedQuery),
                                modifier = Modifier.clickable(role = Role.Button) { onOpenMessage(message.id) },
                            )
                            HorizontalDivider(Modifier.padding(start = 72.dp))
                        }
                    }
                }
            }
        }
    }

    if (showConversationPicker) {
        val options = remember(state.conversations) { listOf<Long?>(null) + state.conversations.map { it.id } }
        val anyLabel = stringResource(R.string.search_any_conversation)
        ChoiceDialog(
            title = stringResource(R.string.search_filter_conversation),
            options = options,
            selected = filters.conversationId,
            label = { id ->
                if (id == null) anyLabel else state.conversations.firstOrNull { it.id == id }?.let { "${it.title} · ${it.appLabel}" } ?: ""
            },
            onSelect = {
                onConversation(it)
                showConversationPicker = false
            },
            onDismiss = { showConversationPicker = false },
        )
    }

    if (showDatePicker) {
        val pickerState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = filters.dayRange?.startUtcMillis,
            initialSelectedEndDateMillis = filters.dayRange?.endUtcMillis,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    enabled = pickerState.selectedStartDateMillis != null,
                    onClick = {
                        val start = pickerState.selectedStartDateMillis
                        if (start != null) onDayRange(DayRange(start, pickerState.selectedEndDateMillis ?: start))
                        showDatePicker = false
                    },
                ) { Text(stringResource(R.string.action_apply)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    onDayRange(null)
                    showDatePicker = false
                }) { Text(stringResource(R.string.action_clear)) }
            },
        ) {
            DateRangePicker(state = pickerState, modifier = Modifier.weight(1f))
        }
    }
}

private fun formatDayRange(range: DayRange): String {
    // Created per call so a runtime locale change is picked up.
    val rangeFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    fun day(millis: Long) = rangeFormat.format(Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate())
    return if (range.startUtcMillis == range.endUtcMillis) day(range.startUtcMillis) else "${day(range.startUtcMillis)} – ${day(range.endUtcMillis)}"
}
