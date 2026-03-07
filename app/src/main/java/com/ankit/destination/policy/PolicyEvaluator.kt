package com.ankit.destination.policy

import com.ankit.destination.data.GlobalControls

class PolicyEvaluator(
    private val packageResolver: PackageResolver,
    private val controllerPackageName: String
) {
    fun evaluate(
        mode: ModeState,
        emergencyApps: Set<String>,
        alwaysAllowedApps: Set<String>,
        alwaysBlockedApps: Set<String>,
        strictInstallBlockedPackages: Set<String> = emptySet(),
        uninstallProtectedApps: Set<String>,
        globalControls: GlobalControls,
        previouslySuspended: Set<String>,
        previouslyUninstallProtected: Set<String>,
        budgetBlockedPackages: Set<String>,
        primaryReasonByPackage: Map<String, String>,
        lockReason: String?,
        touchGrassBreakActive: Boolean,
        usageAccessComplianceState: UsageAccessComplianceState
    ): PolicyState {
        FocusLog.d(FocusEventId.POLICY_STATE_COMPUTED, "┌── PolicyEvaluator.evaluate() START ──")
        FocusLog.d(FocusEventId.POLICY_STATE_COMPUTED, "│ mode=$mode emergencyApps=${emergencyApps.size} alwaysAllowed=${alwaysAllowedApps.size} alwaysBlocked=${alwaysBlockedApps.size}")
        FocusLog.d(FocusEventId.POLICY_STATE_COMPUTED, "│ strictInstall=${strictInstallBlockedPackages.size} uninstallProtected=${uninstallProtectedApps.size} budgetBlocked=${budgetBlockedPackages.size}")
        FocusLog.d(FocusEventId.POLICY_STATE_COMPUTED, "│ lockReason=$lockReason touchGrassBreak=$touchGrassBreakActive prevSuspended=${previouslySuspended.size}")
        FocusLog.d(FocusEventId.USAGE_ACCESS_CHECK, "│ usageAccess: granted=${usageAccessComplianceState.usageAccessGranted} lockdownEligible=${usageAccessComplianceState.lockdownEligible} lockdownActive=${usageAccessComplianceState.lockdownActive}")

        val effectiveMode = ModeState.NORMAL
        if (usageAccessComplianceState.lockdownActive) {
            FocusLog.i(FocusEventId.USAGE_ACCESS_CHECK, "│ ⚠️ USAGE ACCESS RECOVERY LOCKDOWN ACTIVE — reason=${usageAccessComplianceState.reason}")
            val recoveryAllowlist = usageAccessComplianceState.recoveryAllowlist
            FocusLog.d(FocusEventId.USAGE_ACCESS_CHECK, "│ recoveryAllowlist=${recoveryAllowlist.size}: ${recoveryAllowlist.joinToString(",")}")
            val recoverySuspendTargets =
                packageResolver.computeUsageAccessRecoverySuspendTargets(recoveryAllowlist)
            FocusLog.d(FocusEventId.USAGE_ACCESS_CHECK, "│ recoverySuspendTargets=${recoverySuspendTargets.size}")
            val recoveryReasons = recoverySuspendTargets.associateWith {
                EffectiveBlockReason.USAGE_ACCESS_RECOVERY_LOCKDOWN.name
            }
            val allowlistReasons = recoveryAllowlist.associateWith { "usage access recovery" }
            val managedNetworkPolicy = globalControls.toManagedNetworkPolicy(controllerPackageName)
            val restrictions = PolicyRestrictions.build(
                mode = effectiveMode,
                globalControls = globalControls,
                managedNetworkPolicy = managedNetworkPolicy
            )
            FocusLog.d(FocusEventId.USAGE_ACCESS_CHECK, "│ restrictions=${restrictions.size} managedNetwork=$managedNetworkPolicy")
            FocusLog.d(FocusEventId.USAGE_ACCESS_CHECK, "└── PolicyEvaluator.evaluate() END (recovery lockdown) ──")
            return PolicyState(
                mode = effectiveMode,
                lockTaskAllowlist = recoveryAllowlist,
                lockTaskFeatures = FocusConfig.normalLockTaskFeatures,
                statusBarDisabled = false,
                suspendTargets = recoverySuspendTargets,
                previouslySuspended = previouslySuspended,
                uninstallProtectedPackages = uninstallProtectedApps
                    .asSequence()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .filter(packageResolver::isPackageInstalled)
                    .toSet(),
                previouslyUninstallProtectedPackages = previouslyUninstallProtected,
                restrictions = restrictions,
                enforceRestrictions = restrictions.isNotEmpty(),
                blockSelfUninstall = true,
                requireAutoTime = globalControls.lockTime,
                emergencyApps = emptySet(),
                allowlistReasons = allowlistReasons,
                vpnRequired = false,
                managedNetworkPolicy = managedNetworkPolicy,
                lockReason = usageAccessComplianceState.reason,
                budgetBlockedPackages = emptySet(),
                touchGrassBreakActive = false,
                primaryReasonByPackage = recoveryReasons,
                globalControls = globalControls
            )
        }
        val normalizedEmergency = emergencyApps.map(String::trim).filter(String::isNotBlank).toSet()
        val normalizedAlwaysAllowed = alwaysAllowedApps.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        FocusLog.d(FocusEventId.ALLOWLIST_RESOLVE, "│ Resolving allowlist: emergency=${normalizedEmergency.size} alwaysAllowed=${normalizedAlwaysAllowed.size}")
        val allowlistResolution = packageResolver.resolveAllowlist(
            userChosenEmergencyApps = normalizedEmergency,
            alwaysAllowedApps = normalizedAlwaysAllowed
        )
        FocusLog.d(FocusEventId.ALLOWLIST_RESOLVE, "│ Allowlist resolved: ${allowlistResolution.packages.size} packages")
        allowlistResolution.reasons.forEach { (pkg, reason) ->
            FocusLog.v(FocusEventId.ALLOWLIST_RESOLVE, "│   allowlist: $pkg → $reason")
        }

        val budgetBlockedSuspendable =
            packageResolver.filterSuspendable(
                budgetBlockedPackages,
                allowlistResolution.packages
            )
        FocusLog.d(FocusEventId.SUSPEND_TARGET, "│ budgetBlockedSuspendable=${budgetBlockedSuspendable.size} (from ${budgetBlockedPackages.size} budget blocked)")
        val alwaysBlockedSuspendable = packageResolver.filterSuspendable(
            packages = alwaysBlockedApps,
            allowlist = emptySet()
        )
        FocusLog.d(FocusEventId.SUSPEND_TARGET, "│ alwaysBlockedSuspendable=${alwaysBlockedSuspendable.size} (from ${alwaysBlockedApps.size} always blocked)")
        if (alwaysBlockedSuspendable.isNotEmpty()) {
            FocusLog.d(FocusEventId.SUSPEND_TARGET, "│   alwaysBlocked pkgs: ${alwaysBlockedSuspendable.joinToString(",")}")
        }
        val strictInstallSuspendable = packageResolver.filterSuspendable(
            packages = strictInstallBlockedPackages,
            allowlist = allowlistResolution.packages
        )
        FocusLog.d(FocusEventId.SUSPEND_TARGET, "│ strictInstallSuspendable=${strictInstallSuspendable.size} (from ${strictInstallBlockedPackages.size} strict install)")
        val managedNetworkPolicy = globalControls.toManagedNetworkPolicy(controllerPackageName)

        val nuclearSuspendTargets = packageResolver.computeSuspendTargets(allowlistResolution.packages)
        val suspendTargets = mergeSuspendTargets(
            effectiveMode = effectiveMode,
            nuclearSuspendTargets = nuclearSuspendTargets,
            budgetBlockedSuspendable = budgetBlockedSuspendable,
            alwaysBlockedSuspendable = alwaysBlockedSuspendable,
            strictInstallSuspendable = strictInstallSuspendable
        )
        FocusLog.d(FocusEventId.SUSPEND_TARGET, "│ MERGED suspendTargets=${suspendTargets.size} (nuclear=${nuclearSuspendTargets.size} budget=${budgetBlockedSuspendable.size} alwaysBlocked=${alwaysBlockedSuspendable.size} strict=${strictInstallSuspendable.size})")

        val restrictions = PolicyRestrictions.build(
            mode = effectiveMode,
            globalControls = globalControls,
            managedNetworkPolicy = managedNetworkPolicy
        )
        FocusLog.d(FocusEventId.LOCK_CALC, "│ restrictions=${restrictions.size}: ${restrictions.joinToString(",")}")
        val manualNuclear = false
        val uninstallProtectedTargets = uninstallProtectedApps
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .filter(packageResolver::isPackageInstalled)
            .toSet()
        FocusLog.d(FocusEventId.LOCK_CALC, "│ uninstallProtectedTargets=${uninstallProtectedTargets.size} vpnRequired=$manualNuclear statusBarDisabled=${effectiveMode == ModeState.NUCLEAR}")
        FocusLog.d(FocusEventId.POLICY_STATE_COMPUTED, "└── PolicyEvaluator.evaluate() END — suspendTargets=${suspendTargets.size} restrictions=${restrictions.size} ──")

        return PolicyState(
            mode = effectiveMode,
            lockTaskAllowlist = allowlistResolution.packages,
            lockTaskFeatures = if (effectiveMode == ModeState.NUCLEAR) {
                FocusConfig.nuclearLockTaskFeatures
            } else {
                FocusConfig.normalLockTaskFeatures
            },
            statusBarDisabled = effectiveMode == ModeState.NUCLEAR,
            suspendTargets = suspendTargets,
            previouslySuspended = previouslySuspended,
            uninstallProtectedPackages = uninstallProtectedTargets,
            previouslyUninstallProtectedPackages = previouslyUninstallProtected,
            restrictions = restrictions,
            enforceRestrictions = restrictions.isNotEmpty(),
            blockSelfUninstall = true,
            requireAutoTime = effectiveMode == ModeState.NUCLEAR || globalControls.lockTime,
            emergencyApps = normalizedEmergency,
            allowlistReasons = allowlistResolution.reasons,
            vpnRequired = manualNuclear && FocusConfig.requireVpnForNuclear,
            managedNetworkPolicy = managedNetworkPolicy,
            lockReason = lockReason,
            budgetBlockedPackages = budgetBlockedSuspendable,
            touchGrassBreakActive = touchGrassBreakActive,
            primaryReasonByPackage = primaryReasonByPackage,
            globalControls = globalControls
        )
    }

    companion object {
        internal fun mergeSuspendTargets(
            effectiveMode: ModeState,
            nuclearSuspendTargets: Set<String>,
            budgetBlockedSuspendable: Set<String>,
            alwaysBlockedSuspendable: Set<String>,
            strictInstallSuspendable: Set<String>
        ): Set<String> {
            val targetedPackages = linkedSetOf<String>()
            if (effectiveMode == ModeState.NUCLEAR) {
                targetedPackages += nuclearSuspendTargets
                FocusLog.d(FocusEventId.SUSPEND_TARGET, "│ mergeSuspend: +nuclear=${nuclearSuspendTargets.size}")
            }
            targetedPackages += budgetBlockedSuspendable
            targetedPackages += alwaysBlockedSuspendable
            targetedPackages += strictInstallSuspendable
            FocusLog.d(FocusEventId.SUSPEND_TARGET, "│ mergeSuspend: +budget=${budgetBlockedSuspendable.size} +alwaysBlocked=${alwaysBlockedSuspendable.size} +strict=${strictInstallSuspendable.size} total=${targetedPackages.size}")
            return targetedPackages
        }
    }
}
