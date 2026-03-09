package com.ankit.destination.policy

import com.ankit.destination.data.GlobalControls

enum class ModeState {
    NORMAL
}

enum class UsageSnapshotStatus {
    OK,
    ACCESS_MISSING,
    INGESTION_FAILED;

    val usageAccessGranted: Boolean
        get() = this == OK

    companion object {
        fun fromUsageAccessGranted(granted: Boolean): UsageSnapshotStatus {
            return if (granted) OK else ACCESS_MISSING
        }
    }
}

data class AllowlistResolution(
    val packages: Set<String>,
    val reasons: Map<String, String>
)

data class UsageAccessComplianceState(
    val snapshotStatus: UsageSnapshotStatus,
    val usageAccessGranted: Boolean,
    val lockdownEligible: Boolean,
    val lockdownActive: Boolean,
    val recoveryAllowlist: Set<String>,
    val reason: String?
)

data class AccessibilityComplianceState(
    val accessibilityServiceEnabled: Boolean,
    val accessibilityServiceRunning: Boolean,
    val lockdownEligible: Boolean,
    val lockdownActive: Boolean,
    val recoveryAllowlist: Set<String>,
    val reason: String?
)

data class RecoveryLockdownState(
    val active: Boolean,
    val allowlist: Set<String>,
    val allowlistReasons: Map<String, String>,
    val reasonTokens: Set<String>,
    val reason: String?
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
    val managedNetworkPolicy: ManagedNetworkPolicy = ManagedNetworkPolicy.Unmanaged,
    val lockReason: String?,
    val budgetBlockedPackages: Set<String>,
    val touchGrassBreakActive: Boolean,
    val primaryReasonByPackage: Map<String, String>,
    val blockReasonsByPackage: Map<String, Set<String>> = emptyMap(),
    val globalControls: GlobalControls
)

data class DesiredDevicePolicyState(
    val suspendTargets: Set<String>,
    val uninstallProtected: Set<String>,
    val userRestrictions: Set<String>,
    val managedNetworkPolicy: ManagedNetworkPolicy,
    val lockTaskPackages: Set<String>,
    val lockTaskFeatures: Int,
    val requireAutoTime: Boolean,
    val statusBarDisabled: Boolean
)

data class ObservedDevicePolicyState(
    val suspendedPackages: Set<String> = emptySet(),
    val suspensionAuthoritative: Boolean = false,
    val uninstallProtectedPackages: Set<String> = emptySet(),
    val uninstallProtectionAuthoritative: Boolean = false,
    val userControlDisabledPackages: Set<String> = emptySet(),
    val userControlDisabledAuthoritative: Boolean = false,
    val userRestrictions: Set<String> = emptySet(),
    val lockTaskPackages: Set<String> = emptySet(),
    val lockTaskFeatures: Int? = null,
    val autoTimeRequired: Boolean? = null
)

data class PolicyApplyDelta(
    val toSuspend: Set<String>,
    val toUnsuspend: Set<String>,
    val toProtectUninstall: Set<String>,
    val toUnprotectUninstall: Set<String>,
    val restrictionsToAdd: Set<String>,
    val restrictionsToClear: Set<String>,
    val shouldUpdateLockTaskPackages: Boolean,
    val shouldUpdateLockTaskFeatures: Boolean,
    val shouldUpdateAutoTime: Boolean
)

enum class PolicyEnforcementCategory {
    CORE,
    SUPPORTING,
    COSMETIC
}

enum class PolicyApplyPhase {
    PREPARE,
    REVERSIBLE,
    BEST_EFFORT,
    VERIFY,
    REPAIR
}

data class PolicyApplyPhaseResult(
    val phase: PolicyApplyPhase,
    val category: PolicyEnforcementCategory,
    val successful: Boolean,
    val errors: List<String>
)

data class PolicyRepairPlan(
    val required: Boolean,
    val delayMs: Long?,
    val reason: String?
)

data class ApplyResult(
    val failedToSuspend: Set<String>,
    val failedToUnsuspend: Set<String>,
    val failedToProtectUninstall: Set<String>,
    val failedToUnprotectUninstall: Set<String>,
    val errors: List<String>,
    val observedState: ObservedDevicePolicyState = ObservedDevicePolicyState(),
    val phaseResults: List<PolicyApplyPhaseResult> = emptyList(),
    val repairPlan: PolicyRepairPlan? = null,
    val verification: PolicyVerificationResult? = null,
    val coreFailure: Boolean = false,
    val supportingFailure: Boolean = false
)

data class PolicyVerificationResult(
    val passed: Boolean,
    val issues: List<String>,
    val suspendedChecked: Int,
    val suspendedMismatchCount: Int,
    val lockTaskModeActive: Boolean?,
    val coreIssues: List<String> = emptyList(),
    val supportingIssues: List<String> = emptyList(),
    val cosmeticIssues: List<String> = emptyList()
)

data class EngineResult(
    val success: Boolean,
    val message: String,
    val verification: PolicyVerificationResult,
    val state: PolicyState,
    val repairPlan: PolicyRepairPlan? = null
)

data class DiagnosticsSnapshot(
    val deviceOwner: Boolean,
    val desiredMode: ModeState,
    val manualMode: ModeState,
    val usageSnapshotStatus: UsageSnapshotStatus,
    val usageAccessGranted: Boolean,
    val accessibilityServiceEnabled: Boolean,
    val accessibilityServiceRunning: Boolean,
    val accessibilityDegradedReason: String?,
    val usageAccessRecoveryLockdownActive: Boolean,
    val usageAccessRecoveryAllowlist: Set<String>,
    val usageAccessRecoveryReason: String?,
    val lastUsageAccessCheckAtMs: Long,
    val lastAccessibilityStatusCheckAtMs: Long,
    val lastAccessibilityServiceConnectAtMs: Long?,
    val lastAccessibilityHeartbeatAtMs: Long?,
    val scheduleLockComputed: Boolean,
    val scheduleLockActive: Boolean,
    val scheduleStrictComputed: Boolean,
    val scheduleStrictActive: Boolean,
    val scheduleBlockedGroups: Set<String>,
    val scheduleBlockedPackages: Set<String>,
    val scheduleLockReason: String?,
    val scheduleTargetWarning: String?,
    val scheduleTargetDiagnosticCode: ScheduleTargetDiagnosticCode,
    val scheduleNextTransitionAtMs: Long?,
    val budgetBlockedPackages: Set<String>,
    val budgetBlockedGroupIds: Set<String>,
    val budgetReason: String?,
    val budgetUsageSnapshotStatus: UsageSnapshotStatus,
    val budgetUsageAccessGranted: Boolean,
    val budgetNextCheckAtMs: Long?,
    val nextPolicyWakeAtMs: Long?,
    val nextPolicyWakeReason: String?,
    val touchGrassBreakActive: Boolean,
    val touchGrassBreakUntilMs: Long?,
    val unlockCountToday: Int,
    val unlockCountDay: String?,
    val touchGrassThreshold: Int,
    val touchGrassBreakMinutes: Int,
    val lockTaskPackages: Set<String>,
    val lockTaskFeatures: Int?,
    val statusBarDisabledObserved: Boolean?,
    val statusBarDisabledExpected: Boolean,
    val lastAppliedAtMs: Long,
    val lastVerificationPassed: Boolean,
    val lastError: String?,
    val hiddenSuspendPrototypeEnabled: Boolean,
    val packageSuspendBackend: String?,
    val packageSuspendPrototypeError: String?,
    val lastSuspendedPackages: Set<String>,
    val restrictions: Map<String, Boolean>,
    val vpnActive: Boolean,
    val vpnLockdownRequired: Boolean,
    val vpnLastError: String?,
    val alwaysOnVpnPackage: String?,
    val alwaysOnVpnLockdown: Boolean?,
    val privateDnsMode: Int?,
    val privateDnsHost: String?,
    val managedNetworkMode: String,
    val managedVpnPackage: String?,
    val managedVpnLockdown: Boolean?,
    val managedPrivateDnsHost: String?,
    val domainRuleCount: Int,
    val currentLockReason: String?,
    val emergencyApps: Set<String>,
    val allowlistReasons: Map<String, String>,
    val alwaysAllowedApps: Set<String>,
    val alwaysBlockedApps: Set<String>,
    val uninstallProtectedApps: Set<String>,
    val globalControls: GlobalControls,
    val primaryReasonByPackage: Map<String, String>,
    val packageDiagnostics: List<PackageDiagnostics>
)

data class DashboardSnapshot(
    val deviceOwner: Boolean,
    val usageSnapshotStatus: UsageSnapshotStatus,
    val usageAccessGranted: Boolean,
    val accessibilityServiceEnabled: Boolean,
    val accessibilityServiceRunning: Boolean,
    val accessibilityDegradedReason: String?,
    val usageAccessRecoveryLockdownActive: Boolean,
    val usageAccessRecoveryReason: String?,
    val scheduleBlockedGroupsCount: Int,
    val scheduleStrictActive: Boolean,
    val totalBlockedApps: Int,
    val lastAppliedAtMs: Long,
    val lastError: String?,
    val nextPolicyWakeAtMs: Long?,
    val nextPolicyWakeReason: String?,
    val vpnActive: Boolean
)

enum class PackageDiagnosticsDisposition {
    SUSPEND_TARGET,
    ELIGIBLE_NOT_ACTIVE,
    ALLOWLIST_EXCLUDED,
    HIDDEN,
    RUNTIME_EXEMPT,
    PROTECTED,
    NOT_INSTALLED
}

data class PackageDiagnostics(
    val packageName: String,
    val activeReasons: Set<String>,
    val primaryReason: String?,
    val disposition: PackageDiagnosticsDisposition,
    val allowlistReason: String?,
    val protectionReason: String?,
    val hiddenLocked: Boolean,
    val fromStrictInstallSuspended: Boolean,
    val nextPotentialClearEvent: String
)
