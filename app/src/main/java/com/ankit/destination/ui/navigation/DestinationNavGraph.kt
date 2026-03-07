package com.ankit.destination.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.ankit.destination.ui.apprules.AppRulesScreen
import com.ankit.destination.ui.apps.AppDetailScreen
import com.ankit.destination.ui.apps.IndividualAppsScreen
import com.ankit.destination.ui.danger.DangerZoneScreen
import com.ankit.destination.ui.device.DeviceControlsScreen
import com.ankit.destination.ui.diagnostics.DiagnosticsScreen
import com.ankit.destination.ui.groups.GroupDetailScreen
import com.ankit.destination.ui.groups.GroupListScreen
import kotlinx.serialization.Serializable

@Serializable object DeviceControlsRoute
@Serializable object AppRulesRoute
@Serializable object GroupListRoute
@Serializable data class GroupDetailRoute(val groupId: String)
@Serializable object IndividualAppsRoute
@Serializable data class AppDetailRoute(val packageName: String)
@Serializable object DangerZoneRoute
@Serializable object DiagnosticsRoute

@Composable
fun DestinationNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = DeviceControlsRoute,
        modifier = modifier
    ) {

        composable<DeviceControlsRoute> {
            DeviceControlsScreen()
        }
        composable<AppRulesRoute> {
            AppRulesScreen()
        }
        composable<GroupListRoute> {
            GroupListScreen(
                onNavigateToGroupDetail = { groupId ->
                    navController.navigate(GroupDetailRoute(groupId))
                }
            )
        }
        composable<GroupDetailRoute> { backStackEntry ->
            val route: GroupDetailRoute = backStackEntry.toRoute()
            GroupDetailScreen(
                groupId = route.groupId,
                onBack = { navController.popBackStack() }
            )
        }
        composable<IndividualAppsRoute> {
            IndividualAppsScreen(
                onNavigateToAppDetail = { pkg ->
                    navController.navigate(AppDetailRoute(pkg))
                }
            )
        }
        composable<AppDetailRoute> { backStackEntry ->
            val route: AppDetailRoute = backStackEntry.toRoute()
            AppDetailScreen(
                packageName = route.packageName,
                onBack = { navController.popBackStack() }
            )
        }
        composable<DangerZoneRoute> {
            DangerZoneScreen()
        }

        composable<DiagnosticsRoute> {
            DiagnosticsScreen()
        }
    }
}
