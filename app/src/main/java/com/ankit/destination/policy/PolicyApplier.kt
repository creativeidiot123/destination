package com.ankit.destination.policy

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.os.Build
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class PolicyApplier(
    private val facade: DevicePolicyClient,
    private val protectedPackagesProvider: ProtectedPackagesProvider? = null
) {

    fun apply(state: PolicyState, hostActivity: Activity? = null): ApplyResult {
        val desired = prepare(state)
        val phaseResults = mutableListOf<PolicyApplyPhaseResult>()
        val errors = mutableListOf<String>()

        if (!facade.isDeviceOwner()) {
            val deviceOwnerError = "Not device owner"
            phaseResults += PolicyApplyPhaseResult(
                phase = PolicyApplyPhase.PREPARE,
                category = PolicyEnforcementCategory.CORE,
                successful = false,
                errors = listOf(deviceOwnerError)
            )
            val verification = PolicyVerificationResult(
                passed = false,
                issues = listOf(deviceOwnerError),
                suspendedChecked = 0,
                suspendedMismatchCount = 0,
                lockTaskModeActive = null,
                coreIssues = listOf(deviceOwnerError)
            )
            return ApplyResult(
                failedToSuspend = emptySet(),
                failedToUnsuspend = emptySet(),
                failedToProtectUninstall = emptySet(),
                failedToUnprotectUninstall = emptySet(),
                errors = listOf(deviceOwnerError),
                phaseResults = phaseResults,
                repairPlan = repairIfNeeded(verification),
                verification = verification,
                coreFailure = true
            )
        }

        val current = readAuthoritativeState(desired, state)
        val delta = computeDelta(current, desired, state)
        phaseResults += PolicyApplyPhaseResult(
            phase = PolicyApplyPhase.PREPARE,
            category = PolicyEnforcementCategory.CORE,
            successful = true,
            errors = emptyList()
        )

        val reversibleErrors = mutableListOf<String>()
        val failedToProtectUninstall = linkedSetOf<String>()
        val failedToUnprotectUninstall = linkedSetOf<String>()
        delta.toProtectUninstall.forEach { pkg ->
            runCatching { facade.setUninstallBlocked(pkg, true) }
                .onFailure {
                    failedToProtectUninstall += pkg
                    reversibleErrors += "setUninstallBlocked($pkg,true) failed: ${it.message}"
                }
        }
        delta.toUnprotectUninstall.forEach { pkg ->
            runCatching { facade.setUninstallBlocked(pkg, false) }
                .onFailure {
                    failedToUnprotectUninstall += pkg
                    reversibleErrors += "setUninstallBlocked($pkg,false) failed: ${it.message}"
                }
        }

        val desiredUserControlDisabled = desiredUserControlDisabledPackages(desired.uninstallProtected)
        if (facade.supportsUserControlDisabledPackages()) {
            runCatching { facade.setUserControlDisabledPackages(desiredUserControlDisabled.toList()) }
                .onFailure { reversibleErrors += "setUserControlDisabledPackages failed: ${it.message}" }
        }

        val failedToSuspend: Set<String>
        val failedToUnsuspend: Set<String>
        if (delta.toSuspend.isNotEmpty()) {
            val suspendReasons = state.blockReasonsByPackage.filterKeys(delta.toSuspend::contains)
            val suspendResult = facade.setPackagesSuspended(
                packages = delta.toSuspend.toList(),
                suspended = true,
                blockReasonsByPackage = suspendReasons
            )
            failedToSuspend = suspendResult.failedPackages
            reversibleErrors += suspendResult.errors.map { "setPackagesSuspended(true) failed: $it" }
        } else {
            failedToSuspend = emptySet()
        }
        if (delta.toUnsuspend.isNotEmpty()) {
            val unsuspendResult = facade.setPackagesSuspended(
                packages = delta.toUnsuspend.toList(),
                suspended = false
            )
            failedToUnsuspend = unsuspendResult.failedPackages
            reversibleErrors += unsuspendResult.errors.map { "setPackagesSuspended(false) failed: $it" }
        } else {
            failedToUnsuspend = emptySet()
        }
        if (failedToSuspend.isNotEmpty()) {
            reversibleErrors += "Packages failed to suspend: ${failedToSuspend.sorted().joinToString(", ")}"
        }
        if (failedToUnsuspend.isNotEmpty()) {
            reversibleErrors += "Packages failed to unsuspend: ${failedToUnsuspend.sorted().joinToString(", ")}"
        }
        errors += reversibleErrors
        phaseResults += PolicyApplyPhaseResult(
            phase = PolicyApplyPhase.REVERSIBLE,
            category = PolicyEnforcementCategory.CORE,
            successful = reversibleErrors.isEmpty(),
            errors = reversibleErrors.toList()
        )

        val supportingErrors = mutableListOf<String>()
        val cosmeticErrors = mutableListOf<String>()
        exitLockTaskIfNeeded(hostActivity)?.let { supportingErrors += it }

        delta.restrictionsToAdd.forEach { restriction ->
            runCatching { facade.addUserRestriction(restriction) }
                .onFailure {
                    if (!PolicyRestrictions.isBestEffort(restriction)) {
                        reversibleErrors += "addRestriction($restriction) failed: ${it.message}"
                    }
                }
        }
        delta.restrictionsToClear.forEach { restriction ->
            runCatching { facade.clearUserRestriction(restriction) }
                .onFailure {
                    if (!PolicyRestrictions.isBestEffortOnClear(restriction)) {
                        reversibleErrors += "clearRestriction($restriction) failed: ${it.message}"
                    }
                }
        }

        if (desired.userRestrictions.contains(UserManager.DISALLOW_DEBUGGING_FEATURES)) {
            runCatching { facade.setGlobalSetting(Settings.Global.ADB_ENABLED, "0") }
                .onFailure { reversibleErrors += "setGlobalSetting(${Settings.Global.ADB_ENABLED}) failed: ${it.message}" }
        }

        if (delta.shouldUpdateAutoTime) {
            runCatching { facade.setAutoTimeRequired(desired.requireAutoTime) }
                .onFailure { supportingErrors += "setAutoTimeRequired failed: ${it.message}" }
        }

        runCatching { facade.setBlankDeviceOwnerLockScreenInfo() }
            .onFailure { cosmeticErrors += "setDeviceOwnerLockScreenInfo failed: ${it.message}" }

        if (delta.shouldUpdateLockTaskPackages) {
            runCatching { facade.setLockTaskPackages(desired.lockTaskPackages.toList()) }
                .onFailure { supportingErrors += "setLockTaskPackages failed: ${it.message}" }
        }
        if (delta.shouldUpdateLockTaskFeatures) {
            runCatching { facade.setLockTaskFeatures(desired.lockTaskFeatures) }
                .onFailure { supportingErrors += "setLockTaskFeatures failed: ${it.message}" }
        }
        runCatching { facade.setStatusBarDisabled(desired.statusBarDisabled) }
            .onSuccess { applied ->
                if (applied == false) {
                    supportingErrors += "setStatusBarDisabled returned false"
                }
            }
            .onFailure { supportingErrors += "setStatusBarDisabled failed: ${it.message}" }

        supportingErrors += applyManagedNetworkPolicy(desired.managedNetworkPolicy)

        runCatching { facade.setAsHomeForKiosk(false) }
            .onFailure { cosmeticErrors += "setAsHomeForKiosk failed: ${it.message}" }

        if (reversibleErrors.isNotEmpty()) {
            errors += reversibleErrors.filterNot(errors::contains)
        }
        if (supportingErrors.isNotEmpty()) {
            errors += supportingErrors
        }
        if (cosmeticErrors.isNotEmpty()) {
            errors += cosmeticErrors
        }
        phaseResults += PolicyApplyPhaseResult(
            phase = PolicyApplyPhase.BEST_EFFORT,
            category = PolicyEnforcementCategory.CORE,
            successful = reversibleErrors.isEmpty(),
            errors = reversibleErrors.toList()
        )
        phaseResults += PolicyApplyPhaseResult(
            phase = PolicyApplyPhase.BEST_EFFORT,
            category = PolicyEnforcementCategory.SUPPORTING,
            successful = supportingErrors.isEmpty(),
            errors = supportingErrors.toList()
        )
        phaseResults += PolicyApplyPhaseResult(
            phase = PolicyApplyPhase.BEST_EFFORT,
            category = PolicyEnforcementCategory.COSMETIC,
            successful = cosmeticErrors.isEmpty(),
            errors = cosmeticErrors.toList()
        )

        val observedAfter = readAuthoritativeState(desired, state)
        val verification = verifyPrepared(desired, state, observedAfter, hostActivity)
        phaseResults += PolicyApplyPhaseResult(
            phase = PolicyApplyPhase.VERIFY,
            category = PolicyEnforcementCategory.CORE,
            successful = verification.coreIssues.isEmpty(),
            errors = verification.coreIssues
        )
        phaseResults += PolicyApplyPhaseResult(
            phase = PolicyApplyPhase.VERIFY,
            category = PolicyEnforcementCategory.SUPPORTING,
            successful = verification.supportingIssues.isEmpty(),
            errors = verification.supportingIssues
        )
        phaseResults += PolicyApplyPhaseResult(
            phase = PolicyApplyPhase.VERIFY,
            category = PolicyEnforcementCategory.COSMETIC,
            successful = verification.cosmeticIssues.isEmpty(),
            errors = verification.cosmeticIssues
        )

        val repairPlan = repairIfNeeded(verification)
        if (repairPlan?.required == true) {
            phaseResults += PolicyApplyPhaseResult(
                phase = PolicyApplyPhase.REPAIR,
                category = if (verification.coreIssues.isNotEmpty()) {
                    PolicyEnforcementCategory.CORE
                } else {
                    PolicyEnforcementCategory.SUPPORTING
                },
                successful = false,
                errors = listOfNotNull(repairPlan.reason)
            )
        }

        return ApplyResult(
            failedToSuspend = failedToSuspend,
            failedToUnsuspend = failedToUnsuspend,
            failedToProtectUninstall = failedToProtectUninstall,
            failedToUnprotectUninstall = failedToUnprotectUninstall,
            errors = errors,
            observedState = observedAfter,
            phaseResults = phaseResults,
            repairPlan = repairPlan,
            verification = verification,
            coreFailure = reversibleErrors.isNotEmpty() || verification.coreIssues.isNotEmpty(),
            supportingFailure = supportingErrors.isNotEmpty() || verification.supportingIssues.isNotEmpty()
        )
    }

    fun verify(state: PolicyState, hostActivity: Activity? = null): PolicyVerificationResult {
        val desired = prepare(state)
        val observed = readAuthoritativeState(desired, state)
        return verifyPrepared(desired, state, observed, hostActivity)
    }

    private fun prepare(state: PolicyState): DesiredDevicePolicyState {
        val hardProtectedPackages = protectedPackagesProvider?.getHardProtectedPackages().orEmpty()
        return DesiredDevicePolicyState(
            suspendTargets = (state.suspendTargets - hardProtectedPackages).toSet(),
            uninstallProtected = buildSet {
                if (state.blockSelfUninstall) add(facade.packageName)
                addAll(state.uninstallProtectedPackages)
            },
            userRestrictions = managedRestrictions(state.restrictions),
            managedNetworkPolicy = state.managedNetworkPolicy,
            lockTaskPackages = state.lockTaskAllowlist,
            lockTaskFeatures = state.lockTaskFeatures,
            requireAutoTime = state.requireAutoTime,
            statusBarDisabled = state.statusBarDisabled
        )
    }

    private fun readAuthoritativeState(
        desired: DesiredDevicePolicyState,
        tracked: PolicyState
    ): ObservedDevicePolicyState {
        val suspendCandidates = (desired.suspendTargets + tracked.previouslySuspended)
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
        val observedSuspended = linkedSetOf<String>()
        var suspensionAuthoritative = facade.canVerifyPackageSuspension()
        if (suspensionAuthoritative) {
            suspendCandidates.forEach { packageName ->
                val suspended = facade.isPackageSuspended(packageName)
                if (suspended == null) {
                    suspensionAuthoritative = false
                } else if (suspended) {
                    observedSuspended += packageName
                }
            }
        }
        val suspendedPackages = if (suspensionAuthoritative) {
            observedSuspended.toSet()
        } else {
            tracked.previouslySuspended.intersect(suspendCandidates)
        }

        val uninstallCandidates = (desired.uninstallProtected + tracked.previouslyUninstallProtectedPackages)
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
        val observedUninstallProtected = linkedSetOf<String>()
        var uninstallProtectionAuthoritative = true
        uninstallCandidates.forEach { packageName ->
            val blocked = runCatching { facade.isUninstallBlocked(packageName) }.getOrElse {
                uninstallProtectionAuthoritative = false
                false
            }
            if (blocked) observedUninstallProtected += packageName
        }
        val uninstallProtectedPackages = if (uninstallProtectionAuthoritative) {
            observedUninstallProtected.toSet()
        } else {
            tracked.previouslyUninstallProtectedPackages.intersect(uninstallCandidates)
        }

        val userControlDisabledPackages = if (facade.supportsUserControlDisabledPackages()) {
            runCatching { facade.getUserControlDisabledPackages() }.getOrDefault(emptySet())
        } else {
            emptySet()
        }

        val observedRestrictions = desired.userRestrictions.filterTo(linkedSetOf()) { restriction ->
            runCatching { facade.hasUserRestriction(restriction) }.getOrDefault(false)
        }

        val lockTaskPackages = runCatching { facade.getLockTaskPackages().toSet() }.getOrDefault(emptySet())
        val lockTaskFeatures = runCatching { facade.getLockTaskFeatures() }.getOrNull()
        val autoTimeRequired = runCatching { facade.isAutoTimeRequired() }.getOrNull()

        return ObservedDevicePolicyState(
            suspendedPackages = suspendedPackages,
            suspensionAuthoritative = suspensionAuthoritative,
            uninstallProtectedPackages = uninstallProtectedPackages,
            uninstallProtectionAuthoritative = uninstallProtectionAuthoritative,
            userControlDisabledPackages = userControlDisabledPackages,
            userControlDisabledAuthoritative = facade.supportsUserControlDisabledPackages(),
            userRestrictions = observedRestrictions,
            lockTaskPackages = lockTaskPackages,
            lockTaskFeatures = lockTaskFeatures,
            autoTimeRequired = autoTimeRequired
        )
    }

    private fun computeDelta(
        current: ObservedDevicePolicyState,
        desired: DesiredDevicePolicyState,
        tracked: PolicyState
    ): PolicyApplyDelta {
        val currentSuspended = if (current.suspensionAuthoritative) {
            current.suspendedPackages
        } else {
            tracked.previouslySuspended.intersect(desired.suspendTargets + tracked.previouslySuspended)
        }
        val currentUninstallProtected = if (current.uninstallProtectionAuthoritative) {
            current.uninstallProtectedPackages
        } else {
            tracked.previouslyUninstallProtectedPackages.intersect(
                desired.uninstallProtected + tracked.previouslyUninstallProtectedPackages
            )
        }
        return PolicyApplyDelta(
            toSuspend = if (tracked.refreshExistingSuspendedPackages) {
                desired.suspendTargets
            } else {
                desired.suspendTargets - currentSuspended
            },
            toUnsuspend = currentSuspended - desired.suspendTargets,
            toProtectUninstall = desired.uninstallProtected - currentUninstallProtected,
            toUnprotectUninstall = currentUninstallProtected - desired.uninstallProtected,
            restrictionsToAdd = desired.userRestrictions - current.userRestrictions,
            restrictionsToClear = current.userRestrictions - desired.userRestrictions,
            shouldUpdateLockTaskPackages = current.lockTaskPackages != desired.lockTaskPackages,
            shouldUpdateLockTaskFeatures = current.lockTaskFeatures == null || current.lockTaskFeatures != desired.lockTaskFeatures,
            shouldUpdateAutoTime = current.autoTimeRequired == null || current.autoTimeRequired != desired.requireAutoTime
        )
    }

    private fun verifyPrepared(
        desired: DesiredDevicePolicyState,
        tracked: PolicyState,
        observed: ObservedDevicePolicyState,
        hostActivity: Activity?
    ): PolicyVerificationResult {
        val coreIssues = mutableListOf<String>()
        val supportingIssues = mutableListOf<String>()
        val cosmeticIssues = mutableListOf<String>()

        if (!facade.isDeviceOwner()) {
            coreIssues += "Device owner not active"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && observed.lockTaskPackages != desired.lockTaskPackages) {
            supportingIssues += "Lock task packages mismatch"
        }
        val observedLockTaskFeatures = observed.lockTaskFeatures
        if (observedLockTaskFeatures != null && observedLockTaskFeatures != desired.lockTaskFeatures) {
            supportingIssues += "Lock task features mismatch"
        }
        if (runCatching { facade.isHomeAppPinnedToSelf() }.getOrNull() == true) {
            cosmeticIssues += "Home app pinning still active"
        }

        verifyManagedNetworkPolicy(desired.managedNetworkPolicy, supportingIssues)

        desired.userRestrictions.forEach { restriction ->
            if (!observed.userRestrictions.contains(restriction)) {
                val issue = "Restriction missing: $restriction"
                if (
                    restriction == UserManager.DISALLOW_CONFIG_DATE_TIME &&
                    desired.requireAutoTime &&
                    observed.autoTimeRequired == true
                ) {
                    supportingIssues += issue
                } else {
                    coreIssues += issue
                }
            }
        }
        (observed.userRestrictions - desired.userRestrictions).forEach { restriction ->
            if (!PolicyRestrictions.isBestEffortOnClear(restriction)) {
                coreIssues += "Restriction still active: $restriction"
            }
        }

        desired.uninstallProtected.forEach { packageName ->
            if (!observed.uninstallProtectedPackages.contains(packageName)) {
                coreIssues += "Uninstall block missing: $packageName"
            }
        }
        (observed.uninstallProtectedPackages - desired.uninstallProtected).forEach { packageName ->
            coreIssues += "Uninstall block still active: $packageName"
        }

        if (facade.supportsUserControlDisabledPackages()) {
            val expectedUserControlDisabled = desiredUserControlDisabledPackages(desired.uninstallProtected)
            expectedUserControlDisabled.forEach { packageName ->
                if (!observed.userControlDisabledPackages.contains(packageName)) {
                    coreIssues += "User control disable missing: $packageName"
                }
            }
            (observed.userControlDisabledPackages - expectedUserControlDisabled).forEach { packageName ->
                if (packageName in tracked.previouslyUninstallProtectedPackages || packageName in desired.uninstallProtected) {
                    coreIssues += "User control disable still active: $packageName"
                }
            }
        }

        val autoTimeRequired = observed.autoTimeRequired
        if (autoTimeRequired != null && autoTimeRequired != desired.requireAutoTime) {
            supportingIssues += "Auto time requirement mismatch"
        }

        var suspendedChecked = 0
        var suspendedMismatchCount = 0
        if (observed.suspensionAuthoritative) {
            desired.suspendTargets.forEach { packageName ->
                suspendedChecked += 1
                if (!observed.suspendedPackages.contains(packageName)) {
                    suspendedMismatchCount += 1
                    coreIssues += "Suspend mismatch for $packageName"
                }
            }
            (observed.suspendedPackages - desired.suspendTargets).forEach { packageName ->
                suspendedChecked += 1
                suspendedMismatchCount += 1
                supportingIssues += "Unsuspend mismatch for $packageName"
            }
        }

        val lockTaskActive = if (hostActivity != null) {
            val active = facade.lockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE
            if (active) {
                supportingIssues += "Lock task mode still active in foreground activity"
            }
            active
        } else {
            null
        }

        val issues = buildList {
            addAll(coreIssues)
            addAll(supportingIssues)
            addAll(cosmeticIssues)
        }
        return PolicyVerificationResult(
            passed = issues.isEmpty(),
            issues = issues,
            suspendedChecked = suspendedChecked,
            suspendedMismatchCount = suspendedMismatchCount,
            lockTaskModeActive = lockTaskActive,
            coreIssues = coreIssues,
            supportingIssues = supportingIssues,
            cosmeticIssues = cosmeticIssues
        )
    }

    private fun repairIfNeeded(verification: PolicyVerificationResult): PolicyRepairPlan? {
        return when {
            verification.coreIssues.isNotEmpty() -> PolicyRepairPlan(
                required = true,
                delayMs = CORE_REPAIR_DELAY_MS,
                reason = "Core enforcement verification failed"
            )
            verification.supportingIssues.isNotEmpty() -> PolicyRepairPlan(
                required = true,
                delayMs = SUPPORTING_REPAIR_DELAY_MS,
                reason = "Supporting enforcement verification failed"
            )
            else -> null
        }
    }

    private fun exitLockTaskIfNeeded(hostActivity: Activity?): String? {
        if (hostActivity == null) return null
        val lockTaskState = facade.lockTaskModeState() ?: ActivityManager.LOCK_TASK_MODE_NONE
        if (lockTaskState == ActivityManager.LOCK_TASK_MODE_NONE) return null
        return runOnMainThreadBlocking(hostActivity) { hostActivity.stopLockTask() }
            ?.let { "stopLockTask failed: ${it.message}" }
    }

    private fun runOnMainThreadBlocking(activity: Activity, action: () -> Unit): Throwable? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching(action).exceptionOrNull()
        }
        var error: Throwable? = null
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            try {
                action()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        val completed = try {
            latch.await(5, TimeUnit.SECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return interrupted
        }
        return when {
            !completed -> IllegalStateException("Main-thread action timed out")
            else -> error
        }
    }

    private fun managedRestrictions(desiredRestrictions: Set<String>): Set<String> {
        return PolicyRestrictions.managed(desiredRestrictions)
    }

    private fun applyManagedNetworkPolicy(policy: ManagedNetworkPolicy): List<String> {
        val errors = mutableListOf<String>()
        when (policy) {
            is ManagedNetworkPolicy.ForcedVpn -> {
                runCatching {
                    facade.setAlwaysOnVpnPackage(policy.packageName, policy.lockdown)
                }.onFailure { errors += "setAlwaysOnVpnPackage failed: ${it.message}" }
                runCatching {
                    if (facade.supportsGlobalPrivateDns()) {
                        facade.setGlobalPrivateDnsModeOpportunistic()
                    }
                }.onFailure { errors += "clearManagedPrivateDns failed: ${it.message}" }
            }

            is ManagedNetworkPolicy.ForcedPrivateDns -> {
                runCatching {
                    facade.setAlwaysOnVpnPackage(null, false)
                }.onFailure { errors += "clearAlwaysOnVpnPackage failed: ${it.message}" }
                if (!facade.supportsGlobalPrivateDns()) {
                    errors += "Forced Private DNS requires Android 10 or later"
                } else {
                    runCatching {
                        val result = facade.setGlobalPrivateDnsModeSpecifiedHost(policy.hostname)
                        check(result == null || result == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                            "error code=$result"
                        }
                    }.onFailure { errors += "setGlobalPrivateDns failed: ${it.message}" }
                }
            }

            ManagedNetworkPolicy.Unmanaged -> {
                runCatching {
                    facade.setAlwaysOnVpnPackage(null, false)
                }.onFailure { errors += "clearAlwaysOnVpnPackage failed: ${it.message}" }
                runCatching {
                    if (facade.supportsGlobalPrivateDns()) {
                        facade.setGlobalPrivateDnsModeOpportunistic()
                    }
                }.onFailure { errors += "clearManagedPrivateDns failed: ${it.message}" }
            }
        }
        return errors
    }

    private fun verifyManagedNetworkPolicy(
        policy: ManagedNetworkPolicy,
        issues: MutableList<String>
    ) {
        val alwaysOnPackage = runCatching { facade.getAlwaysOnVpnPackage() }.getOrNull()
        val lockdownEnabled = runCatching { facade.isAlwaysOnVpnLockdownEnabled() }.getOrNull()
        val privateDnsMode = runCatching { facade.getGlobalPrivateDnsMode() }.getOrNull()
        val privateDnsHost = runCatching { facade.getGlobalPrivateDnsHost() }.getOrNull()

        when (policy) {
            is ManagedNetworkPolicy.ForcedVpn -> {
                if (alwaysOnPackage != policy.packageName) {
                    issues += "Always-on VPN package mismatch"
                }
                if (lockdownEnabled != null && lockdownEnabled != policy.lockdown) {
                    issues += "Always-on VPN lockdown mismatch"
                }
                if (
                    privateDnsMode != null &&
                    privateDnsMode != DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC
                ) {
                    issues += "Private DNS should be opportunistic while Forced VPN is active"
                }
            }

            is ManagedNetworkPolicy.ForcedPrivateDns -> {
                if (alwaysOnPackage != null) {
                    issues += "Always-on VPN should be cleared"
                }
                if (!facade.supportsGlobalPrivateDns()) {
                    issues += "Forced Private DNS requires Android 10 or later"
                } else {
                    if (privateDnsMode != DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME) {
                        issues += "Private DNS mode mismatch"
                    }
                    if (privateDnsHost != policy.hostname) {
                        issues += "Private DNS host mismatch"
                    }
                }
            }

            ManagedNetworkPolicy.Unmanaged -> {
                if (alwaysOnPackage != null) {
                    issues += "Always-on VPN should be cleared"
                }
                if (
                    privateDnsMode != null &&
                    privateDnsMode != DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC
                ) {
                    issues += "Managed Private DNS should be cleared"
                }
            }
        }
    }

    private companion object {
        const val CORE_REPAIR_DELAY_MS = 60_000L
        const val SUPPORTING_REPAIR_DELAY_MS = 5L * 60_000L
    }
}

internal fun desiredUserControlDisabledPackages(
    uninstallProtectedPackages: Set<String>,
    sdkInt: Int = Build.VERSION.SDK_INT
): Set<String> {
    if (sdkInt < Build.VERSION_CODES.R) return emptySet()
    return uninstallProtectedPackages.asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()
}
