package com.riyadm.unsendvault.ui

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.core.model.ContentKind
import com.riyadm.unsendvault.core.model.MessageDetail
import com.riyadm.unsendvault.core.model.MessageRevision
import com.riyadm.unsendvault.core.model.RemovalReason
import com.riyadm.unsendvault.core.model.VaultSettings
import com.riyadm.unsendvault.data.fake.InMemorySettingsRepository
import com.riyadm.unsendvault.data.fake.InMemoryVaultRepository
import com.riyadm.unsendvault.ui.detail.DetailTestTags
import com.riyadm.unsendvault.ui.detail.MessageDetailScreen
import com.riyadm.unsendvault.ui.detail.MessageDetailUiState
import com.riyadm.unsendvault.ui.home.HomeScreen
import com.riyadm.unsendvault.ui.home.HomeTestTags
import com.riyadm.unsendvault.ui.home.HomeViewModel
import com.riyadm.unsendvault.ui.search.SearchScreen
import com.riyadm.unsendvault.ui.search.SearchTestTags
import com.riyadm.unsendvault.ui.search.SearchViewModel
import com.riyadm.unsendvault.ui.theme.UnsendVaultTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveScreensUiTest {
    @get:Rule val rule = createComposeRule()

    private fun setHome(vm: HomeViewModel) {
        rule.setContent {
            UnsendVaultTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                HomeScreen(
                    state = state, isWide = false,
                    onSelectTab = vm::selectTab, onTogglePackage = vm::togglePackage, onClearPackages = vm::clearPackageFilter,
                    onOpenApp = vm::openApp, onOpenMessage = {}, onOpenConversation = {}, onGrantAccess = {},
                    onResume = vm::resumeCapture, onReconnect = vm::reconnect, onReliabilityHelp = {}, onLockNow = {},
                )
            }
        }
    }

    @Test
    fun homeEmptyWithoutAccessAsksForAccess() {
        setHome(HomeViewModel(InMemoryVaultRepository(), InMemorySettingsRepository(VaultSettings(onboardingCompleted = true)), FakeListenerStatus(false)))
        rule.onNodeWithText(str(R.string.status_no_access_title)).assertIsDisplayed()
        rule.onNodeWithTag(HomeTestTags.GRANT).assertIsDisplayed()
        rule.onNodeWithTag(HomeTestTags.EMPTY).assertExists()
        rule.onNodeWithText(str(R.string.home_empty_no_access_title)).assertExists()
    }

    @Test
    fun homePausedOffersResume() {
        val settings = InMemorySettingsRepository(VaultSettings(onboardingCompleted = true, captureEnabled = false))
        setHome(HomeViewModel(InMemoryVaultRepository(), settings, FakeListenerStatus(true)))
        rule.onNodeWithText(str(R.string.status_paused_title)).assertIsDisplayed()
        rule.onNodeWithTag(HomeTestTags.RESUME).performClick()
        // Healthy capture shows no card at all; the paused card goes away.
        rule.waitUntil(5_000) { rule.onAllNodesWithText(str(R.string.status_paused_title)).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText(str(R.string.status_active_title)).assertDoesNotExist()
    }

    @Test
    fun homePopulatedAndFilteredByApp() {
        val vault = InMemoryVaultRepository(listOf(UiFixtures.alex, UiFixtures.club), UiFixtures.messages)
        setHome(HomeViewModel(vault, InMemorySettingsRepository(VaultSettings(onboardingCompleted = true)), FakeListenerStatus(true)))
        rule.onNodeWithText(str(R.string.status_active_title)).assertDoesNotExist()
        rule.onNodeWithText("See you at 7", substring = true).assertExists()
        rule.onNodeWithText("Chapter 7 on Sunday", substring = true).assertExists()

        rule.onNodeWithTag(HomeTestTags.appChip("org.telegram.messenger")).performClick()
        rule.onNodeWithText("Chapter 7 on Sunday", substring = true).assertExists()
        rule.onNodeWithText("See you at 7", substring = true).assertDoesNotExist()

        rule.onNodeWithText(str(R.string.home_tab_conversations)).performClick()
        rule.onNodeWithText("Book Club").assertExists()
        rule.onNodeWithText("Alex Rivera").assertDoesNotExist()
    }

    @Test
    fun searchFiltersAndHighlights() {
        val vault = InMemoryVaultRepository(listOf(UiFixtures.alex, UiFixtures.club), UiFixtures.messages)
        val vm = SearchViewModel(vault)
        rule.setContent {
            UnsendVaultTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                SearchScreen(
                    query = vm.query, state = state, onQueryChange = vm::onQueryChange, onTogglePackage = vm::togglePackage,
                    onConversation = vm::setConversation, onDayRange = vm::setDayRange, onFavoritesOnly = vm::setFavoritesOnly,
                    onPossiblyDeletedOnly = vm::setPossiblyDeletedOnly, onClearFilters = vm::clearFilters, onOpenMessage = {},
                )
            }
        }
        rule.onNodeWithText(str(R.string.search_start_title)).assertIsDisplayed()
        rule.onNodeWithTag(SearchTestTags.FIELD).performTextInput("7")
        val twoResults = plural(R.plurals.search_result_count, 2)
        rule.waitUntil(5_000) { rule.onAllNodesWithText(twoResults).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(SearchTestTags.FAVORITES).performClick()
        val oneResult = plural(R.plurals.search_result_count, 1)
        rule.waitUntil(5_000) { rule.onAllNodesWithText(oneResult).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("See you at 7", substring = true).assertExists()
        rule.onAllNodesWithText("Chapter 7 on Sunday", substring = true).assertCountEquals(0)
    }

    @Test
    fun detailExplainsDeletionEvidenceRemovalAndRevisions() {
        val message = UiFixtures.message(
            3, UiFixtures.alex, "Ignore the last voice note, wrong chat", UiFixtures.BASE,
            deleted = true, removal = RemovalReason.APP_CANCELLED, revisions = 2,
        )
        val detail = MessageDetail(message, listOf(MessageRevision(10, 3, "Ignore the last voice note", UiFixtures.BASE + 200)))
        rule.setContent {
            UnsendVaultTheme {
                MessageDetailScreen(MessageDetailUiState(false, detail), onBack = {}, onToggleFavorite = {}, onCopy = {}, onShare = {}, onDelete = {})
            }
        }
        rule.onNodeWithTag(DetailTestTags.EVIDENCE).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.detail_possibly_deleted_title)).assertIsDisplayed()
        rule.onNode(hasText("WhatsApp showed “This message was deleted”", substring = true)).assertExists()
        rule.onNodeWithText(str(R.string.detail_removal_not_deletion)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(DetailTestTags.HISTORY).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Ignore the last voice note").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun detailExplainsAndroidRedaction() {
        val message = UiFixtures.message(5, UiFixtures.club, "", UiFixtures.BASE, kind = ContentKind.SYSTEM_REDACTED)
        rule.setContent {
            UnsendVaultTheme {
                MessageDetailScreen(MessageDetailUiState(false, MessageDetail(message, emptyList())), {}, {}, {}, {}, {})
            }
        }
        rule.onNodeWithTag(DetailTestTags.KIND).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.kind_redacted_body)).assertIsDisplayed()
        rule.onNodeWithTag(DetailTestTags.EVIDENCE).assertDoesNotExist()
    }
}
