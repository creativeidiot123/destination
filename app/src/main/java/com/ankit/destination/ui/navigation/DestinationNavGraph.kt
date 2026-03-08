package com.ankit.destination.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AppLockManager
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
    policyEngine: PolicyEngine,
    appLockManager: AppLockManager,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = DeviceControlsRoute,
        modifier = modifier
    ) {

        composable<DeviceControlsRoute> {
            DeviceControlsScreen(
                policyEngine = policyEngine,
                appLockManager = appLockManager
            )
        }
        composable<AppRulesRoute> {
            AppRulesScreen(
                policyEngine = policyEngine,
                appLockManager = appLockManager
            )
        }
        composable<GroupListRoute> {
            GroupListScreen(
                policyEngine = policyEngine,
                appLockManager = appLockManager,
                onNavigateToGroupDetail = { groupId ->
                    navController.navigate(GroupDetailRoute(groupId))
                }
            )
        }
        composable<GroupDetailRoute> { backStackEntry ->
            val route: GroupDetailRoute = backStackEntry.toRoute()
            GroupDetailScreen(
                groupId = route.groupId,
                policyEngine = policyEngine,
                appLockManager = appLockManager,
                onBack = { navController.popBackStack() }
            )
        }
        composable<IndividualAppsRoute> {
            IndividualAppsScreen(
                policyEngine = policyEngine,
                appLockManager = appLockManager,
                onNavigateToAppDetail = { pkg ->
                    navController.navigate(AppDetailRoute(pkg))
                }
            )
        }
        composable<AppDetailRoute> { backStackEntry ->
            val route: AppDetailRoute = backStackEntry.toRoute()
            AppDetailScreen(
                packageName = route.packageName,
                policyEngine = policyEngine,
                appLockManager = appLockManager,
                onBack = { navController.popBackStack() }
            )
        }
        composable<DangerZoneRoute> {
            DangerZoneScreen(
                policyEngine = policyEngine,
                appLockManager = appLockManager
            )
        }

        composable<DiagnosticsRoute> {
            DiagnosticsScreen(
                policyEngine = policyEngine,
                appLockManager = appLockManager
            )
        }
    }
}
