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
        blockReasonsByPackage: Map<String, Set<String>> = emptyMap(),
        primaryReasonByPackage: Map<String, String>,
        lockReason: String?,
        touchGrassBreakActive: Boolean,
        usageAccessComplianceState: UsageAccessComplianceState
    ): PolicyState {
        FocusLog.d(FocusEventId.POLICY_STATE_COMPUTED, "â”Œâ”€â”€ PolicyEvaluator.evaluate() START â”€â”€")
        FocusLog.d(FocusEventId.POLICY_STATE_COMPUTED, "â”‚ mode=$mode emergencyApps=${emergencyApps.size} alwaysAllowed=${alwaysAllowedApps.size} alwaysBlocked=${alwaysBlockedApps.size}")
        FocusLog.d(FocusEventId.POLICY_STATE_COMPUTED, "â”‚ strictInstall=${strictInstallBlockedPackages.size} uninstallProtected=${uninstallProtectedApps.size} budgetBlocked=${budgetBlockedPackages.size}")
        FocusLog.d(FocusEventId.POLICY_STATE_COMPUTED, "â”‚ lockReason=$lockReason touchGrassBreak=$touchGrassBreakActive prevSuspended=${previouslySuspended.size}")
        FocusLog.d(FocusEventId.USAGE_ACCESS_CHECK, "â”‚ usageAccess: granted=${usageAccessComplianceState.usageAccessGranted} lockdownEligible=${usageAccessComplianceState.lockdownEligible} lockdownActive=${usageAccessComplianceState.lockdownActive}")

        val effectiveMode = if (FocusConfig.enableNuclearMode) mode else ModeState.NORMAL
        if (usageAccessComplianceState.lockdownActive) {
            FocusLog.i(FocusEventId.USAGE_ACCESS_CHECK, "â”‚ âš ï¸ USAGE ACCESS RECOVERY LOCKDOWN ACTIVE â€” reason=${usageAccessComplianceState.reason}")
            val recoveryAllowlist = usageAccessComplianceState.recoveryAllowlist
            FocusLog.d(FocusEventId.USAGE_ACCESS_CHECK, "â”‚ recoveryAllowlist=${recoveryAllowlist.size}: ${recoveryAllowlist.joinToString(",")}")
            val recoverySuspendTargets =
                packageResolver.computeUsageAccessRecoverySuspendTargets(recoveryAllowlist)
            FocusLog.d(FocusEventId.USAGE_ACCESS_CHECK, "â”‚ recoverySuspendTargets=${recoverySuspendTargets.size}")
            val recoveryReasons = recoverySuspendTargets.associateWith {
                EffectiveBlockReason.USAGE_ACCESS_RECOVERY_LOCKDOWN.name
            }
            val recoveryReasonsByPackage = recoveryReasons.mapValues { setOf(it.value) }
            val allowlistReasons = recoveryAllowlist.associateWith { "usage access recovery" }
            val managedNetworkPolicy = globalControls.toManagedNetworkPolicy(controllerPackageName)
            val restrictions = PolicyRestrictions.build(
                mode = effectiveMode,
                globalControls = globalControls,
                managedNetworkPolicy = managedNetworkPolicy
            )
            FocusLog.d(FocusEventId.USAGE_ACCESS_CHECK, "â”‚ restrictions=${restrictions.size} managedNetwork=$managedNetworkPolicy")
            FocusLog.d(FocusEventId.USAGE_ACCESS_CHECK, "â””â”€â”€ PolicyEvaluator.evaluate() END (recovery lockdown) â”€â”€")
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
            blockReasonsByPackage = recoveryReasonsByPackage,
            globalControls = globalControls
        )
    }
        val normalizedEmergency = emergencyApps.map(String::trim).filter(String::isNotBlank).toSet()
        val normalizedAlwaysAllowed = alwaysAllowedApps.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        FocusLog.d(FocusEventId.ALLOWLIST_RESOLVE, "â”‚ Resolving allowlist: emergency=${normalizedEmergency.size} alwaysAllowed=${normalizedAlwaysAllowed.size}")
        val allowlistResolution = packageResolver.resolveAllowlist(
            userChosenEmergencyApps = normalizedEmergency,
            alwaysAllowedApps = normalizedAlwaysAllowed
        )
        FocusLog.d(FocusEventId.ALLOWLIST_RESOLVE, "â”‚ Allowlist resolved: ${allowlistResolution.packages.size} packages")
        allowlistResolution.reasons.forEach { (pkg, reason) ->
            FocusLog.v(FocusEventId.ALLOWLIST_RESOLVE, "â”‚   allowlist: $pkg â†’ $reason")
        }

        val budgetBlockedSuspendable =
            packageResolver.filterSuspendable(
                budgetBlockedPackages,
                allowlistResolution.packages
            )
        FocusLog.d(FocusEventId.SUSPEND_TARGET, "â”‚ budgetBlockedSuspendable=${budgetBlockedSuspendable.size} (from ${budgetBlockedPackages.size} budget blocked)")
        val alwaysBlockedSuspendable = packageResolver.filterSuspendable(
            packages = alwaysBlockedApps,
            allowlist = allowlistResolution.packages
        )
        FocusLog.d(FocusEventId.SUSPEND_TARGET, "â”‚ alwaysBlockedSuspendable=${alwaysBlockedSuspendable.size} (from ${alwaysBlockedApps.size} always blocked)")
        if (alwaysBlockedSuspendable.isNotEmpty()) {
            FocusLog.d(FocusEventId.SUSPEND_TARGET, "â”‚   alwaysBlocked pkgs: ${alwaysBlockedSuspendable.joinToString(",")}")
        }
        val strictInstallSuspendable = packageResolver.filterSuspendable(
            packages = strictInstallBlockedPackages,
            allowlist = allowlistResolution.packages
        )
        FocusLog.d(FocusEventId.SUSPEND_TARGET, "â”‚ strictInstallSuspendable=${strictInstallSuspendable.size} (from ${strictInstallBlockedPackages.size} strict install)")
        val managedNetworkPolicy = globalControls.toManagedNetworkPolicy(controllerPackageName)

        val normalizedBlockReasons = blockReasonsByPackage
            .asSequence()
            .mapNotNull { (rawPackage, reasons) ->
                val normalizedPackage = rawPackage.trim()
                if (normalizedPackage.isBlank()) return@mapNotNull null
                val normalizedReasons = reasons
                    .asSequence()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .toSet()
                if (normalizedReasons.isEmpty()) {
                    null
                } else {
                    normalizedPackage to normalizedReasons
                }
            }
            .toMap()

        val finalBlockReasons = linkedMapOf<String, LinkedHashSet<String>>()
        fun addReason(pkg: String, reason: String) {
            if (pkg.isBlank() || reason.isBlank()) return
            finalBlockReasons.getOrPut(pkg) { linkedSetOf() }.add(reason)
        }

        budgetBlockedSuspendable.forEach { pkg ->
            val reasons = normalizedBlockReasons[pkg]
            if (reasons != null) {
                reasons.forEach { addReason(pkg, it) }
            } else {
                primaryReasonByPackage[pkg]?.let { addReason(pkg, it) }
            }
            if (!pkg.isBlank() && finalBlockReasons[pkg].isNullOrEmpty()) {
                addReason(pkg, "BUDGET")
            }
        }
        alwaysBlockedSuspendable.forEach { pkg ->
            addReason(pkg, EffectiveBlockReason.ALWAYS_BLOCKED.name)
        }
        strictInstallSuspendable.forEach { pkg ->
            addReason(pkg, EffectiveBlockReason.STRICT_INSTALL.name)
        }
        normalizedBlockReasons.entries.forEach { (pkg, reasons) ->
            if (pkg in budgetBlockedSuspendable || pkg in alwaysBlockedSuspendable || pkg in strictInstallSuspendable) {
                reasons.forEach { addReason(pkg, it) }
            }
        }
        val derivedPrimaryReasons = BlockReasonUtils.derivePrimaryByPackage(finalBlockReasons)
        val finalBlockReasonsByPackage = finalBlockReasons.mapValues { (_, value) -> value.toSet() }

        val nuclearSuspendTargets = packageResolver.computeSuspendTargets(allowlistResolution.packages)
        val suspendTargets = mergeSuspendTargets(
            effectiveMode = effectiveMode,
            nuclearSuspendTargets = nuclearSuspendTargets,
            budgetBlockedSuspendable = budgetBlockedSuspendable,
            alwaysBlockedSuspendable = alwaysBlockedSuspendable,
            strictInstallSuspendable = strictInstallSuspendable
        )
        FocusLog.d(FocusEventId.SUSPEND_TARGET, "â”‚ MERGED suspendTargets=${suspendTargets.size} (nuclear=${nuclearSuspendTargets.size} budget=${budgetBlockedSuspendable.size} alwaysBlocked=${alwaysBlockedSuspendable.size} strict=${strictInstallSuspendable.size})")

        val restrictions = PolicyRestrictions.build(
            mode = effectiveMode,
            globalControls = globalControls,
            managedNetworkPolicy = managedNetworkPolicy
        )
        FocusLog.d(FocusEventId.LOCK_CALC, "â”‚ restrictions=${restrictions.size}: ${restrictions.joinToString(",")}")
        val manualNuclear = FocusConfig.enableNuclearMode && mode == ModeState.NUCLEAR
        val uninstallProtectedTargets = uninstallProtectedApps
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .filter(packageResolver::isPackageInstalled)
            .toSet()
        FocusLog.d(FocusEventId.LOCK_CALC, "â”‚ uninstallProtectedTargets=${uninstallProtectedTargets.size} vpnRequired=$manualNuclear statusBarDisabled=${effectiveMode == ModeState.NUCLEAR}")
        FocusLog.d(FocusEventId.POLICY_STATE_COMPUTED, "â””â”€â”€ PolicyEvaluator.evaluate() END â€” suspendTargets=${suspendTargets.size} restrictions=${restrictions.size} â”€â”€")

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
            primaryReasonByPackage = derivedPrimaryReasons,
            blockReasonsByPackage = finalBlockReasonsByPackage,
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
                FocusLog.d(FocusEventId.SUSPEND_TARGET, "â”‚ mergeSuspend: +nuclear=${nuclearSuspendTargets.size}")
            }
            targetedPackages += budgetBlockedSuspendable
            targetedPackages += alwaysBlockedSuspendable
            targetedPackages += strictInstallSuspendable
            FocusLog.d(FocusEventId.SUSPEND_TARGET, "â”‚ mergeSuspend: +budget=${budgetBlockedSuspendable.size} +alwaysBlocked=${alwaysBlockedSuspendable.size} +strict=${strictInstallSuspendable.size} total=${targetedPackages.size}")
            return targetedPackages
        }
    }
}

