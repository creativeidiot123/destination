package com.ankit.destination.policy

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.os.Build
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class PolicyApplier(
    private val facade: DevicePolicyClient,
    private val protectedPackagesProvider: ProtectedPackagesProvider? = null
) {

    fun apply(state: PolicyState, hostActivity: Activity? = null): ApplyResult {
        FocusLog.d(FocusEventId.POLICY_APPLY_START, "┌── PolicyApplier.apply() START ──")
        FocusLog.d(FocusEventId.POLICY_APPLY_START, "│ mode=${state.mode} suspendTargets=${state.suspendTargets.size} prevSuspended=${state.previouslySuspended.size}")
        FocusLog.d(FocusEventId.POLICY_APPLY_START, "│ restrictions=${state.restrictions.size} uninstallProtected=${state.uninstallProtectedPackages.size} lockTaskAllowlist=${state.lockTaskAllowlist.size}")
        FocusLog.d(FocusEventId.POLICY_APPLY_START, "│ statusBarDisabled=${state.statusBarDisabled} requireAutoTime=${state.requireAutoTime} blockSelfUninstall=${state.blockSelfUninstall}")
        FocusLog.d(FocusEventId.POLICY_APPLY_START, "│ managedNetworkPolicy=${state.managedNetworkPolicy} vpnRequired=${state.vpnRequired}")
        val errors = mutableListOf<String>()

        if (!facade.isDeviceOwner()) {
            FocusLog.e(FocusEventId.POLICY_APPLY_START, "│ ❌ NOT device owner — aborting apply")
            errors += "Not device owner"
            return ApplyResult(
                failedToSuspend = emptySet(),
                failedToUnsuspend = emptySet(),
                failedToProtectUninstall = emptySet(),
                failedToUnprotectUninstall = emptySet(),
                errors = errors
            )
        }

        if (hostActivity != null) {
            val lockTaskState = facade.lockTaskModeState() ?: ActivityManager.LOCK_TASK_MODE_NONE
            if (lockTaskState != ActivityManager.LOCK_TASK_MODE_NONE) {
                FocusLog.d(FocusEventId.LOCK_CALC, "│ Exiting lock task (was mode=$lockTaskState)")
                runOnMainThreadBlocking(hostActivity) { hostActivity.stopLockTask() }
                    ?.let { errors += "stopLockTask failed: ${it.message}" }
            }
        }

        val desiredUninstallProtected = buildSet {
            if (state.blockSelfUninstall) add(facade.packageName)
            addAll(state.uninstallProtectedPackages)
        }
        val previouslyUninstallProtected = state.previouslyUninstallProtectedPackages
        val toProtect = desiredUninstallProtected - previouslyUninstallProtected
        val toUnprotect = previouslyUninstallProtected - desiredUninstallProtected
        val failedToProtectUninstall = linkedSetOf<String>()
        val failedToUnprotectUninstall = linkedSetOf<String>()

        FocusLog.d(FocusEventId.POLICY_APPLY_START, "│ uninstallProtect: toProtect=${toProtect.size} toUnprotect=${toUnprotect.size}")
        toProtect.forEach { pkg ->
            runCatching { facade.setUninstallBlocked(pkg, true) }
                .onFailure {
                    failedToProtectUninstall += pkg
                    errors += "setUninstallBlocked($pkg,true) failed: ${it.message}"
                    FocusLog.e(FocusEventId.POLICY_APPLY_START, "│ ❌ setUninstallBlocked($pkg,true) FAILED: ${it.message}")
                }
        }
        toUnprotect.forEach { pkg ->
            runCatching { facade.setUninstallBlocked(pkg, false) }
                .onFailure {
                    failedToUnprotectUninstall += pkg
                    errors += "setUninstallBlocked($pkg,false) failed: ${it.message}"
                    FocusLog.e(FocusEventId.POLICY_APPLY_START, "│ ❌ setUninstallBlocked($pkg,false) FAILED: ${it.message}")
                }
        }
        val desiredUserControlDisabled = desiredUserControlDisabledPackages(desiredUninstallProtected)
        if (facade.supportsUserControlDisabledPackages()) {
            FocusLog.d(FocusEventId.POLICY_APPLY_START, "│ userControlDisabled: ${desiredUserControlDisabled.size} packages")
            runCatching { facade.setUserControlDisabledPackages(desiredUserControlDisabled.toList()) }
                .onFailure {
                    errors += "setUserControlDisabledPackages failed: ${it.message}"
                }
        }

        val desiredRestrictions = state.restrictions
        val managedRestrictions = managedRestrictions(desiredRestrictions)
        FocusLog.d(FocusEventId.LOCK_CALC, "│ restrictions: desired=${desiredRestrictions.size} managed=${managedRestrictions.size}")
        managedRestrictions.forEach { restriction ->
            val shouldEnable = desiredRestrictions.contains(restriction)
            runCatching {
                if (shouldEnable) {
                    facade.addUserRestriction(restriction)
                } else {
                    facade.clearUserRestriction(restriction)
                }
            }.onSuccess {
                FocusLog.v(FocusEventId.LOCK_CALC, "│   restriction: $restriction ${if (shouldEnable) "SET" else "CLEARED"}")
            }.onFailure {
                if (PolicyRestrictions.isBestEffort(restriction)) {
                    FocusLog.v(FocusEventId.LOCK_CALC, "│   restriction: $restriction (best-effort) failed: ${it.message}")
                    return@onFailure
                }
                if (!shouldEnable && PolicyRestrictions.isBestEffortOnClear(restriction)) return@onFailure
                val op = if (shouldEnable) "addRestriction" else "clearRestriction"
                errors += "$op($restriction) failed: ${it.message}"
                FocusLog.e(FocusEventId.LOCK_CALC, "│ ❌ $op($restriction) FAILED: ${it.message}")
            }
        }

        runCatching { facade.setAutoTimeRequired(state.requireAutoTime) }
            .onFailure { errors += "setAutoTimeRequired failed: ${it.message}" }

        FocusLog.d(FocusEventId.LOCK_CALC, "│ lockTask: allowlist=${state.lockTaskAllowlist.size} features=${state.lockTaskFeatures}")
        runCatching { facade.setBlankDeviceOwnerLockScreenInfo() }
            .onFailure { errors += "setDeviceOwnerLockScreenInfo failed: ${it.message}" }

        runCatching { facade.setLockTaskPackages(state.lockTaskAllowlist.toList()) }
            .onFailure { errors += "setLockTaskPackages failed: ${it.message}" }

        runCatching { facade.setLockTaskFeatures(state.lockTaskFeatures) }
            .onFailure { errors += "setLockTaskFeatures failed: ${it.message}" }

        runCatching { facade.setStatusBarDisabled(state.statusBarDisabled) }
            .onSuccess { applied ->
                if (applied == false) {
                    errors += "setStatusBarDisabled returned false"
                }
            }
            .onFailure { errors += "setStatusBarDisabled failed: ${it.message}" }

        FocusLog.d(FocusEventId.MANAGED_NETWORK_CHANGE, "│ applyManagedNetworkPolicy: ${state.managedNetworkPolicy}")
        applyManagedNetworkPolicy(state.managedNetworkPolicy, errors)

        runCatching { facade.setAsHomeForKiosk(false) }
            .onFailure { errors += "setAsHomeForKiosk failed: ${it.message}" }

        val hardProtectedPackages = protectedPackagesProvider?.getHardProtectedPackages().orEmpty()
        val desiredSuspendTargets = state.suspendTargets - hardProtectedPackages
        val trackedPreviouslySuspended = state.previouslySuspended - hardProtectedPackages
        val skippedProtectedPackages = state.suspendTargets.intersect(hardProtectedPackages) +
            state.previouslySuspended.intersect(hardProtectedPackages)
        val toSuspend = desiredSuspendTargets - trackedPreviouslySuspended
        val toUnsuspend = trackedPreviouslySuspended - desiredSuspendTargets
        if (skippedProtectedPackages.isNotEmpty()) {
            FocusLog.w(
                FocusEventId.SUSPEND_TARGET,
                "protected packages excluded from suspension delta: ${skippedProtectedPackages.joinToString(",")}"
            )
        }
        FocusLog.d(FocusEventId.SUSPEND_TARGET, "│ SUSPEND DELTA: toSuspend=${toSuspend.size} toUnsuspend=${toUnsuspend.size}")
        if (toSuspend.isNotEmpty()) {
            FocusLog.d(FocusEventId.SUSPEND_TARGET, "│   suspending: ${toSuspend.joinToString(",")}")
        }
        if (toUnsuspend.isNotEmpty()) {
            FocusLog.d(FocusEventId.SUSPEND_TARGET, "│   unsuspending: ${toUnsuspend.joinToString(",")}")
        }
        val failedToSuspend: Set<String>
        val failedToUnsuspend: Set<String>
        if (toSuspend.isEmpty() && toUnsuspend.isEmpty()) {
            FocusLog.d(FocusEventId.SUSPEND_TARGET, "suspension state unchanged; skipping DPM suspend calls")
            failedToSuspend = emptySet()
            failedToUnsuspend = emptySet()
        } else {
            if (toSuspend.isNotEmpty()) {
                val suspendResult = facade.setPackagesSuspended(
                    packages = toSuspend.toList(),
                    suspended = true
                )
                failedToSuspend = suspendResult.failedPackages
                suspendResult.errors.forEach { error ->
                    errors += "setPackagesSuspended(true) failed: $error"
                }
            } else {
                failedToSuspend = emptySet()
            }
            if (toUnsuspend.isNotEmpty()) {
                val unsuspendResult = facade.setPackagesSuspended(
                    packages = toUnsuspend.toList(),
                    suspended = false
                )
                failedToUnsuspend = unsuspendResult.failedPackages
                unsuspendResult.errors.forEach { error ->
                    errors += "setPackagesSuspended(false) failed: $error"
                }
            } else {
                failedToUnsuspend = emptySet()
            }
        }
        if (failedToSuspend.isNotEmpty()) {
            errors += "Packages failed to suspend: ${failedToSuspend.sorted().joinToString(", ")}"
            FocusLog.e(FocusEventId.SUSPEND_TARGET, "│ ❌ FAILED to suspend: ${failedToSuspend.joinToString(",")}")
        }
        if (failedToUnsuspend.isNotEmpty()) {
            errors += "Packages failed to unsuspend: ${failedToUnsuspend.sorted().joinToString(", ")}"
            FocusLog.e(FocusEventId.SUSPEND_TARGET, "│ ❌ FAILED to unsuspend: ${failedToUnsuspend.joinToString(",")}")
        }


        FocusLog.d(FocusEventId.POLICY_APPLY_DONE, "└── PolicyApplier.apply() END — errors=${errors.size} failedSuspend=${failedToSuspend.size} failedUnsuspend=${failedToUnsuspend.size}")
        if (errors.isNotEmpty()) {
            errors.forEach { e -> FocusLog.w(FocusEventId.POLICY_APPLY_DONE, "  error: $e") }
        }

        return ApplyResult(
            failedToSuspend = failedToSuspend,
            failedToUnsuspend = failedToUnsuspend,
            failedToProtectUninstall = failedToProtectUninstall,
            failedToUnprotectUninstall = failedToUnprotectUninstall,
            errors = errors
        )
    }

    fun verify(state: PolicyState, hostActivity: Activity? = null): PolicyVerificationResult {
        FocusLog.d(FocusEventId.POLICY_VERIFY_PASS, "┌── PolicyApplier.verify() START ──")
        val issues = mutableListOf<String>()

        if (!facade.isDeviceOwner()) {
            issues += "Device owner not active"
            FocusLog.e(FocusEventId.POLICY_VERIFY_FAIL, "│ ❌ Device owner not active")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val lockTaskPackages = runCatching { facade.getLockTaskPackages().toSet() }.getOrElse {
                issues += "Unable to read lock task packages: ${it.message}"
                emptySet()
            }
            if (lockTaskPackages != state.lockTaskAllowlist) {
                issues += "Lock task packages mismatch"
                FocusLog.d(FocusEventId.POLICY_VERIFY_FAIL, "│ lockTask mismatch: actual=${lockTaskPackages.size} expected=${state.lockTaskAllowlist.size}")
            }
        }
        val lockTaskFeatures = runCatching { facade.getLockTaskFeatures() }.getOrNull()
        if (lockTaskFeatures != null && lockTaskFeatures != state.lockTaskFeatures) {
            issues += "Lock task features mismatch"
            FocusLog.d(FocusEventId.POLICY_VERIFY_FAIL, "│ lockTaskFeatures mismatch: actual=$lockTaskFeatures expected=${state.lockTaskFeatures}")
        }
        val homePinned = runCatching { facade.isHomeAppPinnedToSelf() }.getOrNull()
        if (homePinned == true) issues += "Home app pinning still active"

        verifyManagedNetworkPolicy(state.managedNetworkPolicy, issues)

        val desiredRestrictions = state.restrictions
        managedRestrictions(desiredRestrictions).forEach { restriction ->
            val shouldBeActive = desiredRestrictions.contains(restriction)
            val active = runCatching { facade.hasUserRestriction(restriction) }.getOrDefault(false)
            if (shouldBeActive && !active) {
                issues += "Restriction missing: $restriction"
                FocusLog.d(FocusEventId.POLICY_VERIFY_FAIL, "│ restriction missing: $restriction")
            }
            if (!shouldBeActive && active) {
                if (!PolicyRestrictions.isBestEffortOnClear(restriction)) {
                    issues += "Restriction still active: $restriction"
                    FocusLog.d(FocusEventId.POLICY_VERIFY_FAIL, "│ restriction still active: $restriction")
                }
            }
        }

        val expectedUninstallBlocked = buildSet {
            if (state.blockSelfUninstall) add(facade.packageName)
            addAll(state.uninstallProtectedPackages)
        }
        expectedUninstallBlocked.forEach { pkg ->
            val uninstallBlocked = runCatching { facade.isUninstallBlocked(pkg) }.getOrDefault(false)
            if (!uninstallBlocked) {
                issues += "Uninstall block missing: $pkg"
                FocusLog.d(FocusEventId.POLICY_VERIFY_FAIL, "│ uninstall block missing: $pkg")
            }
        }
        val shouldBeUninstallUnblocked = state.previouslyUninstallProtectedPackages - expectedUninstallBlocked
        shouldBeUninstallUnblocked.forEach { pkg ->
            val uninstallBlocked = runCatching { facade.isUninstallBlocked(pkg) }.getOrDefault(false)
            if (uninstallBlocked) {
                issues += "Uninstall block still active: $pkg"
                FocusLog.d(FocusEventId.POLICY_VERIFY_FAIL, "│ uninstall block NOT cleared: $pkg")
            }
        }
        if (facade.supportsUserControlDisabledPackages()) {
            val expectedUserControlDisabled = desiredUserControlDisabledPackages(expectedUninstallBlocked)
            val actualUserControlDisabled = runCatching { facade.getUserControlDisabledPackages() }.getOrElse {
                issues += "Unable to read user-control-disabled packages: ${it.message}"
                emptySet()
            }
            expectedUserControlDisabled.forEach { pkg ->
                if (!actualUserControlDisabled.contains(pkg)) {
                    issues += "User control disable missing: $pkg"
                }
            }
            val shouldBeUserControlEnabled = state.previouslyUninstallProtectedPackages - expectedUninstallBlocked
            shouldBeUserControlEnabled.forEach { pkg ->
                if (actualUserControlDisabled.contains(pkg)) {
                    issues += "User control disable still active: $pkg"
                }
            }
        }

        val autoTimeRequired = runCatching { facade.isAutoTimeRequired() }.getOrDefault(false)
        if (autoTimeRequired != state.requireAutoTime) {
            issues += "Auto time requirement mismatch"
        }

        var suspendedChecked = 0
        var suspendedMismatchCount = 0
        var suspendUnknownCount = 0
        if (facade.canVerifyPackageSuspension()) {
            val hardProtectedPackages = protectedPackagesProvider?.getHardProtectedPackages().orEmpty()
            val shouldBeSuspended = state.suspendTargets - hardProtectedPackages
            val shouldBeUnsuspended = (state.previouslySuspended - hardProtectedPackages) - shouldBeSuspended
            FocusLog.d(FocusEventId.POLICY_VERIFY_PASS, "│ verifying suspension: shouldSuspend=${shouldBeSuspended.size} shouldUnsuspend=${shouldBeUnsuspended.size}")
            shouldBeSuspended.forEach { packageName ->
                val suspended = facade.isPackageSuspended(packageName)
                if (suspended == null) {
                    suspendUnknownCount += 1
                    if (suspendUnknownCount <= 10) issues += "Unable to verify suspend state for $packageName"
                    return@forEach
                }
                suspendedChecked += 1
                if (!suspended) {
                    suspendedMismatchCount += 1
                    if (suspendedMismatchCount <= 10) {
                        issues += "Suspend mismatch for $packageName"
                        FocusLog.d(FocusEventId.POLICY_VERIFY_FAIL, "│ suspend mismatch: $packageName should be SUSPENDED but is NOT")
                    }
                }
            }
            shouldBeUnsuspended.forEach { packageName ->
                val suspended = facade.isPackageSuspended(packageName)
                if (suspended == null) {
                    suspendUnknownCount += 1
                    if (suspendUnknownCount <= 10) issues += "Unable to verify unsuspend state for $packageName"
                    return@forEach
                }
                suspendedChecked += 1
                if (suspended) {
                    suspendedMismatchCount += 1
                    if (suspendedMismatchCount <= 10) {
                        issues += "Unsuspend mismatch for $packageName"
                        FocusLog.d(FocusEventId.POLICY_VERIFY_FAIL, "│ unsuspend mismatch: $packageName should be UNSUSPENDED but is NOT")
                    }
                }
            }
            if (suspendedMismatchCount > 10) {
                issues += "Additional suspend mismatches: ${suspendedMismatchCount - 10}"
            }
            if (suspendUnknownCount > 10) {
                issues += "Additional suspend verification unknowns: ${suspendUnknownCount - 10}"
            }
        }

        val lockTaskActive = if (hostActivity != null) {
            val active = facade.lockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE
            if (active) {
                issues += "Lock task mode still active in foreground activity"
            }
            active
        } else {
            null
        }

        val passed = issues.isEmpty()
        FocusLog.d(
            if (passed) FocusEventId.POLICY_VERIFY_PASS else FocusEventId.POLICY_VERIFY_FAIL,
            "└── PolicyApplier.verify() END — ${if (passed) "✅ PASSED" else "❌ FAILED(${issues.size} issues)"} checked=$suspendedChecked mismatches=$suspendedMismatchCount"
        )
        if (!passed) {
            issues.take(5).forEach { issue ->
                FocusLog.d(FocusEventId.POLICY_VERIFY_FAIL, "  issue: $issue")
            }
        }

        return PolicyVerificationResult(
            passed = passed,
            issues = issues,
            suspendedChecked = suspendedChecked,
            suspendedMismatchCount = suspendedMismatchCount,
            lockTaskModeActive = lockTaskActive
        )
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
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            return ie
        }
        return when {
            !completed -> IllegalStateException("Main-thread action timed out")
            else -> error
        }
    }

    private fun managedRestrictions(desiredRestrictions: Set<String>): Set<String> {
        return PolicyRestrictions.managed(desiredRestrictions)
    }

    private fun applyManagedNetworkPolicy(
        policy: ManagedNetworkPolicy,
        errors: MutableList<String>
    ) {
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
                        check(
                            result == null || result == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR
                        ) { "error code=$result" }
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





