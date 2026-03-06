package com.ankit.destination.policy

import com.ankit.destination.data.GlobalControls

class PolicyEvaluator(private val packageResolver: PackageResolver) {
    fun evaluate(
        mode: ModeState,
        emergencyApps: Set<String>,
        alwaysAllowedApps: Set<String>,
        alwaysBlockedApps: Set<String>,
        uninstallProtectedApps: Set<String>,
        globalControls: GlobalControls,
        previouslySuspended: Set<String>,
        previouslyUninstallProtected: Set<String>,
        budgetBlockedPackages: Set<String>,
        primaryReasonByPackage: Map<String, String>,
        lockReason: String?,
        touchGrassBreakActive: Boolean
    ): PolicyState {
        val normalizedEmergency = emergencyApps.map(String::trim).filter(String::isNotBlank).toSet()
        val normalizedAlwaysAllowed = alwaysAllowedApps.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val allowlistResolution = packageResolver.resolveAllowlist(
            userChosenEmergencyApps = normalizedEmergency,
            alwaysAllowedApps = normalizedAlwaysAllowed
        )
        val budgetBlockedSuspendable =
            packageResolver.filterSuspendable(
                budgetBlockedPackages,
                allowlistResolution.packages
            )
        val alwaysBlockedSuspendable = packageResolver.filterSuspendable(
            packages = alwaysBlockedApps,
            allowlist = emptySet()
        )

        val suspendTargets = if (mode == ModeState.NUCLEAR) {
            packageResolver.computeSuspendTargets(allowlistResolution.packages) +
                budgetBlockedSuspendable +
                alwaysBlockedSuspendable
        } else {
            budgetBlockedSuspendable + alwaysBlockedSuspendable
        }
        val restrictions = buildRestrictionSet(mode, globalControls)
        val manualNuclear = mode == ModeState.NUCLEAR && lockReason == "Manual Nuclear mode"
        val uninstallProtectedTargets = uninstallProtectedApps.toSet()

        return PolicyState(
            mode = mode,
            lockTaskAllowlist = allowlistResolution.packages,
            lockTaskFeatures = if (mode == ModeState.NUCLEAR) {
                FocusConfig.nuclearLockTaskFeatures
            } else {
                FocusConfig.normalLockTaskFeatures
            },
            statusBarDisabled = mode == ModeState.NUCLEAR,
            suspendTargets = suspendTargets,
            previouslySuspended = previouslySuspended,
            uninstallProtectedPackages = uninstallProtectedTargets,
            previouslyUninstallProtectedPackages = previouslyUninstallProtected,
            restrictions = restrictions,
            enforceRestrictions = restrictions.isNotEmpty(),
            blockSelfUninstall = true,
            requireAutoTime = mode == ModeState.NUCLEAR || globalControls.lockTime,
            emergencyApps = normalizedEmergency,
            allowlistReasons = allowlistResolution.reasons,
            vpnRequired = manualNuclear && FocusConfig.requireVpnForNuclear,
            alwaysOnVpnLockdown = globalControls.lockVpnDns,
            lockReason = lockReason,
            budgetBlockedPackages = budgetBlockedSuspendable,
            touchGrassBreakActive = touchGrassBreakActive,
            primaryReasonByPackage = primaryReasonByPackage,
            globalControls = globalControls
        )
    }

    private fun buildRestrictionSet(mode: ModeState, globalControls: GlobalControls): Set<String> {
        val out = mutableSetOf<String>()
        if (mode == ModeState.NUCLEAR) {
            out += FocusConfig.nuclearRestrictions()
        }
        if (globalControls.lockTime) {
            out += android.os.UserManager.DISALLOW_CONFIG_DATE_TIME
        }
        if (globalControls.lockDevOptions) {
            out += android.os.UserManager.DISALLOW_DEBUGGING_FEATURES
        }
        if (globalControls.lockUserCreation) {
            out += android.os.UserManager.DISALLOW_ADD_USER
        }
        if (globalControls.lockWorkProfile) {
            out += android.os.UserManager.DISALLOW_ADD_MANAGED_PROFILE
        }
        if (globalControls.lockCloningBestEffort) {
            out += "no_add_clone_profile"
            out += "no_add_private_profile"
        }
        return out
    }
}



