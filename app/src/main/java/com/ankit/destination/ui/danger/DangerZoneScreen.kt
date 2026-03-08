package com.ankit.destination.ui.danger

import androidx.compose.runtime.Composable
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AppLockManager
import com.ankit.destination.ui.diagnostics.DiagnosticsScreen

@Composable
fun DangerZoneScreen(
    policyEngine: PolicyEngine,
    appLockManager: AppLockManager
) {
    DiagnosticsScreen(
        policyEngine = policyEngine,
        appLockManager = appLockManager
    )
}
