package com.ankit.destination.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AppLockManager
import com.ankit.destination.ui.components.DeviceOwnerMissingOverlay
import com.ankit.destination.ui.components.UsageAccessRecoveryBanner
import com.ankit.destination.ui.components.collectAsStateWithLifecycleCompat
import com.ankit.destination.ui.navigation.DestinationBottomBar
import com.ankit.destination.ui.navigation.DestinationNavGraph
import com.ankit.destination.usage.UsageAccessMonitor

@Composable
fun DestinationApp(
    policyEngine: PolicyEngine,
    appLockManager: AppLockManager
) {
    val navController = rememberNavController()
    val usageAccessState by UsageAccessMonitor.currentState.collectAsStateWithLifecycleCompat()
    val isDeviceOwner = remember(usageAccessState.lastCheckAtMs) {
        policyEngine.isDeviceOwner()
    }
    val complianceState = remember(usageAccessState.usageAccessGranted, usageAccessState.lastCheckAtMs) {
        policyEngine.currentUsageAccessComplianceState(usageAccessState.usageAccessGranted)
    }
    
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val rootMaxWidth = 840.dp
        val isWide = maxWidth > rootMaxWidth
        val containerModifier = if (isWide) {
            Modifier.width(rootMaxWidth).fillMaxHeight()
        } else {
            Modifier.fillMaxSize()
        }

        Box(modifier = containerModifier) {
            Scaffold(
                bottomBar = {
                    DestinationBottomBar(navController)
                }
            ) { innerPadding ->
                Column(modifier = Modifier.padding(innerPadding)) {
                    if (complianceState.lockdownActive) {
                        UsageAccessRecoveryBanner(
                            message = complianceState.reason
                                ?: "Usage Access is missing. Only Destination, your launcher, and Settings remain available until it is restored.",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                    DestinationNavGraph(
                        navController = navController,
                        policyEngine = policyEngine,
                        appLockManager = appLockManager,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (!isDeviceOwner) {
                DeviceOwnerMissingOverlay()
            }
        }
    }
}
