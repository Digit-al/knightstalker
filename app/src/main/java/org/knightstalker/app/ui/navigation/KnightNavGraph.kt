// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import org.knightstalker.app.ui.search.SearchScreen
import org.knightstalker.app.ui.search.SearchViewModel
import org.knightstalker.app.ui.settings.SettingsScreen
import org.knightstalker.app.ui.settings.SettingsViewModel
import org.knightstalker.app.ui.theme.DarkBackground
import org.knightstalker.app.ui.theme.DarkSurface
import org.knightstalker.app.ui.theme.Accent
import org.knightstalker.app.ui.theme.TextSecondary
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
    data object Player : NavRoute("player/{type}/{cmd}/{name}") {
        fun createRoute(type: String, cmd: String, name: String): String {
            val encodedCmd = URLEncoder.encode(cmd, "UTF-8")
            val encodedName = URLEncoder.encode(name, "UTF-8")
            return "player/$type/$encodedCmd/$encodedName"
        }
    }
}

data class BottomNavItem(val label: String, val icon: ImageVector, val route: String)

private val bottomNavItems = listOf(
    BottomNavItem("Live TV", Icons.Default.LiveTv, "livetv"),
    BottomNavItem("Guide", Icons.Default.CalendarMonth, "epg"),
    BottomNavItem("Movies", Icons.Default.Movie, "vod"),
    BottomNavItem("History", Icons.Default.History, "history"),
    BottomNavItem("Favorites", Icons.Default.Favorite, "favorites"),
    BottomNavItem("Search", Icons.Default.Search, "search"),
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
                factory = PortalViewModel.Factory(module.portalRepository, module.authManager, module.userPreferences)
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
                factory = PortalViewModel.Factory(module.portalRepository, module.authManager, module.userPreferences)
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
            route = NavRoute.Player.route,
            arguments = listOf(
                navArgument("type") { type = NavType.StringType },
                navArgument("cmd") { type = NavType.StringType },
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

    Scaffold(
        containerColor = DarkBackground,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = DarkSurface) {
                    bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                homeNavController.navigate(item.route) {
                                    popUpTo(homeNavController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Accent,
                                selectedTextColor = Accent,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary,
                                indicatorColor = Accent.copy(alpha = 0.15f)
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            NavHost(navController = homeNavController, startDestination = "livetv") {
                composable("livetv") {
                    val vm: LiveTvViewModel = viewModel(
                        factory = LiveTvViewModel.Factory(module.channelRepository, module.epgRepository, module.favoriteRepository, module.authManager)
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
                        factory = VodViewModel.Factory(module.vodRepository, module.favoriteRepository, module.downloadRepository, module.authManager)
                    )
                    VodScreen(
                        viewModel = vm,
                        onVodClick = { id, cmd ->
                            navController.navigate(NavRoute.Player.createRoute("vod", cmd, ""))
                        },
                        onPlayVod = { cmd ->
                            navController.navigate(NavRoute.Player.createRoute("vod", cmd, ""))
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

                composable("search") {
                    val vm: SearchViewModel = viewModel(
                        factory = SearchViewModel.Factory(module.channelRepository, module.vodRepository, module.authManager)
                    )
                    SearchScreen(
                        viewModel = vm,
                        onChannelClick = { id, cmd ->
                            navController.navigate(NavRoute.Player.createRoute("itv", cmd, ""))
                        },
                        onVodClick = { id, cmd ->
                            navController.navigate(NavRoute.Player.createRoute("vod", cmd, ""))
                        }
                    )
                }

                composable("settings") {
                    val vm: SettingsViewModel = viewModel(
                        factory = SettingsViewModel.Factory(module.userPreferences, module.authManager, module.portalRepository)
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
