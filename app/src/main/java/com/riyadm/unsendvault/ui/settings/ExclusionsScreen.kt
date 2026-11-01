package com.riyadm.unsendvault.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.apps.ProfileKind
import com.riyadm.unsendvault.ui.components.AppAvatar
import com.riyadm.unsendvault.ui.components.AppScaffold
import com.riyadm.unsendvault.ui.components.ConfirmDialog
import com.riyadm.unsendvault.ui.components.InfoText

/** The app list serves both "Excluded apps" (default mode) and "Selected apps" ("Only selected apps" mode). */
enum class AppPickerMode { EXCLUDE, SELECT }

object AppPickerTestTags {
    const val LIST = "exclusions_list"
    fun row(packageName: String) = "app_picker_$packageName"
}

/**
 * Order for the list, fixed when it first loads so rows don't jump while switches are flipped. [pinned] are the apps
 * that were ticked when the list opened, [newAtOpen] the apps marked "New" then. In [AppPickerMode.SELECT]: the new
 * apps first (by name), then the pinned ones, then the rest in [candidates] order (archive apps, then alphabetical). In
 * [AppPickerMode.EXCLUDE]: the pinned (excluded) apps first, then the rest.
 */
internal fun orderAppChoices(
    candidates: List<ExclusionCandidate>,
    mode: AppPickerMode,
    pinned: Set<String>,
    newAtOpen: Set<String> = emptySet(),
): List<ExclusionCandidate> {
    val (newApps, others) = if (mode == AppPickerMode.SELECT) {
        candidates.partition { it.packageName in newAtOpen }
    } else {
        emptyList<ExclusionCandidate>() to candidates
    }
    val (first, rest) = others.partition { it.packageName in pinned }
    return newApps.sortedBy { it.label.lowercase() } + first + rest
}

private fun ExclusionCandidate.isOn(mode: AppPickerMode) = if (mode == AppPickerMode.SELECT) selected else excluded

@Composable
fun ExclusionsRoute(viewModel: SettingsViewModel, onBack: () -> Unit, mode: AppPickerMode = AppPickerMode.EXCLUDE) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var offer by remember { mutableStateOf<SettingsEvent.OfferDeleteAppData?>(null) }
    var filter by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(viewModel) {
        viewModel.loadLauncherApps()
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.OfferDeleteAppData -> offer = event
                is SettingsEvent.AppDataDeleted -> snackbar.showSnackbar(resources.getString(R.string.exclusions_data_deleted, event.label))
                else -> Unit
            }
        }
    }

    val onLabel = stringResource(if (mode == AppPickerMode.SELECT) R.string.selected_apps_state_saved else R.string.exclusions_state_excluded)
    val offLabel = stringResource(if (mode == AppPickerMode.SELECT) R.string.selected_apps_state_not_saved else R.string.exclusions_state_captured)
    val title = stringResource(if (mode == AppPickerMode.SELECT) R.string.settings_selected_apps else R.string.settings_excluded_apps)
    // Show the list only once the installed apps are known: a partial list re-sorted later would open scrolled down.
    val ready = state.launcherApps != null && state.settings != null
    val pinned = remember(ready, mode) {
        if (ready) state.exclusionCandidates.filter { it.isOn(mode) }.mapTo(HashSet()) { it.packageName } else emptySet()
    }
    val newAtOpen = remember(ready, mode) {
        if (ready) state.exclusionCandidates.filter { it.isNew }.mapTo(HashSet()) { it.packageName } else emptySet()
    }
    AppScaffold(title = title, onBack = onBack, snackbarHostState = snackbar) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            InfoText(
                stringResource(if (mode == AppPickerMode.SELECT) R.string.selected_apps_explainer else R.string.exclusions_explainer),
                Modifier.padding(top = 8.dp),
            )
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.exclusions_filter_hint)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = MaterialTheme.shapes.extraLarge,
            )
            if (!ready) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                return@Column
            }
            if (state.launcherApps.orEmpty().any { it.profile != ProfileKind.PERSONAL || it.alsoIn.isNotEmpty() }) {
                InfoText(
                    stringResource(if (mode == AppPickerMode.SELECT) R.string.selected_apps_profiles_note else R.string.exclusions_profiles_note),
                )
            }
            val candidates = orderAppChoices(state.exclusionCandidates, mode, pinned, newAtOpen).let { all ->
                val needle = filter.trim()
                if (needle.isEmpty()) all else all.filter { it.label.contains(needle, true) || it.packageName.contains(needle, true) }
            }
            val newLabel = stringResource(R.string.selected_apps_new)
            LazyColumn(Modifier.fillMaxSize().testTag(AppPickerTestTags.LIST)) {
                items(candidates, key = { it.packageName }, contentType = { "app" }) { candidate ->
                    // A fresh group per app: a recycled row must not animate its switch from the previous app's state.
                    key(candidate.packageName) {
                        val on = candidate.isOn(mode)
                        ListItem(
                            headlineContent = { Text(candidate.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                val detail = if (candidate.archivedMessages > 0) {
                                    pluralStringResource(R.plurals.stats_messages, candidate.archivedMessages, candidate.archivedMessages)
                                } else {
                                    candidate.packageName
                                }
                                val parts = listOfNotNull(
                                    if (mode == AppPickerMode.SELECT && candidate.isNew) newLabel else null,
                                    profileText(candidate.profile, candidate.alsoIn),
                                    detail,
                                )
                                Text(parts.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            leadingContent = { AppAvatar(candidate.packageName, candidate.label) },
                            trailingContent = { Switch(checked = on, onCheckedChange = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier
                                .heightIn(min = 56.dp)
                                .testTag(AppPickerTestTags.row(candidate.packageName))
                                .toggleable(value = on, role = Role.Switch) {
                                    if (mode == AppPickerMode.SELECT) viewModel.setSelected(candidate, it) else viewModel.setExcluded(candidate, it)
                                }
                                .semantics { stateDescription = if (on) onLabel else offLabel },
                        )
                    }
                }
            }
        }
    }

    offer?.let { event ->
        ConfirmDialog(
            title = stringResource(R.string.exclusions_delete_title, event.label),
            body = pluralStringResource(R.plurals.exclusions_delete_body, event.messageCount, event.messageCount, event.label),
            confirmLabel = stringResource(R.string.action_delete),
            dismissLabel = stringResource(R.string.action_keep),
            destructive = true,
            onConfirm = {
                viewModel.deleteAppData(event.packageName, event.label)
                offer = null
            },
            onDismiss = { offer = null },
        )
    }
}

/** "Work profile" for an app only there, "Also in work profile" for one in both; null for personal-only apps. */
@Composable
private fun profileText(profile: ProfileKind, alsoIn: Set<ProfileKind>): String? = when {
    profile != ProfileKind.PERSONAL -> stringResource(
        when (profile) {
            ProfileKind.WORK -> R.string.profile_work
            ProfileKind.CLONE -> R.string.profile_clone
            ProfileKind.PRIVATE -> R.string.profile_private
            else -> R.string.profile_other
        },
    )
    alsoIn.isNotEmpty() -> stringResource(
        when {
            ProfileKind.WORK in alsoIn -> R.string.profile_also_work
            ProfileKind.CLONE in alsoIn -> R.string.profile_also_clone
            ProfileKind.PRIVATE in alsoIn -> R.string.profile_also_private
            else -> R.string.profile_also_other
        },
    )
    else -> null
}
