package com.ankit.destination.policy

import com.ankit.destination.data.GlobalControls

enum class ModeState {
    NORMAL,
    NUCLEAR
}

data class AllowlistResolution(
    val packages: Set<String>,
    val reasons: Map<String, String>
)

data class PolicyState(
    val mode: ModeState,
    val lockTaskAllowlist: Set<String>,
    val lockTaskFeatures: Int,
    val statusBarDisabled: Boolean,
    val suspendTargets: Set<String>,
    val previouslySuspended: Set<String>,
    val uninstallProtectedPackages: Set<String>,
    val previouslyUninstallProtectedPackages: Set<String>,
    val restrictions: Set<String>,
    val enforceRestrictions: Boolean,
    val blockSelfUninstall: Boolean,
    val requireAutoTime: Boolean,
    val emergencyApps: Set<String>,
    val allowlistReasons: Map<String, String>,
    val vpnRequired: Boolean,
    val alwaysOnVpnLockdown: Boolean,
    val lockReason: String?,
    val budgetBlockedPackages: Set<String>,
    val touchGrassBreakActive: Boolean,
    val primaryReasonByPackage: Map<String, String>,
    val globalControls: GlobalControls
)

data class ApplyResult(
    val failedToSuspend: Set<String>,
    val failedToUnsuspend: Set<String>,
    val failedToProtectUninstall: Set<String>,
    val failedToUnprotectUninstall: Set<String>,
    val errors: List<String>
)

data class PolicyVerificationResult(
    val passed: Boolean,
    val issues: List<String>,
    val suspendedChecked: Int,
    val suspendedMismatchCount: Int,
    val lockTaskModeActive: Boolean?
)

data class EngineResult(
    val success: Boolean,
    val message: String,
    val verification: PolicyVerificationResult,
    val state: PolicyState
)

data class DiagnosticsSnapshot(
    val deviceOwner: Boolean,
    val desiredMode: ModeState,
    val manualMode: ModeState,
    val scheduleLockComputed: Boolean,
    val scheduleLockActive: Boolean,
    val scheduleStrictComputed: Boolean,
    val scheduleStrictActive: Boolean,
    val scheduleBlockedGroups: Set<String>,
    val scheduleLockReason: String?,
    val scheduleNextTransitionAtMs: Long?,
    val budgetBlockedPackages: Set<String>,
    val budgetBlockedGroupIds: Set<String>,
    val budgetReason: String?,
    val budgetUsageAccessGranted: Boolean,
    val budgetNextCheckAtMs: Long?,
    val touchGrassBreakActive: Boolean,
    val touchGrassBreakUntilMs: Long?,
    val unlockCountToday: Int,
    val unlockCountDay: String?,
    val touchGrassThreshold: Int,
    val touchGrassBreakMinutes: Int,
    val lockTaskPackages: Set<String>,
    val lockTaskFeatures: Int?,
    val statusBarDisabled: Boolean?,
    val lastAppliedAtMs: Long,
    val lastVerificationPassed: Boolean,
    val lastError: String?,
    val lastSuspendedPackages: Set<String>,
    val restrictions: Map<String, Boolean>,
    val vpnActive: Boolean,
    val vpnRequiredForNuclear: Boolean,
    val vpnLockdownRequired: Boolean,
    val vpnLastError: String?,
    val alwaysOnVpnPackage: String?,
    val alwaysOnVpnLockdown: Boolean?,
    val domainRuleCount: Int,
    val currentLockReason: String?,
    val emergencyApps: Set<String>,
    val allowlistReasons: Map<String, String>,
    val alwaysAllowedApps: Set<String>,
    val alwaysBlockedApps: Set<String>,
    val uninstallProtectedApps: Set<String>,
    val globalControls: GlobalControls,
    val primaryReasonByPackage: Map<String, String>
)
