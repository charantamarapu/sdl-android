package com.sdl.grantha.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.sdl.grantha.ui.screens.*
import com.sdl.grantha.ui.theme.*
import java.net.URLDecoder
import java.net.URLEncoder

sealed class Screen(val route: String) {
    data object Library : Screen("library")
    data object Search : Screen("search")
    data object Settings : Screen("settings")
    data object Reader : Screen("reader/{granthaName}/{page}?highlight={highlight}") {
        fun createRoute(granthaName: String, page: Int, highlight: String? = null): String {
            val encodedName = URLEncoder.encode(granthaName, "UTF-8").replace("+", "%20")
            val base = "reader/$encodedName/$page"
            return if (highlight != null) {
                val encodedHighlight = URLEncoder.encode(highlight, "UTF-8").replace("+", "%20")
                "$base?highlight=$encodedHighlight"
            } else base
        }
    }
}

data class BottomNavItem(
    val screen: Screen,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

val bottomNavItems = listOf(
    BottomNavItem(Screen.Library, "Library", Icons.Filled.LibraryBooks, Icons.Outlined.LibraryBooks),
    BottomNavItem(Screen.Search, "Search", Icons.Filled.Search, Icons.Outlined.Search),
    BottomNavItem(Screen.Settings, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
)

@Composable
fun NavGraph() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Show bottom bar only on main screens
    val showBottomBar = currentDestination?.route in bottomNavItems.map { it.screen.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == item.screen.route
                        } == true

                        NavigationBarItem(
                            icon = {
                                Icon(
                                    if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.label
                                )
                            },
                            label = { Text(item.label) },
                            selected = selected,
                            onClick = {
                                navController.navigate(item.screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Library.route,
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            composable(Screen.Library.route) {
                LibraryScreen(
                    onNavigateToReader = { name, page ->
                        navController.navigate(Screen.Reader.createRoute(name, page))
                    }
                )
            }

            composable(Screen.Search.route) {
                SearchScreen(
                    onNavigateToReader = { name, page, highlight ->
                        navController.navigate(Screen.Reader.createRoute(name, page, highlight))
                    }
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen()
            }

            composable(
                route = Screen.Reader.route,
                arguments = listOf(
                    navArgument("granthaName") { type = NavType.StringType },
                    navArgument("page") { type = NavType.IntType; defaultValue = 1 },
                    navArgument("highlight") { type = NavType.StringType; nullable = true; defaultValue = null }
                )
            ) { backStackEntry ->
                val encodedName = backStackEntry.arguments?.getString("granthaName") ?: ""
                val granthaName = URLDecoder.decode(encodedName, "UTF-8")
                val page = backStackEntry.arguments?.getInt("page") ?: 1
                val highlight = backStackEntry.arguments?.getString("highlight")

                ReaderScreen(
                    granthaName = granthaName,
                    startPage = page,
                    highlightQuery = highlight,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
