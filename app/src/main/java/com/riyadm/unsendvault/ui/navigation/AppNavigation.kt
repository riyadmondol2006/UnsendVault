package com.riyadm.unsendvault.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.lifecycle.compose.dropUnlessResumed
import com.riyadm.unsendvault.BuildConfig
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.core.model.VaultSettings
import com.riyadm.unsendvault.ui.common.LocalScreenInsets
import com.riyadm.unsendvault.ui.common.containerViewModel
import com.riyadm.unsendvault.ui.common.defaultScreenInsets
import com.riyadm.unsendvault.ui.common.rememberReducedMotion
import com.riyadm.unsendvault.ui.conversation.ConversationRoute
import com.riyadm.unsendvault.ui.conversation.ConversationViewModel
import com.riyadm.unsendvault.ui.debug.DemoData
import com.riyadm.unsendvault.ui.detail.MessageDetailRoute
import com.riyadm.unsendvault.ui.detail.MessageDetailViewModel
import com.riyadm.unsendvault.ui.favorites.FavoritesRoute
import com.riyadm.unsendvault.ui.favorites.FavoritesViewModel
import com.riyadm.unsendvault.ui.home.HomeRoute
import com.riyadm.unsendvault.ui.home.HomeViewModel
import com.riyadm.unsendvault.ui.onboarding.OnboardingRoute
import com.riyadm.unsendvault.ui.onboarding.OnboardingViewModel
import com.riyadm.unsendvault.ui.search.SearchRoute
import com.riyadm.unsendvault.ui.search.SearchViewModel
import com.riyadm.unsendvault.ui.settings.AboutRoute
import com.riyadm.unsendvault.ui.settings.BackupViewModel
import com.riyadm.unsendvault.ui.settings.AppPickerMode
import com.riyadm.unsendvault.ui.settings.ExclusionsRoute
import com.riyadm.unsendvault.ui.settings.PackageManagerAppsSource
import com.riyadm.unsendvault.ui.settings.ReliabilityRoute
import com.riyadm.unsendvault.ui.settings.SettingsRoute
import com.riyadm.unsendvault.ui.settings.SettingsViewModel
import com.riyadm.unsendvault.ui.update.UpdatePrompts
import com.riyadm.unsendvault.di.AppContainer
import androidx.compose.ui.platform.LocalContext
import com.riyadm.unsendvault.appContainer
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.unsendvault.data.repo.vaultHealth
import com.riyadm.unsendvault.ui.common.appDetailsIntent
import com.riyadm.unsendvault.ui.common.startFirstAvailable
import kotlinx.serialization.Serializable

@Serializable data object HomeDest
@Serializable data object SearchDest
@Serializable data object FavoritesDest
@Serializable data object SettingsDest
@Serializable data class ConversationDest(val id: Long)
@Serializable data class MessageDest(val id: Long)
@Serializable data object ExclusionsDest
@Serializable data object SelectedAppsDest
@Serializable data object ReliabilityDest
@Serializable data object AboutDest

enum class TopLevel(val route: Any, val label: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME(HomeDest, R.string.nav_archive, Icons.Outlined.Inbox, Icons.Filled.Inbox),
    SEARCH(SearchDest, R.string.nav_search, Icons.Filled.Search, Icons.Filled.Search),
    FAVORITES(FavoritesDest, R.string.nav_favorites, Icons.Outlined.StarBorder, Icons.Filled.Star),
    SETTINGS(SettingsDest, R.string.nav_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
    ;

    fun matches(destination: NavDestination?): Boolean = destination?.hasRoute(route::class) == true
}

/** Root of the unlocked UI: onboarding until completed, then the adaptive main scaffold. */
@Composable
fun UnsendVaultAppUi(widthSizeClass: WindowWidthSizeClass, settings: VaultSettings, onLockNow: () -> Unit) {
    if (!settings.onboardingCompleted) {
        CompositionLocalProvider(LocalScreenInsets provides defaultScreenInsets) {
            OnboardingRoute(containerViewModel { OnboardingViewModel(it.settingsRepository, it.listenerStatus) })
        }
        return
    }
    MainScaffold(widthSizeClass, onLockNow)
}

@Composable
private fun MainScaffold(widthSizeClass: WindowWidthSizeClass, onLockNow: () -> Unit) {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    val useRail = widthSizeClass != WindowWidthSizeClass.Compact
    val showBottomBar = !useRail && TopLevel.entries.any { it.matches(destination) }
    val base = defaultScreenInsets
    val screenInsets: WindowInsets = when {
        useRail -> base.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.End)
        showBottomBar -> base.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        else -> base
    }
    val context = LocalContext.current
    val pendingLink by DeepLinks.requests.collectAsStateWithLifecycle()
    LaunchedEffect(pendingLink) {
        val link = pendingLink ?: return@LaunchedEffect
        DeepLinks.consume(link)
        when (link) {
            DeepLink.Home -> navController.navigateTopLevel(TopLevel.HOME)
            DeepLink.Favorites -> navController.navigateTopLevel(TopLevel.FAVORITES)
            is DeepLink.Message -> navController.navigate(MessageDest(link.id)) { launchSingleTop = true }
            is DeepLink.Conversation -> navController.navigate(ConversationDest(link.id)) { launchSingleTop = true }
            DeepLink.NotificationAccess -> context.startFirstAvailable(
                context.appContainer.listenerStatus.accessSettingsIntent(),
                context.appDetailsIntent(),
            )
        }
    }
    // Opt-in update check: consent / update dialogs, once-per-process automatic check (unlocked main UI only).
    UpdatePrompts()
    Row(Modifier.fillMaxSize()) {
        if (useRail) {
            NavigationRail {
                TopLevel.entries.forEach { item ->
                    val selected = item.matches(destination)
                    NavigationRailItem(
                        selected = selected,
                        onClick = { navController.navigateTopLevel(item) },
                        icon = { Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null) },
                        label = { Text(stringResource(item.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
        Column(Modifier.weight(1f)) {
            VaultHealthBanner(
                health = context.appContainer.vaultRepository.vaultHealth,
                modifier = Modifier.windowInsetsPadding(screenInsets.only(WindowInsetsSides.Top)),
            )
            Box(Modifier.weight(1f)) {
                CompositionLocalProvider(LocalScreenInsets provides screenInsets) {
                    AppNavHost(navController, isWide = useRail, onLockNow = onLockNow)
                }
            }
            if (showBottomBar) {
                NavigationBar {
                    TopLevel.entries.forEach { item ->
                        val selected = item.matches(destination)
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateTopLevel(item) },
                            icon = { Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        }
    }
}

private fun NavHostController.navigateTopLevel(item: TopLevel) {
    navigate(item.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun conversationViewModel(id: Long, c: AppContainer) =
    ConversationViewModel(id, c.vaultRepository, replyGateway = c.replyGateway)

/** Saved-message notifications: clear the chat's notification while it is open, and don't post new ones for it. */
private fun AppContainer.onConversationVisible(id: Long, visible: Boolean) {
    if (!settingsRepository.current.savedMessageNotifications) return
    if (visible) savedMessageNotifier.onConversationVisible(id) else savedMessageNotifier.onConversationHidden(id)
}

private fun settingsViewModelFactory(c: AppContainer) = SettingsViewModel(
    settingsRepository = c.settingsRepository,
    vault = c.vaultRepository,
    retentionScheduler = c.retentionScheduler,
    installedApps = PackageManagerAppsSource(c.context, c.appDirectory),
    demoDataInserter = if (BuildConfig.DEBUG) ({ DemoData.insert(c.vaultRepository) }) else null,
    cancelSavedNotifications = { c.savedMessageNotifier.cancelAll() },
)

@Composable
private fun AppNavHost(navController: NavHostController, isWide: Boolean, onLockNow: () -> Unit) {
    val reducedMotion = rememberReducedMotion()
    val container = LocalContext.current.appContainer
    val noInsets = WindowInsets(0, 0, 0, 0)
    NavHost(
        navController = navController,
        startDestination = HomeDest,
        enterTransition = { if (reducedMotion) EnterTransition.None else fadeIn() },
        exitTransition = { if (reducedMotion) ExitTransition.None else fadeOut() },
    ) {
        composable<HomeDest> {
            HomeRoute(
                viewModel = containerViewModel { HomeViewModel(it.vaultRepository, it.settingsRepository, it.listenerStatus) },
                isWide = isWide,
                onOpenMessage = { navController.navigate(MessageDest(it)) },
                onOpenConversation = { navController.navigate(ConversationDest(it)) },
                onOpenReliabilityHelp = { navController.navigate(ReliabilityDest) },
                onLockNow = onLockNow,
                conversationPane = { id, onClose ->
                    CompositionLocalProvider(LocalScreenInsets provides noInsets) {
                        ConversationRoute(
                            viewModel = containerViewModel(key = "conversation-$id") { conversationViewModel(id, it) },
                            onBack = null,
                            onClosed = onClose,
                            onOpenMessage = { navController.navigate(MessageDest(it)) },
                            onVisibleChanged = { visible -> container.onConversationVisible(id, visible) },
                            appDirectory = container.appDirectory,
                        )
                    }
                },
            )
        }
        composable<SearchDest> {
            SearchRoute(containerViewModel { SearchViewModel(it.vaultRepository) }) { navController.navigate(MessageDest(it)) }
        }
        composable<FavoritesDest> {
            FavoritesRoute(containerViewModel { FavoritesViewModel(it.vaultRepository) }) { navController.navigate(MessageDest(it)) }
        }
        composable<SettingsDest> {
            SettingsRoute(
                viewModel = containerViewModel { settingsViewModelFactory(it) },
                backupViewModel = containerViewModel { BackupViewModel(it.backupManager, it.settingsRepository) },
                accessSettingsIntent = { container.listenerStatus.accessSettingsIntent() },
                onOpenExclusions = { navController.navigate(ExclusionsDest) },
                onOpenSelectedApps = { navController.navigate(SelectedAppsDest) },
                onOpenReliability = { navController.navigate(ReliabilityDest) },
                onOpenAbout = { navController.navigate(AboutDest) },
                showDeveloperOptions = BuildConfig.DEBUG,
            )
        }
        composable<ConversationDest> { backStackEntry ->
            val back = rememberBack(navController)
            val id = backStackEntry.toRoute<ConversationDest>().id
            ConversationRoute(
                viewModel = containerViewModel { conversationViewModel(id, it) },
                onBack = { back() },
                onClosed = { back() },
                onOpenMessage = { navController.navigate(MessageDest(it)) },
                onVisibleChanged = { visible -> container.onConversationVisible(id, visible) },
                appDirectory = container.appDirectory,
            )
        }
        composable<MessageDest> { backStackEntry ->
            val back = rememberBack(navController)
            val id = backStackEntry.toRoute<MessageDest>().id
            MessageDetailRoute(containerViewModel { MessageDetailViewModel(id, it.vaultRepository) }) { back() }
        }
        composable<ExclusionsDest> {
            val back = rememberBack(navController)
            ExclusionsRoute(containerViewModel { settingsViewModelFactory(it) }, onBack = { back() })
        }
        composable<SelectedAppsDest> {
            val back = rememberBack(navController)
            ExclusionsRoute(containerViewModel { settingsViewModelFactory(it) }, onBack = { back() }, mode = AppPickerMode.SELECT)
        }
        composable<ReliabilityDest> {
            val back = rememberBack(navController)
            ReliabilityRoute(accessSettingsIntent = { container.listenerStatus.accessSettingsIntent() }) { back() }
        }
        composable<AboutDest> {
            val back = rememberBack(navController)
            AboutRoute { back() }
        }
    }
}

/**
 * Back action bound to the current back-stack entry's lifecycle: repeated taps during the exit transition are ignored,
 * so the user can never pop past the start destination into an empty host.
 */
@Composable
private fun rememberBack(navController: NavHostController): () -> Unit = dropUnlessResumed { navController.popBackStack() }
