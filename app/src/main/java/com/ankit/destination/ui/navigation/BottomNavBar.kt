package com.ankit.destination.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState

@Composable
fun DestinationBottomBar(navController: NavController) {
    val items = listOf(
        BottomNavItem("Overview", DeviceControlsRoute, Icons.Default.Home),
        // Tab 2: Global rule sets (Allowlist, blocklists, uninstall protection)
        BottomNavItem("Rules", AppRulesRoute, Icons.Default.Warning),
        // Tab 3: Group based schedule settings and rules
        BottomNavItem("Groups", GroupListRoute, Icons.Default.Group),
        // Tab 4: Individual app limits and charts
        BottomNavItem("Apps", IndividualAppsRoute, Icons.Default.Apps),
        BottomNavItem("Debug", DiagnosticsRoute, Icons.Default.Warning)
    )

    androidx.compose.material3.NavigationBar(
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer,
        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination

        items.forEach { item ->
            val isSelected = currentDestination.matchesRoot(item.route)

            NavigationBarItem(
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
                selected = isSelected,
                colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                    selectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    indicatorColor = androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer,
                    unselectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                ),
                onClick = {
                    navController.navigate(item.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    }
}

data class BottomNavItem(
    val label: String,
    val route: Any,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

private fun NavDestination?.matchesRoot(route: Any): Boolean {
    val destination = this ?: return false
    return when (route) {
        DeviceControlsRoute -> destination.hierarchy.any { it.hasRoute(DeviceControlsRoute::class) }
        AppRulesRoute -> destination.hierarchy.any { it.hasRoute(AppRulesRoute::class) }
        GroupListRoute -> destination.hierarchy.any {
            it.hasRoute(GroupListRoute::class) || it.hasRoute(GroupDetailRoute::class)
        }
        IndividualAppsRoute -> destination.hierarchy.any {
            it.hasRoute(IndividualAppsRoute::class) || it.hasRoute(AppDetailRoute::class)
        }
        DiagnosticsRoute -> destination.hierarchy.any {
            it.hasRoute(DiagnosticsRoute::class) || it.hasRoute(DangerZoneRoute::class)
        }
        else -> destination.hasRoute(route::class)
    }
}
