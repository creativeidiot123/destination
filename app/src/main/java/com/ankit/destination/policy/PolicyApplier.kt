package com.ankit.destination.policy

import android.app.Activity
import android.app.ActivityManager
import android.os.Build
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PolicyApplier(private val facade: DevicePolicyFacade) {

    fun apply(state: PolicyState, hostActivity: Activity? = null): ApplyResult {
        val errors = mutableListOf<String>()

        if (!facade.isDeviceOwner()) {
            errors += "Not device owner"
            return ApplyResult(
                failedToSuspend = emptySet(),
                failedToUnsuspend = emptySet(),
                failedToProtectUninstall = emptySet(),
                failedToUnprotectUninstall = emptySet(),
                errors = errors
            )
        }

        // If we're leaving Nuclear, exit lock task first to avoid a brief window where relaxed lock-task
        // features/status-bar settings could allow escapes while still pinned.
        if (hostActivity != null && state.mode == ModeState.NORMAL) {
            val lockTaskState = facade.lockTaskModeState() ?: ActivityManager.LOCK_TASK_MODE_NONE
            if (lockTaskState != ActivityManager.LOCK_TASK_MODE_NONE) {
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

        toProtect.forEach { pkg ->
            runCatching { facade.setUninstallBlocked(pkg, true) }
                .onFailure {
                    failedToProtectUninstall += pkg
                    errors += "setUninstallBlocked($pkg,true) failed: ${it.message}"
                }
        }
        toUnprotect.forEach { pkg ->
            runCatching { facade.setUninstallBlocked(pkg, false) }
                .onFailure {
                    failedToUnprotectUninstall += pkg
                    errors += "setUninstallBlocked($pkg,false) failed: ${it.message}"
                }
        }

        val desiredRestrictions = state.restrictions
        val managedRestrictions = managedRestrictions(desiredRestrictions)
        managedRestrictions.forEach { restriction ->
            val shouldEnable = desiredRestrictions.contains(restriction)
            runCatching {
                if (shouldEnable) {
                    facade.addUserRestriction(restriction)
                } else {
                    facade.clearUserRestriction(restriction)
                }
            }.onFailure {
                if (BEST_EFFORT_RESTRICTIONS.contains(restriction)) return@onFailure
                val op = if (shouldEnable) "addRestriction" else "clearRestriction"
                errors += "$op($restriction) failed: ${it.message}"
            }
        }

        runCatching { facade.setAutoTimeRequired(state.requireAutoTime) }
            .onFailure { errors += "setAutoTimeRequired failed: ${it.message}" }

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

        runCatching {
            if (state.alwaysOnVpnLockdown) {
                facade.setAlwaysOnVpnPackage(facade.packageName, true)
            } else {
                facade.setAlwaysOnVpnPackage(null, false)
            }
        }.onFailure { errors += "setAlwaysOnVpnPackage failed: ${it.message}" }

        runCatching { facade.setAsHomeForKiosk(state.mode == ModeState.NUCLEAR) }
            .onFailure { errors += "setAsHomeForKiosk failed: ${it.message}" }

        val toSuspend = state.suspendTargets - state.previouslySuspended
        val toUnsuspend = state.previouslySuspended - state.suspendTargets
        val suspendResult = facade.setPackagesSuspended(
            packages = toSuspend.toList(),
            suspended = true
        )
        val failedToSuspend = suspendResult.failedPackages
        suspendResult.errors.forEach { error ->
            errors += "setPackagesSuspended(true) failed: $error"
        }
        val unsuspendResult = facade.setPackagesSuspended(
            packages = toUnsuspend.toList(),
            suspended = false
        )
        val failedToUnsuspend = unsuspendResult.failedPackages
        unsuspendResult.errors.forEach { error ->
            errors += "setPackagesSuspended(false) failed: $error"
        }
        if (failedToSuspend.isNotEmpty()) {
            errors += "Packages failed to suspend: ${failedToSuspend.sorted().joinToString(", ")}"
        }
        if (failedToUnsuspend.isNotEmpty()) {
            errors += "Packages failed to unsuspend: ${failedToUnsuspend.sorted().joinToString(", ")}"
        }

        if (hostActivity != null) {
            if (state.mode == ModeState.NUCLEAR) {
                runOnMainThreadBlocking(hostActivity) { hostActivity.startLockTask() }
                    ?.let { errors += "startLockTask failed: ${it.message}" }
            }
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
        val issues = mutableListOf<String>()

        if (!facade.isDeviceOwner()) {
            issues += "Device owner not active"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val lockTaskPackages = runCatching { facade.getLockTaskPackages().toSet() }.getOrElse {
                issues += "Unable to read lock task packages: ${it.message}"
                emptySet()
            }
            if (lockTaskPackages != state.lockTaskAllowlist) {
                issues += "Lock task packages mismatch"
            }
        }
        val lockTaskFeatures = runCatching { facade.getLockTaskFeatures() }.getOrNull()
        if (lockTaskFeatures != null && lockTaskFeatures != state.lockTaskFeatures) {
            issues += "Lock task features mismatch"
        }
        val homePinned = runCatching { facade.isHomeAppPinnedToSelf() }.getOrNull()
        when {
            state.mode == ModeState.NUCLEAR && homePinned == false -> issues += "Home app pinning missing"
            state.mode == ModeState.NORMAL && homePinned == true -> issues += "Home app pinning still active"
        }

        val alwaysOnPackage = runCatching { facade.getAlwaysOnVpnPackage() }.getOrNull()
        if (state.alwaysOnVpnLockdown && alwaysOnPackage != facade.packageName) {
            issues += "Always-on VPN package mismatch"
        }
        if (!state.alwaysOnVpnLockdown && alwaysOnPackage != null) {
            issues += "Always-on VPN should be cleared"
        }
        val lockdownEnabled = runCatching { facade.isAlwaysOnVpnLockdownEnabled() }.getOrNull()
        if (state.alwaysOnVpnLockdown && lockdownEnabled != null && !lockdownEnabled) {
            issues += "Always-on VPN lockdown not enabled"
        }

        val desiredRestrictions = state.restrictions
        managedRestrictions(desiredRestrictions).forEach { restriction ->
            val shouldBeActive = desiredRestrictions.contains(restriction)
            val active = runCatching { facade.hasUserRestriction(restriction) }.getOrDefault(false)
            if (shouldBeActive && !active) {
                issues += "Restriction missing: $restriction"
            }
            if (!shouldBeActive && active) {
                issues += "Restriction still active: $restriction"
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
            }
        }
        val shouldBeUninstallUnblocked = state.previouslyUninstallProtectedPackages - expectedUninstallBlocked
        shouldBeUninstallUnblocked.forEach { pkg ->
            val uninstallBlocked = runCatching { facade.isUninstallBlocked(pkg) }.getOrDefault(false)
            if (uninstallBlocked) {
                issues += "Uninstall block still active: $pkg"
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
            val shouldBeSuspended = state.suspendTargets
            val shouldBeUnsuspended = state.previouslySuspended - state.suspendTargets
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
            when {
                state.mode == ModeState.NUCLEAR && !active -> issues += "Lock task mode not active in foreground activity"
                state.mode == ModeState.NORMAL && active -> issues += "Lock task mode still active in foreground activity"
            }
            active
        } else {
            null
        }

        return PolicyVerificationResult(
            passed = issues.isEmpty(),
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

    private fun managedRestrictions(desiredRestrictions: Set<String>): Set<String> = buildSet {
        addAll(FocusConfig.nuclearRestrictions())
        addAll(desiredRestrictions)
        addAll(BEST_EFFORT_RESTRICTIONS)
    }

    private companion object {
        private val BEST_EFFORT_RESTRICTIONS = setOf(
            "no_add_clone_profile",
            "no_add_private_profile"
        )
    }
}


