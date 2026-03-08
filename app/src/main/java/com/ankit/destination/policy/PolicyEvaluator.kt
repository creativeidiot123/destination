package com.ankit.destination.policy

import com.ankit.destination.data.GlobalControls

internal class PolicyEvaluator(
    private val packageResolver: PackageResolverClient,
    private val controllerPackageName: String
) {
    fun evaluate(
        mode: ModeState,
        emergencyApps: Set<String>,
        protectionSnapshot: AppProtectionSnapshot,
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
        FocusLog.d(FocusEventId.POLICY_STATE_COMPUTED, "PolicyEvaluator.evaluate() start")
        FocusLog.d(
            FocusEventId.POLICY_STATE_COMPUTED,
            "mode=$mode emergencyApps=${emergencyApps.size} allowlist=${protectionSnapshot.allowlistedPackages.size} hidden=${protectionSnapshot.hiddenPackages.size} alwaysBlocked=${alwaysBlockedApps.size}"
        )
        FocusLog.d(
            FocusEventId.POLICY_STATE_COMPUTED,
            "strictInstall=${strictInstallBlockedPackages.size} uninstallProtected=${uninstallProtectedApps.size} budgetBlocked=${budgetBlockedPackages.size}"
        )
        FocusLog.d(
            FocusEventId.USAGE_ACCESS_CHECK,
            "usageAccess granted=${usageAccessComplianceState.usageAccessGranted} lockdownEligible=${usageAccessComplianceState.lockdownEligible} lockdownActive=${usageAccessComplianceState.lockdownActive}"
        )

        val effectiveMode = mode
        if (usageAccessComplianceState.lockdownActive) {
            val recoveryAllowlist = usageAccessComplianceState.recoveryAllowlist
            val recoverySuspendTargets =
                packageResolver.computeUsageAccessRecoverySuspendTargets(recoveryAllowlist)
            val recoveryReasons = recoverySuspendTargets.associateWith {
                EffectiveBlockReason.USAGE_ACCESS_RECOVERY_LOCKDOWN.name
            }
            val recoveryReasonsByPackage = recoveryReasons.mapValues { setOf(it.value) }
            val allowlistReasons = recoveryAllowlist.associateWith { "usage access recovery" }
            val managedNetworkPolicy = globalControls.toManagedNetworkPolicy(controllerPackageName)
            val restrictions = PolicyRestrictions.build(
                globalControls = globalControls,
                managedNetworkPolicy = managedNetworkPolicy
            )
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

        val normalizedEmergency = emergencyApps
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()

        val budgetBlockedSuspendable = packageResolver.filterSuspendable(
            packages = budgetBlockedPackages,
            allowlist = protectionSnapshot.fullyExemptPackages
        )
        val alwaysBlockedSuspendable = packageResolver.filterSuspendable(
            packages = alwaysBlockedApps,
            allowlist = protectionSnapshot.fullyExemptPackages
        )
        val strictInstallSuspendable = packageResolver.filterSuspendable(
            packages = strictInstallBlockedPackages,
            allowlist = protectionSnapshot.fullyExemptPackages
        )
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
            if (finalBlockReasons[pkg].isNullOrEmpty()) {
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

        val finalBlockReasonsByPackage = finalBlockReasons.mapValues { (_, value) -> value.toSet() }
        val derivedPrimaryReasons = BlockReasonUtils.derivePrimaryByPackage(finalBlockReasons)
        val suspendTargets = mergeSuspendTargets(
            budgetBlockedSuspendable = budgetBlockedSuspendable,
            alwaysBlockedSuspendable = alwaysBlockedSuspendable,
            strictInstallSuspendable = strictInstallSuspendable
        )

        val restrictions = PolicyRestrictions.build(
            globalControls = globalControls,
            managedNetworkPolicy = managedNetworkPolicy
        )
        val uninstallProtectedTargets = uninstallProtectedApps
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .filter(packageResolver::isPackageInstalled)
            .toSet()

        return PolicyState(
            mode = effectiveMode,
            lockTaskAllowlist = protectionSnapshot.runtimeExemptPackages,
            lockTaskFeatures = FocusConfig.normalLockTaskFeatures,
            statusBarDisabled = false,
            suspendTargets = suspendTargets,
            previouslySuspended = previouslySuspended,
            uninstallProtectedPackages = uninstallProtectedTargets,
            previouslyUninstallProtectedPackages = previouslyUninstallProtected,
            restrictions = restrictions,
            enforceRestrictions = restrictions.isNotEmpty(),
            blockSelfUninstall = true,
            requireAutoTime = globalControls.lockTime,
            emergencyApps = normalizedEmergency,
            allowlistReasons = protectionSnapshot.runtimeExemptionReasons,
            vpnRequired = false,
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
            budgetBlockedSuspendable: Set<String>,
            alwaysBlockedSuspendable: Set<String>,
            strictInstallSuspendable: Set<String>
        ): Set<String> {
            val targetedPackages = linkedSetOf<String>()
            targetedPackages += budgetBlockedSuspendable
            targetedPackages += alwaysBlockedSuspendable
            targetedPackages += strictInstallSuspendable
            FocusLog.d(
                FocusEventId.SUSPEND_TARGET,
                "mergeSuspend budget=${budgetBlockedSuspendable.size} alwaysBlocked=${alwaysBlockedSuspendable.size} strict=${strictInstallSuspendable.size} total=${targetedPackages.size}"
            )
            return targetedPackages
        }
    }
}
