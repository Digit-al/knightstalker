// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.navigation

import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.knightstalker.app.KnightStalkerApp
import org.knightstalker.app.ui.epg.EpgScreen
import org.knightstalker.app.ui.epg.EpgViewModel
import org.knightstalker.app.ui.favorites.FavoritesScreen
import org.knightstalker.app.ui.favorites.FavoritesViewModel
import org.knightstalker.app.ui.history.HistoryScreen
import org.knightstalker.app.ui.history.HistoryViewModel
import org.knightstalker.app.ui.livetv.LiveTvScreen
import org.knightstalker.app.ui.livetv.LiveTvViewModel
import org.knightstalker.app.ui.player.PlayerScreen
import org.knightstalker.app.ui.player.PlayerViewModel
import org.knightstalker.app.ui.portal.PortalEditScreen
import org.knightstalker.app.ui.portal.PortalListScreen
import org.knightstalker.app.ui.portal.PortalViewModel
import org.knightstalker.app.data.repository.DownloadStatus
import org.knightstalker.app.ui.downloads.DownloadsScreen
import org.knightstalker.app.ui.settings.SettingsScreen
import org.knightstalker.app.ui.settings.SettingsViewModel
import org.knightstalker.app.ui.theme.DarkBackground
import org.knightstalker.app.ui.theme.DarkSurface
import org.knightstalker.app.ui.theme.Accent
import org.knightstalker.app.ui.theme.TextSecondary
import org.knightstalker.app.ui.vod.ContentMode
import org.knightstalker.app.ui.vod.VodScreen
import org.knightstalker.app.ui.vod.VodViewModel
import java.net.URLDecoder
import java.net.URLEncoder

sealed class NavRoute(val route: String) {
    data object Portals : NavRoute("portals")
    data object PortalEdit : NavRoute("portals/edit?id={id}") {
        fun createRoute(id: Long? = null) = if (id != null) "portals/edit?id=$id" else "portals/edit"
    }
    data object Home : NavRoute("home")
    data object Player : NavRoute("player/{type}?cmd={cmd}&name={name}") {
        fun createRoute(type: String, cmd: String, name: String): String {
            val encodedCmd = URLEncoder.encode(cmd, "UTF-8")
            val encodedName = URLEncoder.encode(name, "UTF-8")
            return "player/$type?cmd=$encodedCmd&name=$encodedName"
        }
    }
}

data class BottomNavItem(val label: String, val icon: ImageVector, val route: String)

private val bottomNavItems = listOf(
    BottomNavItem("Movies", Icons.Default.Movie, "vod"),
    BottomNavItem("Series", Icons.Default.Tv, "series"),
    BottomNavItem("Live TV", Icons.Default.LiveTv, "livetv"),
    BottomNavItem("History", Icons.Default.History, "history"),
    BottomNavItem("Guide", Icons.Default.CalendarMonth, "epg"),
    BottomNavItem("Favorites", Icons.Default.Favorite, "favorites"),
    BottomNavItem("Downloads", Icons.Default.Download, "downloads"),
    BottomNavItem("Settings", Icons.Default.Settings, "settings")
)

@Composable
fun KnightNavGraph() {
    val context = LocalContext.current
    val app = context.applicationContext as KnightStalkerApp
    val module = app.appModule
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = NavRoute.Portals.route) {
        // Portal list
        composable(NavRoute.Portals.route) {
            val vm: PortalViewModel = viewModel(
                factory = PortalViewModel.Factory(module.portalRepository, module.authManager, module.userPreferences, module.mediaIndexRepository)
            )
            PortalListScreen(
                viewModel = vm,
                onAddPortal = { navController.navigate(NavRoute.PortalEdit.createRoute()) },
                onEditPortal = { id -> navController.navigate(NavRoute.PortalEdit.createRoute(id)) },
                onConnected = {
                    navController.navigate(NavRoute.Home.route) {
                        popUpTo(NavRoute.Portals.route) { inclusive = true }
                    }
                }
            )
        }

        // Portal edit
        composable(
            route = "portals/edit?id={id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = 0L })
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            val vm: PortalViewModel = viewModel(
                factory = PortalViewModel.Factory(module.portalRepository, module.authManager, module.userPreferences, module.mediaIndexRepository)
            )
            val portals by vm.state.collectAsState()
            val portal = portals.portals.find { it.id == id }

            PortalEditScreen(
                portal = portal,
                onSave = { name, url, mac, existingId ->
                    vm.savePortal(name, url, mac, existingId)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() }
            )
        }

        // Home with bottom nav
        composable(NavRoute.Home.route) {
            HomeScreen(module = module, navController = navController)
        }

        // Player
        composable(
            route = "player/{type}?cmd={cmd}&name={name}",
            arguments = listOf(
                navArgument("type") { type = NavType.StringType },
                navArgument("cmd") { type = NavType.StringType; defaultValue = "" },
                navArgument("name") { type = NavType.StringType; defaultValue = "" }
            )
        ) { entry ->
            val type = entry.arguments?.getString("type") ?: "itv"
            val cmd = URLDecoder.decode(entry.arguments?.getString("cmd") ?: "", "UTF-8")
            val name = URLDecoder.decode(entry.arguments?.getString("name") ?: "", "UTF-8")
            val vm: PlayerViewModel = viewModel(
                factory = PlayerViewModel.Factory(context, module.channelRepository, module.vodRepository, module.authManager, module.downloadRepository, module.watchHistoryRepository)
            )
            PlayerScreen(
                viewModel = vm,
                type = type,
                cmd = cmd,
                name = name,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

// Routes cycled by Channel +/- and the LIST/TV buttons on a TV remote
private val cycleRoutes = listOf("vod", "series", "livetv")

// Set by HomeScreen so MainActivity.onKeyDown can forward TV remote keys.
internal var remoteKeyHandler: ((Int) -> Boolean)? = null

@Composable
private fun HomeScreen(
    module: org.knightstalker.app.di.AppModule,
    navController: androidx.navigation.NavController
) {
    val homeNavController = rememberNavController()
    val navBackStackEntry by homeNavController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Hide bottom bar when not on a main tab
    val showBottomBar = currentRoute in bottomNavItems.map { it.route }

    fun goToTab(route: String) {
        homeNavController.navigate(route) {
            popUpTo(homeNavController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun cycleTab(direction: Int) {
        val current = currentRoute ?: cycleRoutes[0]
        val idx = cycleRoutes.indexOf(current).takeIf { it >= 0 } ?: 0
        val next = cycleRoutes[((idx + direction) % cycleRoutes.size + cycleRoutes.size) % cycleRoutes.size]
        goToTab(next)
    }

    val routeState = rememberUpdatedState(currentRoute)
    DisposableEffect(homeNavController) {
        val routes = bottomNavItems.map { it.route }
        fun cycle(direction: Int) {
            val cur = routeState.value ?: cycleRoutes[0]
            val idx = cycleRoutes.indexOf(cur).takeIf { it >= 0 } ?: 0
            val next = cycleRoutes[((idx + direction) % cycleRoutes.size + cycleRoutes.size) % cycleRoutes.size]
            homeNavController.navigate(next) {
                popUpTo(homeNavController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        fun jump(route: String) {
            homeNavController.navigate(route) {
                popUpTo(homeNavController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        remoteKeyHandler = handler@{ keyCode ->
            // Only handle when on a home tab (don't hijack keys in player/portal screens)
            if (routeState.value !in routes) return@handler false
            when (keyCode) {
                // Cycle forward (next tab in Movies → Series → Live TV)
                KeyEvent.KEYCODE_CHANNEL_UP,
                KeyEvent.KEYCODE_BOOKMARK,
                KeyEvent.KEYCODE_TV,
                KeyEvent.KEYCODE_TV_INPUT,
                KeyEvent.KEYCODE_TV_TIMER_PROGRAMMING,
                KeyEvent.KEYCODE_PAGE_DOWN,
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_PROG_YELLOW -> { cycle(+1); true }
                // Cycle backward
                KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_PAGE_UP,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_PROG_GREEN -> { cycle(-1); true }
                // Direct jumps via colored buttons
                KeyEvent.KEYCODE_PROG_RED -> { jump("vod"); true }
                KeyEvent.KEYCODE_PROG_BLUE -> { jump("livetv"); true }
                else -> false
            }
        }
        onDispose { remoteKeyHandler = null }
    }

    // On startup, if downloads were interrupted (left Paused when the app last
    // closed), ask once whether to resume them. Resuming re-mints a fresh play
    // link, so we wait until Home — i.e. the portal is connected — to offer it.
    val downloads by module.downloadRepository.downloads.collectAsState()
    var resumePromptHandled by rememberSaveable { mutableStateOf(false) }
    val resumable = downloads.filter { it.status == DownloadStatus.PAUSED }
    if (!resumePromptHandled && resumable.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { resumePromptHandled = true },
            title = { Text("Resume downloads?") },
            text = {
                Text(
                    "You have ${resumable.size} unfinished download" +
                        (if (resumable.size > 1) "s" else "") + ". Resume now?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    resumePromptHandled = true
                    resumable.forEach { module.downloadRepository.resumeDownload(it.id) }
                }) { Text("Resume", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { resumePromptHandled = true }) {
                    Text("Not now", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }

    Scaffold(
        containerColor = DarkBackground,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = DarkSurface) {
                    bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = { goToTab(item.route) },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Accent,
                                unselectedIconColor = TextSecondary,
                                indicatorColor = Accent.copy(alpha = 0.15f)
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            NavHost(navController = homeNavController, startDestination = "vod") {
                composable("livetv") {
                    val vm: LiveTvViewModel = viewModel(
                        factory = LiveTvViewModel.Factory(module.channelRepository, module.epgRepository, module.favoriteRepository, module.authManager, module.userPreferences)
                    )
                    LiveTvScreen(
                        viewModel = vm,
                        onChannelClick = { id, cmd ->
                            navController.navigate(NavRoute.Player.createRoute("itv", cmd, ""))
                        }
                    )
                }

                composable("epg") {
                    val vm: EpgViewModel = viewModel(
                        factory = EpgViewModel.Factory(module.channelRepository, module.epgRepository, module.authManager)
                    )
                    EpgScreen(
                        viewModel = vm,
                        onChannelClick = { id, cmd ->
                            navController.navigate(NavRoute.Player.createRoute("itv", cmd, ""))
                        }
                    )
                }

                composable("vod") {
                    val vm: VodViewModel = viewModel(
                        factory = VodViewModel.Factory(module.vodRepository, module.favoriteRepository, module.downloadRepository, module.authManager, module.userPreferences, module.mediaIndexRepository, module.syncEngine, module.watchHistoryRepository, module.seriesRatingRepository, ContentMode.Vod)
                    )
                    VodScreen(
                        viewModel = vm,
                        onVodClick = { id, cmd ->
                            navController.navigate(NavRoute.Player.createRoute("vod", cmd, ""))
                        },
                        onPlayVod = { cmd ->
                            navController.navigate(NavRoute.Player.createRoute("vod", cmd, ""))
                        },
                        onOpenDownloads = { goToTab("downloads") }
                    )
                }

                composable("series") {
                    val vm: VodViewModel = viewModel(
                        key = "series",
                        factory = VodViewModel.Factory(module.vodRepository, module.favoriteRepository, module.downloadRepository, module.authManager, module.userPreferences, module.mediaIndexRepository, module.syncEngine, module.watchHistoryRepository, module.seriesRatingRepository, ContentMode.Series)
                    )
                    VodScreen(
                        viewModel = vm,
                        onVodClick = { _, _ -> },
                        onPlayVod = { },
                        onOpenDownloads = { goToTab("downloads") },
                        isSeries = true,
                        onPlayEpisode = { seasonCmd, episode, episodes, seriesName ->
                            navController.navigate(
                                NavRoute.Player.createRoute(
                                    "episode",
                                    "$seasonCmd|$episode|${episodes.joinToString(",")}",
                                    seriesName
                                )
                            )
                        }
                    )
                }

                composable("history") {
                    val vm: HistoryViewModel = viewModel(
                        factory = HistoryViewModel.Factory(module.watchHistoryRepository, module.authManager)
                    )
                    HistoryScreen(
                        viewModel = vm,
                        onResume = { type, cmd, name ->
                            navController.navigate(NavRoute.Player.createRoute(type, cmd, name))
                        }
                    )
                }

                composable("favorites") {
                    val vm: FavoritesViewModel = viewModel(
                        factory = FavoritesViewModel.Factory(module.favoriteRepository, module.authManager)
                    )
                    FavoritesScreen(
                        viewModel = vm,
                        onChannelClick = { id, cmd ->
                            navController.navigate(NavRoute.Player.createRoute("itv", cmd, ""))
                        },
                        onVodClick = { id, cmd ->
                            navController.navigate(NavRoute.Player.createRoute("vod", cmd, ""))
                        }
                    )
                }

                composable("downloads") {
                    DownloadsScreen(
                        downloadRepository = module.downloadRepository,
                        onPlay = { task ->
                            val path = task.filePath
                            if (path != null) {
                                navController.navigate(NavRoute.Player.createRoute("local", path, task.name))
                            }
                        }
                    )
                }

                composable("settings") {
                    val vm: SettingsViewModel = viewModel(
                        factory = SettingsViewModel.Factory(module.userPreferences, module.authManager, module.portalRepository, module.vodRepository, module.mediaIndexRepository, module.syncEngine)
                    )
                    SettingsScreen(
                        viewModel = vm,
                        onPortals = {
                            navController.navigate(NavRoute.Portals.route)
                        }
                    )
                }
            }
        }
    }
}
