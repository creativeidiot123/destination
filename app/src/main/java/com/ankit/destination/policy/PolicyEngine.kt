package com.ankit.destination.policy

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.ankit.destination.budgets.BudgetOrchestrator
import com.ankit.destination.data.AppGroupMap
import com.ankit.destination.data.AppPolicy
import com.ankit.destination.data.EmergencyState
import com.ankit.destination.data.EmergencyStateMerger
import com.ankit.destination.data.EmergencyTargetType
import com.ankit.destination.data.FocusDatabase
import com.ankit.destination.data.GlobalControls
import com.ankit.destination.data.GroupEmergencyConfig
import com.ankit.destination.data.GroupLimit
import com.ankit.destination.data.ScheduleBlockGroup
import com.ankit.destination.schedule.AlarmScheduler
import com.ankit.destination.schedule.ScheduleDecision
import com.ankit.destination.schedule.ScheduleEvaluator
import com.ankit.destination.usage.UsageAccess
import com.ankit.destination.usage.UsageAccessMonitor
import com.ankit.destination.usage.UsageWindow
import com.ankit.destination.vpn.FocusVpnService
import com.ankit.destination.vpn.VpnStatusStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

class PolicyEngine(context: Context) {
    private val appContext = context.applicationContext
    private val facade = DevicePolicyFacade(appContext)
    private val store = PolicyStore(appContext)
    private val resolver = PackageResolver(appContext)
    private val db by lazy { FocusDatabase.get(appContext) }
    private val budgetOrchestrator by lazy { BudgetOrchestrator(appContext) }
    private val evaluator = PolicyEvaluator(resolver, appContext.packageName)
    private val applier = PolicyApplier(facade)
    private val alarmScheduler by lazy { AlarmScheduler(appContext) }
    @Volatile private var policyControlsCache: PolicyControls? = null

    fun isDeviceOwner(): Boolean = facade.isDeviceOwner()

    fun getDesiredMode(): ModeState = store.getDesiredMode()

    fun getManualMode(): ModeState = store.getManualMode()

    fun isScheduleLockActive(): Boolean = store.isScheduleLockEnforced()

    fun isStrictScheduleActive(): Boolean = store.isScheduleStrictComputed()

    fun isScheduleLockComputed(): Boolean = store.isScheduleLockComputed()

    fun getScheduleLockReason(): String? = store.getScheduleLockReason()

    fun getScheduleBlockedGroupIds(): Set<String> = store.getScheduleBlockedGroups()

    fun getBudgetBlockedPackages(): Set<String> = store.getBudgetBlockedPackages()

    fun getBudgetBlockedGroupIds(): Set<String> = store.getBudgetBlockedGroupIds()

    fun getBudgetReason(): String? = store.getBudgetReason()

    fun isBudgetUsageAccessGranted(): Boolean = store.isBudgetUsageAccessGranted()

    fun getBudgetNextCheckAtMs(): Long? = store.getBudgetNextCheckAtMs()

    fun getStrictInstallSuspendedPackages(): Set<String> = store.getStrictInstallSuspendedPackages()

    fun getScheduleNextTransitionAtMs(): Long? = store.getScheduleNextTransitionAtMs()

    fun currentUsageAccessComplianceState(
        usageAccessGranted: Boolean = UsageAccess.hasUsageAccess(appContext)
    ): UsageAccessComplianceState {
        val lockdownEligible = isUsageAccessLockdownEligible(
            deviceOwnerActive = isDeviceOwner(),
            provisioningFinalizationState = store.getProvisioningFinalizationState(),
            hasSuccessfulPolicyApply = store.hasSuccessfulPolicyApply(),
            hasAnyPriorApply = store.hasAnyPriorApply()
        )
        val lockdownActive = lockdownEligible && !usageAccessGranted
        val recoveryResolution = if (lockdownActive) {
            resolver.resolveUsageAccessRecoveryPackages()
        } else {
            null
        }
        val baseReason = if (lockdownActive) {
            "Usage Access missing: recovery lockdown active"
        } else {
            null
        }
        val reason = when {
            baseReason == null -> null
            recoveryResolution == null -> baseReason
            recoveryResolution.warnings.isEmpty() -> baseReason
            else -> "$baseReason. ${recoveryResolution.warnings.joinToString("; ")}"
        }
        return UsageAccessComplianceState(
            usageAccessGranted = usageAccessGranted,
            lockdownEligible = lockdownEligible,
            lockdownActive = lockdownActive,
            recoveryAllowlist = recoveryResolution?.packages.orEmpty(),
            reason = reason
        )
    }

    fun getEmergencyApps(): Set<String> {
        return resolveEmergencyApps(
            storedEmergencyApps = store.getEmergencyApps(),
            hasExplicitSelection = store.hasExplicitEmergencyAppsSelection()
        )
    }

    fun setEmergencyApps(packages: Set<String>) {
        store.setEmergencyApps(
            packages
                .asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .toSet()
        )
    }

    fun getGlobalControls(): GlobalControls = loadPolicyControls().globalControls

    suspend fun getGlobalControlsAsync(): GlobalControls = loadPolicyControlsAsync().globalControls

    fun getManagedNetworkPolicy(): ManagedNetworkPolicy {
        return getGlobalControls().toManagedNetworkPolicy(appContext.packageName)
    }

    fun getManagedVpnPackage(): String? {
        return when (val policy = getManagedNetworkPolicy()) {
            is ManagedNetworkPolicy.ForcedVpn -> policy.packageName
            else -> null
        }
    }

    fun setGlobalControls(controls: GlobalControls) {
        runBlocking {
            setGlobalControlsAsync(controls)
        }
    }

    suspend fun setGlobalControlsAsync(controls: GlobalControls) {
        withContext(Dispatchers.IO) {
            db.budgetDao().upsertGlobalControls(controls.copy(id = 1))
        }
        invalidatePolicyControlsCache()
    }

    fun getAlwaysAllowedApps(): Set<String> = loadPolicyControls().alwaysAllowedApps

    suspend fun getAlwaysAllowedAppsAsync(): Set<String> = loadPolicyControlsAsync().alwaysAllowedApps

    fun getAlwaysBlockedApps(): Set<String> = loadPolicyControls().alwaysBlockedApps

    suspend fun getAlwaysBlockedAppsAsync(): Set<String> = loadPolicyControlsAsync().alwaysBlockedApps

    fun getUninstallProtectedApps(): Set<String> = loadPolicyControls().uninstallProtectedApps

    suspend fun getUninstallProtectedAppsAsync(): Set<String> = loadPolicyControlsAsync().uninstallProtectedApps

    fun addAlwaysAllowedApp(packageName: String) {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return
        runBlocking {
            addAlwaysAllowedAppAsync(normalized)
        }
    }

    suspend fun addAlwaysAllowedAppAsync(packageName: String) {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return
        withContext(Dispatchers.IO) {
            db.budgetDao().addAlwaysAllowedExclusive(normalized)
        }
        invalidatePolicyControlsCache()
    }

    fun removeAlwaysAllowedApp(packageName: String) {
        runBlocking {
            removeAlwaysAllowedAppAsync(packageName)
        }
    }

    suspend fun removeAlwaysAllowedAppAsync(packageName: String) {
        withContext(Dispatchers.IO) {
            db.budgetDao().deleteAlwaysAllowed(packageName.trim())
        }
        invalidatePolicyControlsCache()
    }

    fun addAlwaysBlockedApp(packageName: String) {
        val normalized = packageName.trim()
        if (normalized.isBlank() || getAlwaysAllowedApps().contains(normalized)) return
        runBlocking {
            addAlwaysBlockedAppAsync(normalized)
        }
    }

    suspend fun addAlwaysBlockedAppAsync(packageName: String) {
        val normalized = packageName.trim()
        if (normalized.isBlank() || loadPolicyControlsAsync().alwaysAllowedApps.contains(normalized)) return
        withContext(Dispatchers.IO) {
            db.budgetDao().addAlwaysBlockedExclusive(normalized)
        }
        invalidatePolicyControlsCache()
    }

    fun removeAlwaysBlockedApp(packageName: String) {
        runBlocking {
            removeAlwaysBlockedAppAsync(packageName)
        }
    }

    suspend fun removeAlwaysBlockedAppAsync(packageName: String) {
        withContext(Dispatchers.IO) {
            db.budgetDao().deleteAlwaysBlocked(packageName.trim())
        }
        invalidatePolicyControlsCache()
    }

    fun addUninstallProtectedApp(packageName: String) {
        val normalized = packageName.trim()
        if (normalized.isBlank() || normalized == appContext.packageName) return
        runBlocking {
            addUninstallProtectedAppAsync(normalized)
        }
    }

    suspend fun addUninstallProtectedAppAsync(packageName: String) {
        val normalized = packageName.trim()
        if (normalized.isBlank() || normalized == appContext.packageName) return
        withContext(Dispatchers.IO) {
            db.budgetDao().upsertUninstallProtected(
                com.ankit.destination.data.UninstallProtectedApp(normalized)
            )
        }
        invalidatePolicyControlsCache()
    }

    fun removeUninstallProtectedApp(packageName: String) {
        runBlocking {
            removeUninstallProtectedAppAsync(packageName)
        }
    }

    suspend fun removeUninstallProtectedAppAsync(packageName: String) {
        withContext(Dispatchers.IO) {
            db.budgetDao().deleteUninstallProtected(packageName.trim())
        }
        invalidatePolicyControlsCache()
    }

    fun setManualModePreference(mode: ModeState) {
        store.setManualMode(mode)
    }

    fun setMode(mode: ModeState, hostActivity: Activity? = null, reason: String = "manual"): EngineResult {
        synchronized(APPLY_LOCK) {
            val previousEffectiveMode = store.getDesiredMode()
            val emergencyApps = getEmergencyApps()

            FocusLog.i(FocusEventId.MODE_CHANGE_REQUEST, "Requested mode=$mode reason=$reason")

            if (!isDeviceOwner()) {
                return failureResult(
                    message = "Not device owner",
                    stateMode = previousEffectiveMode,
                    emergencyApps = emergencyApps
                )
            }

            if (store.isScheduleLockEnforced() && mode == ModeState.NORMAL) {
                return failureResult(
                    message = "This lock is scheduled and cannot be canceled.",
                    stateMode = previousEffectiveMode,
                    emergencyApps = emergencyApps
                )
            }
            val previousManualMode = store.getManualMode()
            store.setManualMode(mode)
            val result = requestApplyNowLocked(hostActivity = hostActivity, reason = "manual:$reason")
            if (!result.success) {
                store.setManualMode(previousManualMode)
            }
            return result
        }
    }

    fun applyScheduleDecision(
        decision: ScheduleDecision,
        scheduleBlockedPackages: Set<String>,
        trigger: String,
        hostActivity: Activity? = null
    ): EngineResult {
        return requestApplyNow(hostActivity = hostActivity, reason = "schedule:$trigger")
    }

    fun setBudgetState(
        blockedPackages: Set<String>,
        blockedGroupIds: Set<String>,
        reason: String?,
        usageAccessGranted: Boolean,
        nextCheckAtMs: Long?,
        hostActivity: Activity? = null,
        trigger: String = "budget"
    ): EngineResult {
        return requestApplyNow(hostActivity = hostActivity, reason = "budget:$trigger")
    }

    fun shouldRunBudgetEvaluation(nowMs: Long = System.currentTimeMillis(), graceMs: Long = 15_000L): Boolean {
        return isBudgetEvaluationDue(nowMs, store.getBudgetNextCheckAtMs(), graceMs)
    }

    fun requestApplyNow(hostActivity: Activity? = null, reason: String = "apply_now"): EngineResult {
        synchronized(APPLY_LOCK) {
            return requestApplyNowLocked(hostActivity = hostActivity, reason = reason)
        }
    }

    fun reapplyDesiredMode(hostActivity: Activity? = null, reason: String = "reapply"): EngineResult {
        return requestApplyNow(hostActivity = hostActivity, reason = "reapply:$reason")
    }

    fun resetToFreshState(hostActivity: Activity? = null, reason: String = "fresh_reset"): EngineResult {
        synchronized(APPLY_LOCK) {
            if (!isDeviceOwner()) {
                return failureResult(
                    message = "Not device owner",
                    stateMode = store.getDesiredMode(),
                    emergencyApps = getEmergencyApps()
                )
            }

            val trackedPackages = sanitizeTrackedPackages()
            val cleanControls = GlobalControls()
            val cleanEmergencyApps = resolveEmergencyApps(
                storedEmergencyApps = emptySet(),
                hasExplicitSelection = false
            )

            val cleanState = evaluator.evaluate(
                mode = ModeState.NORMAL,
                emergencyApps = cleanEmergencyApps,
                alwaysAllowedApps = emptySet(),
                alwaysBlockedApps = emptySet(),
                strictInstallBlockedPackages = emptySet(),
                uninstallProtectedApps = emptySet(),
                globalControls = cleanControls,
                previouslySuspended = trackedPackages.previouslySuspended,
                previouslyUninstallProtected = trackedPackages.previouslyUninstallProtected,
                budgetBlockedPackages = emptySet(),
                primaryReasonByPackage = emptyMap(),
                lockReason = null,
                touchGrassBreakActive = false,
                usageAccessComplianceState = UsageAccessComplianceState(
                    usageAccessGranted = true,
                    lockdownEligible = false,
                    lockdownActive = false,
                    recoveryAllowlist = emptySet(),
                    reason = null
                )
            )
            val outcome = runCatching {
                store.resetForFreshStart()
                invalidatePolicyControlsCache()
                VpnStatusStore(appContext).clear()
                FocusLog.clearHistory(appContext)
                FocusLog.w(
                    appContext,
                    FocusEventId.POLICY_RESET_START,
                    "Reset requested reason=$reason suspended=${trackedPackages.previouslySuspended.size} uninstallProtected=${trackedPackages.previouslyUninstallProtected.size}"
                )

                runBlocking {
                    withContext(Dispatchers.IO) {
                        db.scheduleDao().clearAllSchedules()
                        db.budgetDao().resetAllPolicyData(cleanControls)
                    }
                }
                FocusVpnService.stop(appContext)

                val applyResult = applier.apply(cleanState, hostActivity)
                val verification = applier.verify(cleanState, hostActivity)
                val success = applyResult.errors.isEmpty() && verification.passed
                val errorMessage = if (success) {
                    null
                } else {
                    buildList {
                        addAll(applyResult.errors)
                        addAll(verification.issues)
                    }.joinToString(" | ")
                }

                store.recordApply(
                    state = cleanState,
                    applyResult = applyResult,
                    verification = verification,
                    errorMessage = errorMessage,
                    controllerPackageName = appContext.packageName
                )

                if (success) {
                    FocusLog.i(
                        appContext,
                        FocusEventId.POLICY_RESET_DONE,
                        "Reset applied successfully reason=$reason"
                    )
                    EngineResult(
                        success = true,
                        message = "App reset to fresh policy state",
                        verification = verification,
                        state = cleanState
                    )
                } else {
                    FocusLog.w(
                        appContext,
                        FocusEventId.POLICY_RESET_FAIL,
                        "Reset completed with issues reason=$reason error=$errorMessage"
                    )
                    EngineResult(
                        success = false,
                        message = "Reset completed with issues: $errorMessage",
                        verification = verification,
                        state = cleanState
                    )
                }
            }

            return outcome.getOrElse { throwable ->
                val message = throwable.message ?: "Unknown reset failure"
                FocusLog.w(
                    appContext,
                    FocusEventId.POLICY_RESET_FAIL,
                    "Reset failed reason=$reason error=$message"
                )
                EngineResult(
                    success = false,
                    message = "Reset failed: $message",
                    verification = PolicyVerificationResult(
                        passed = false,
                        issues = listOf(message),
                        suspendedChecked = 0,
                        suspendedMismatchCount = 0,
                        lockTaskModeActive = null
                    ),
                    state = cleanState
                )
            }
        }
    }

    fun removeDeviceOwner(reason: String = "manual_remove"): Result<Unit> {
        return runCatching {
            if (!isDeviceOwner()) {
                error("Destination is not the active device owner")
            }
            FocusLog.w(
                appContext,
                FocusEventId.POLICY_RESET_START,
                "Device owner removal requested reason=$reason"
            )
            facade.clearDeviceOwnerApp()
        }
    }

    fun verifyDesiredMode(hostActivity: Activity? = null): PolicyVerificationResult {
        val trackedPackages = sanitizeTrackedPackages()
        val external = computeExternalState()
        val state = evaluator.evaluate(
            mode = store.getDesiredMode(),
            emergencyApps = getEmergencyApps(),
            alwaysAllowedApps = external.policyControls.alwaysAllowedApps,
            alwaysBlockedApps = external.policyControls.alwaysBlockedInstalledPackages,
            strictInstallBlockedPackages = external.strictInstallBlockedPackages,
            uninstallProtectedApps = external.policyControls.uninstallProtectedApps,
            globalControls = external.policyControls.globalControls,
            previouslySuspended = trackedPackages.previouslySuspended,
            previouslyUninstallProtected = trackedPackages.previouslyUninstallProtected,
            budgetBlockedPackages = external.budgetBlockedPackages,
            blockReasonsByPackage = external.blockReasonsByPackage,
            primaryReasonByPackage = external.primaryReasonByPackage,
            lockReason = external.lockReason,
            touchGrassBreakActive = external.touchGrassBreakActive,
            usageAccessComplianceState = external.usageAccessComplianceState
        )
        return applier.verify(state, hostActivity)
    }

    fun diagnosticsSnapshot(): DiagnosticsSnapshot {
        val desiredMode = store.getDesiredMode()
        val trackedPackages = sanitizeTrackedPackages()
        val external = computeExternalState()
        val expected = evaluator.evaluate(
            mode = desiredMode,
            emergencyApps = getEmergencyApps(),
            alwaysAllowedApps = external.policyControls.alwaysAllowedApps,
            alwaysBlockedApps = external.policyControls.alwaysBlockedInstalledPackages,
            strictInstallBlockedPackages = external.strictInstallBlockedPackages,
            uninstallProtectedApps = external.policyControls.uninstallProtectedApps,
            globalControls = external.policyControls.globalControls,
            previouslySuspended = trackedPackages.previouslySuspended,
            previouslyUninstallProtected = trackedPackages.previouslyUninstallProtected,
            budgetBlockedPackages = external.budgetBlockedPackages,
            blockReasonsByPackage = external.blockReasonsByPackage,
            primaryReasonByPackage = external.primaryReasonByPackage,
            lockReason = external.lockReason,
            touchGrassBreakActive = external.touchGrassBreakActive,
            usageAccessComplianceState = external.usageAccessComplianceState
        )
        val restrictions = FocusConfig.nuclearRestrictions().associateWith { facade.hasUserRestriction(it) }
        val scheduleReason = store.getScheduleLockReason()
        val scheduleComputed = store.isScheduleLockComputed()
        val scheduleActive = store.isScheduleLockEnforced()
        val scheduleStrictComputed = store.isScheduleStrictComputed()
        val scheduleStrictActive = store.isScheduleStrictEnforced()
        val compliance = external.usageAccessComplianceState
        return DiagnosticsSnapshot(
            deviceOwner = isDeviceOwner(),
            desiredMode = desiredMode,
            manualMode = store.getManualMode(),
            usageAccessGranted = compliance.usageAccessGranted,
            usageAccessRecoveryLockdownActive = compliance.lockdownActive,
            usageAccessRecoveryAllowlist = compliance.recoveryAllowlist,
            usageAccessRecoveryReason = compliance.reason,
            lastUsageAccessCheckAtMs = UsageAccessMonitor.currentState.value.lastCheckAtMs,
            scheduleLockComputed = scheduleComputed,
            scheduleLockActive = scheduleActive,
            scheduleStrictComputed = scheduleStrictComputed,
            scheduleStrictActive = scheduleStrictActive,
            scheduleBlockedGroups = store.getScheduleBlockedGroups(),
            scheduleLockReason = scheduleReason,
            scheduleNextTransitionAtMs = store.getScheduleNextTransitionAtMs(),
            budgetBlockedPackages = store.getBudgetBlockedPackages(),
            budgetBlockedGroupIds = store.getBudgetBlockedGroupIds(),
            budgetReason = store.getBudgetReason(),
            budgetUsageAccessGranted = store.isBudgetUsageAccessGranted(),
            budgetNextCheckAtMs = store.getBudgetNextCheckAtMs(),
            touchGrassBreakActive = false,
            touchGrassBreakUntilMs = null,
            unlockCountToday = 0,
            unlockCountDay = null,
            touchGrassThreshold = 0,
            touchGrassBreakMinutes = 0,
            lockTaskPackages = runCatching { facade.getLockTaskPackages().toSet() }.getOrDefault(emptySet()),
            lockTaskFeatures = runCatching { facade.getLockTaskFeatures() }.getOrNull() ?: expected.lockTaskFeatures,
            statusBarDisabledObserved = null,
            statusBarDisabledExpected = expected.statusBarDisabled,
            lastAppliedAtMs = store.getLastAppliedAtMs(),
            lastVerificationPassed = store.getLastVerifyPassed(),
            lastError = store.getLastError(),
            lastSuspendedPackages = store.getLastSuspendedPackages(),
            restrictions = restrictions,
            vpnActive = isVpnActive(),
            vpnRequiredForNuclear = false,
            vpnLockdownRequired = shouldEnforceVpnLockdown(external.policyControls.globalControls, appContext.packageName),
            vpnLastError = VpnStatusStore(appContext).getLastError(),
            alwaysOnVpnPackage = runCatching { facade.getAlwaysOnVpnPackage() }.getOrNull(),
            alwaysOnVpnLockdown = runCatching { facade.isAlwaysOnVpnLockdownEnabled() }.getOrNull(),
            privateDnsMode = runCatching { facade.getGlobalPrivateDnsMode() }.getOrNull(),
            privateDnsHost = runCatching { facade.getGlobalPrivateDnsHost() }.getOrNull(),
            managedNetworkMode = expected.managedNetworkPolicy.label(),
            managedVpnPackage = (expected.managedNetworkPolicy as? ManagedNetworkPolicy.ForcedVpn)?.packageName,
            managedVpnLockdown = (expected.managedNetworkPolicy as? ManagedNetworkPolicy.ForcedVpn)?.lockdown,
            managedPrivateDnsHost = (expected.managedNetworkPolicy as? ManagedNetworkPolicy.ForcedPrivateDns)?.hostname,
            domainRuleCount = VpnStatusStore(appContext).getDomainRuleCount(),
            currentLockReason = store.getCurrentLockReason(),
            emergencyApps = getEmergencyApps(),
            allowlistReasons = store.getLastAllowlistReasons(),
            alwaysAllowedApps = external.policyControls.alwaysAllowedApps,
            alwaysBlockedApps = external.policyControls.alwaysBlockedApps,
            uninstallProtectedApps = external.policyControls.uninstallProtectedApps,
            globalControls = external.policyControls.globalControls,
            primaryReasonByPackage = expected.primaryReasonByPackage
        )
    }

    fun onNewPackageInstalledDuringStrictSchedule(packageName: String): Boolean {
        synchronized(APPLY_LOCK) {
            if (!isDeviceOwner()) return false
            val orchestrated = orchestrateCurrentPolicy()
            persistComputedState(orchestrated)
            if (!orchestrated.strictActive) return false
            val controls = loadPolicyControls()
            if (controls.alwaysAllowedApps.contains(packageName)) return false
            val allowlist = resolver.resolveAllowlist(
                userChosenEmergencyApps = getEmergencyApps(),
                alwaysAllowedApps = controls.alwaysAllowedApps
            ).packages
            val suspendable = resolver.filterSuspendable(setOf(packageName), allowlist)
            if (suspendable.isEmpty()) return false
            store.addStrictInstallSuspendedPackage(packageName)
            FocusLog.i(FocusEventId.STRICT_INSTALL_SUSPEND, "Strict schedule staged suspend pkg=$packageName")
            return true
        }
    }

    private fun applyModeInternal(
        targetMode: ModeState,
        hostActivity: Activity?,
        reason: String,
        rollbackMode: ModeState,
        rollbackLockReason: String?,
        emergencyApps: Set<String>,
        external: ExternalState
    ): EngineResult {
        val normalizedTargetMode = resolveEffectiveMode(
            manualMode = targetMode,
            scheduleComputed = false,
            touchGrassBreakUntilMs = null,
            nowMs = System.currentTimeMillis()
        )
        if (!isDeviceOwner()) {
            return failureResult(
                message = "Not device owner",
                stateMode = rollbackMode,
                emergencyApps = emergencyApps
            )
        }
        val vpnRequiredForThisApply = false
        if (vpnRequiredForThisApply && !isVpnActive()) {
            return failureResult(
                message = "VPN is required before enabling Nuclear mode",
                stateMode = rollbackMode,
                emergencyApps = emergencyApps
            )
        }

        val trackedPackages = sanitizeTrackedPackages()
        val state = evaluator.evaluate(
            mode = normalizedTargetMode,
            emergencyApps = emergencyApps,
            alwaysAllowedApps = external.policyControls.alwaysAllowedApps,
            alwaysBlockedApps = external.policyControls.alwaysBlockedInstalledPackages,
            strictInstallBlockedPackages = external.strictInstallBlockedPackages,
            uninstallProtectedApps = external.policyControls.uninstallProtectedApps,
            globalControls = external.policyControls.globalControls,
            previouslySuspended = trackedPackages.previouslySuspended,
            previouslyUninstallProtected = trackedPackages.previouslyUninstallProtected,
            budgetBlockedPackages = external.budgetBlockedPackages,
            blockReasonsByPackage = external.blockReasonsByPackage,
            primaryReasonByPackage = external.primaryReasonByPackage,
            lockReason = external.lockReason,
            touchGrassBreakActive = external.touchGrassBreakActive,
            usageAccessComplianceState = external.usageAccessComplianceState
        )
        store.setPrimaryReasonByPackage(state.primaryReasonByPackage)
        val managedVpnPackage = (state.managedNetworkPolicy as? ManagedNetworkPolicy.ForcedVpn)?.packageName
        if (
            managedVpnPackage == appContext.packageName &&
            FocusVpnService.isPrepared(appContext)
        ) {
            FocusVpnService.start(appContext)
        }
        FocusLog.i(
            FocusEventId.POLICY_APPLY_START,
            "Applying mode=${state.mode} reason=$reason allowlist=${state.lockTaskAllowlist.size} suspend=${state.suspendTargets.size}"
        )

        val applyResult = applier.apply(state, hostActivity)
        val verification = applier.verify(state, hostActivity)
        val vpnSatisfied = !vpnRequiredForThisApply || isVpnActive()
        val success = applyResult.errors.isEmpty() && verification.passed && vpnSatisfied
        if (success) {
            store.setDesiredMode(normalizedTargetMode)
            store.setEmergencyApps(
                if (external.usageAccessComplianceState.lockdownActive) {
                    emergencyApps
                } else {
                    state.emergencyApps
                }
            )
            store.setCurrentLockReason(external.lockReason)
            store.recordApply(state, applyResult, verification, null, appContext.packageName)
            FocusLog.i(FocusEventId.POLICY_APPLY_DONE, "Apply success mode=${state.mode}")
            return EngineResult(true, "Policy applied", verification, state)
        }

        val errorMessage = buildList {
            addAll(applyResult.errors)
            addAll(verification.issues)
            if (!vpnSatisfied) add("VPN not active")
        }.joinToString(" | ")
        store.recordApply(state, applyResult, verification, errorMessage, appContext.packageName)
        FocusLog.w(FocusEventId.MODE_CHANGE_FAIL, "Apply failed mode=$targetMode error=$errorMessage")

        val actualSuspendedAfterApply = PolicyStore.reconcileTrackedPackages(
            previousPackages = trackedPackages.previouslySuspended,
            targetPackages = state.suspendTargets,
            failedAdds = applyResult.failedToSuspend,
            failedRemovals = applyResult.failedToUnsuspend
        )
        val actualUninstallProtectedAfterApply = PolicyStore.reconcileTrackedPackages(
            previousPackages = trackedPackages.previouslyUninstallProtected,
            targetPackages = PolicyStore.desiredUninstallProtectedPackages(state, appContext.packageName),
            failedAdds = applyResult.failedToProtectUninstall,
            failedRemovals = applyResult.failedToUnprotectUninstall
        )

        val rollbackState = evaluator.evaluate(
            mode = rollbackMode,
            emergencyApps = emergencyApps,
            alwaysAllowedApps = external.policyControls.alwaysAllowedApps,
            alwaysBlockedApps = external.policyControls.alwaysBlockedInstalledPackages,
            strictInstallBlockedPackages = external.strictInstallBlockedPackages,
            uninstallProtectedApps = external.policyControls.uninstallProtectedApps,
            globalControls = external.policyControls.globalControls,
            previouslySuspended = actualSuspendedAfterApply,
            previouslyUninstallProtected = actualUninstallProtectedAfterApply,
            budgetBlockedPackages = external.budgetBlockedPackages,
            blockReasonsByPackage = external.blockReasonsByPackage,
            primaryReasonByPackage = external.primaryReasonByPackage,
            lockReason = rollbackLockReason,
            touchGrassBreakActive = external.touchGrassBreakActive,
            usageAccessComplianceState = external.usageAccessComplianceState
        )
        val rollbackApply = applier.apply(rollbackState, hostActivity)
        val rollbackVerify = applier.verify(rollbackState, hostActivity)
        val rollbackOk = rollbackApply.errors.isEmpty() && rollbackVerify.passed
        val rollbackError = if (rollbackOk) null else buildList {
            addAll(rollbackApply.errors)
            addAll(rollbackVerify.issues)
        }.joinToString(" | ")
        store.setDesiredMode(rollbackMode)
        store.setCurrentLockReason(rollbackLockReason)
        store.recordApply(rollbackState, rollbackApply, rollbackVerify, rollbackError, appContext.packageName)

        return EngineResult(false, "Policy failed: $errorMessage", verification, state)
    }

    private fun failureResult(
        message: String,
        stateMode: ModeState,
        emergencyApps: Set<String>
    ): EngineResult {
        val trackedPackages = sanitizeTrackedPackages()
        val external = computeExternalState()
        val state = evaluator.evaluate(
            mode = stateMode,
            emergencyApps = emergencyApps,
            alwaysAllowedApps = external.policyControls.alwaysAllowedApps,
            alwaysBlockedApps = external.policyControls.alwaysBlockedInstalledPackages,
            strictInstallBlockedPackages = external.strictInstallBlockedPackages,
            uninstallProtectedApps = external.policyControls.uninstallProtectedApps,
            globalControls = external.policyControls.globalControls,
            previouslySuspended = trackedPackages.previouslySuspended,
            previouslyUninstallProtected = trackedPackages.previouslyUninstallProtected,
            budgetBlockedPackages = external.budgetBlockedPackages,
            blockReasonsByPackage = external.blockReasonsByPackage,
            primaryReasonByPackage = external.primaryReasonByPackage,
            lockReason = external.lockReason,
            touchGrassBreakActive = external.touchGrassBreakActive,
            usageAccessComplianceState = external.usageAccessComplianceState
        )
        return EngineResult(
            success = false,
            message = message,
            verification = PolicyVerificationResult(
                passed = false,
                issues = listOf(message),
                suspendedChecked = 0,
                suspendedMismatchCount = 0,
                lockTaskModeActive = null
            ),
            state = state
        )
    }

    private fun isVpnActive(): Boolean {
        if (FocusVpnService.isRunning(appContext)) {
            return true
        }
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun sanitizeTrackedPackages(): TrackedPackages {
        val installedCheck = resolver::isPackageInstalled
        return TrackedPackages(
            previouslySuspended = retainInstalledTrackedPackages(
                trackedPackages = store.getLastSuspendedPackages(),
                controllerPackageName = appContext.packageName,
                isInstalled = installedCheck
            ),
            previouslyUninstallProtected = retainInstalledTrackedPackages(
                trackedPackages = store.getLastUninstallProtectedPackages(),
                controllerPackageName = appContext.packageName,
                isInstalled = installedCheck
            )
        )
    }

    private fun requestApplyNowLocked(hostActivity: Activity?, reason: String): EngineResult {
        val overallStartNs = System.nanoTime()
        FocusLog.d(FocusEventId.POLICY_STATE_COMPUTED, "â•”â•â• PolicyEngine.requestApplyNow() reason=$reason â•â•")
        UsageAccessMonitor.refreshNow(
            context = appContext,
            reason = "policy_request:$reason",
            requestPolicyRefreshIfChanged = false
        )
        val emergencyApps = getEmergencyApps()
        val previousMode = store.getDesiredMode()
        val previousLockReason = store.getCurrentLockReason()
        val previousScheduleEnforced = store.isScheduleLockEnforced()
        val previousStrictEnforced = store.isScheduleStrictEnforced()
        FocusLog.d(FocusEventId.POLICY_STATE_COMPUTED, "â•‘ prevMode=$previousMode prevLockReason=$previousLockReason prevSchedEnforced=$previousScheduleEnforced prevStrict=$previousStrictEnforced")

        if (!isDeviceOwner()) {
            FocusLog.e(FocusEventId.POLICY_STATE_COMPUTED, "â•šâ•â• NOT device owner â€” aborting â•â•")
            return failureResult(
                message = "Not device owner",
                stateMode = previousMode,
                emergencyApps = emergencyApps
            )
        }

        val orchestrated = FocusLog.timed(FocusEventId.POLICY_STATE_COMPUTED, "orchestrateCurrentPolicy") {
            orchestrateCurrentPolicy()
        }
        persistComputedState(orchestrated)
        val external = computeExternalState(orchestrated.nowMs)
        FocusLog.d(FocusEventId.POLICY_STATE_COMPUTED, "â•‘ effectiveMode=${external.effectiveMode} schedLock=${orchestrated.scheduleDecision.shouldLock} strict=${orchestrated.strictActive}")
        FocusLog.d(FocusEventId.POLICY_STATE_COMPUTED, "â•‘ suspendTargets=${external.budgetBlockedPackages.size} schedBlockedGroups=${orchestrated.scheduleDecision.blockedGroupIds.size} budgetBlocked=${external.budgetBlockedPackages.size}")
        val result = FocusLog.timed(FocusEventId.POLICY_APPLY_START, "applyModeInternal") {
            applyModeInternal(
                targetMode = external.effectiveMode,
                hostActivity = hostActivity,
                reason = "request:$reason",
                rollbackMode = previousMode,
                rollbackLockReason = previousLockReason,
                emergencyApps = emergencyApps,
                external = external
            )
        }
        if (result.success) {
            store.setScheduleEnforced(orchestrated.scheduleDecision.shouldLock)
            store.setScheduleStrictEnforced(orchestrated.strictActive)
        } else {
            store.setScheduleEnforced(previousScheduleEnforced)
            store.setScheduleStrictEnforced(previousStrictEnforced)
            FocusLog.w(FocusEventId.POLICY_STATE_COMPUTED, "â•‘ apply FAILED â€” rolled back schedule state")
        }
        syncNextAlarm(
            nowMs = orchestrated.nowMs,
            keepOverdueBudgetCheck = shouldRunBudgetEvaluation(nowMs = orchestrated.nowMs)
        )
        val totalMs = (System.nanoTime() - overallStartNs) / 1_000_000.0
        FocusLog.d(FocusEventId.POLICY_STATE_COMPUTED, "â•šâ•â• requestApplyNow() DONE in %.1fms success=${result.success} â•â•".format(totalMs))
        return result
    }

    private fun syncNextAlarm(nowMs: Long = System.currentTimeMillis(), keepOverdueBudgetCheck: Boolean = false) {
        val nextAlarmAt = pickNextAlarmAtMs(
            nowMs = nowMs,
            scheduleNextTransitionAtMs = store.getScheduleNextTransitionAtMs(),
            budgetNextCheckAtMs = store.getBudgetNextCheckAtMs(),
            touchGrassBreakUntilMs = store.getTouchGrassBreakUntilMs(),
            keepOverdueBudgetCheck = keepOverdueBudgetCheck
        )
        nextAlarmAt?.let(alarmScheduler::scheduleNextTransition) ?: alarmScheduler.cancelNextTransition()
    }

    private fun orchestrateCurrentPolicy(now: ZonedDateTime = ZonedDateTime.now()): OrchestratedState {
        val policyControls = loadPolicyControls()
        val allowlistPackages = resolver.resolveAllowlist(
            userChosenEmergencyApps = getEmergencyApps(),
            alwaysAllowedApps = policyControls.alwaysAllowedApps
        ).packages
        val loaded = runBlocking {
            withContext(Dispatchers.IO) {
                val budgetDao = db.budgetDao()
                val scheduleDao = db.scheduleDao()
                val blocks = scheduleDao.getEnabledBlocks()
                val blockGroups = scheduleDao.getAllBlockGroups()
                    .groupBy(ScheduleBlockGroup::blockId, ScheduleBlockGroup::groupId)
                    .mapValues { it.value.toSet() }
                LoadedPolicyRows(
                    groupLimits = budgetDao.getEnabledGroupLimits(),
                    groupEmergencyConfigs = budgetDao.getAllGroupEmergencyConfigs().associateBy { it.groupId },
                    appPolicies = budgetDao.getEnabledAppPolicies(),
                    mappings = budgetDao.getAllMappings(),
                    scheduleBlocks = blocks,
                    scheduleBlockGroups = blockGroups,
                    emergencyStates = budgetOrchestrator.getActiveEmergencyStates(now)
                )
            }
        }
        val scheduleDecision = ScheduleEvaluator.evaluate(now, loaded.scheduleBlocks, loaded.scheduleBlockGroups)
        val scheduledTargetIds = scheduleDecision.blockedGroupIds
        val scheduledGroupIds = scheduledTargetIds.filterTo(linkedSetOf()) { !isSingleAppScheduleTarget(it) }
        val scheduledAppPackages = scheduledTargetIds.mapNotNullTo(linkedSetOf()) { decodeSingleAppScheduleTarget(it) }
        val usageSnapshot = runBlocking { budgetOrchestrator.readUsageSnapshot(now) }
        val groupMembers = loaded.mappings.groupBy(AppGroupMap::groupId) { it.packageName }
        val groupInputs = loaded.groupLimits.map { limit ->
            GroupPolicyInput(
                groupId = limit.groupId,
                priorityIndex = limit.priorityIndex,
                strictEnabled = limit.strictEnabled,
                dailyLimitMs = limit.dailyLimitMs,
                hourlyLimitMs = limit.hourlyLimitMs,
                opensPerDay = limit.opensPerDay,
                members = groupMembers[limit.groupId].orEmpty().toSet(),
                emergencyConfig = loaded.groupEmergencyConfigs[limit.groupId].toEmergencyConfig(),
                scheduleBlocked = scheduledGroupIds.contains(limit.groupId)
            )
        }
        val strictActive = resolveStrictScheduleActive(
            scheduleStrictActive = scheduleDecision.strictActive,
            groupInputs = groupInputs
        )
        val strictInstallBlocked = if (strictActive) {
            store.getStrictInstallSuspendedPackages()
        } else {
            emptySet()
        }
        val appInputs = loaded.appPolicies.map { policy ->
            AppPolicyInput(
                packageName = policy.packageName,
                dailyLimitMs = policy.dailyLimitMs,
                hourlyLimitMs = policy.hourlyLimitMs,
                opensPerDay = policy.opensPerDay,
                emergencyConfig = EmergencyConfigInput(
                    enabled = policy.emergencyEnabled,
                    unlocksPerDay = policy.unlocksPerDay,
                    minutesPerUnlock = policy.minutesPerUnlock
                ),
                scheduleBlocked = scheduledAppPackages.contains(policy.packageName)
            )
        }
        val nowMs = now.toInstant().toEpochMilli()
        val dayKey = UsageWindow.dayKey(now)
        val mergedEmergencyStates = EmergencyStateMerger.merge(
            dayKey = dayKey,
            nowMs = nowMs,
            rows = loaded.emergencyStates
        )
        val emergencyStates = mergedEmergencyStates.map {
            EmergencyStateInput(
                targetType = runCatching { EmergencyTargetType.valueOf(it.targetType) }
                    .getOrDefault(EmergencyTargetType.GROUP),
                targetId = it.targetId,
                unlocksUsedToday = it.unlocksUsedToday,
                activeUntilEpochMs = it.activeUntilEpochMs
            )
        }
        val evaluation = EffectivePolicyEvaluator.evaluate(
            nowMs = nowMs,
            usageInputs = usageSnapshot.usageInputs,
            groupPolicies = groupInputs,
            appPolicies = appInputs,
            emergencyStates = emergencyStates,
            strictInstallBlockedPackages = strictInstallBlocked,
            alwaysAllowedPackages = allowlistPackages
        )
        return OrchestratedState(
            nowMs = nowMs,
            usageAccessGranted = usageSnapshot.usageAccessGranted,
            usageAccessComplianceState = currentUsageAccessComplianceState(usageSnapshot.usageAccessGranted),
            nextCheckAtMs = usageSnapshot.nextCheckAtMs,
            scheduleDecision = scheduleDecision,
            scheduledGroupIds = scheduledGroupIds,
            policyControls = policyControls,
            evaluation = evaluation,
            strictActive = strictActive
        )
    }

    private fun persistComputedState(orchestrated: OrchestratedState) {
        val allowlistPackages = resolver.resolveAllowlist(
            userChosenEmergencyApps = getEmergencyApps(),
            alwaysAllowedApps = orchestrated.policyControls.alwaysAllowedApps
        ).packages
        val budgetBlockedSuspendable = resolver.filterSuspendable(
            packages = orchestrated.evaluation.effectiveBlockedPackages,
            allowlist = allowlistPackages
        )
        val scheduleBlockedSuspendable = resolver.filterSuspendable(
            packages = orchestrated.evaluation.scheduledBlockedPackages,
            allowlist = allowlistPackages
        )
        val alwaysBlockedSuspendable = resolver.filterSuspendable(
            packages = orchestrated.policyControls.alwaysBlockedInstalledPackages,
            allowlist = allowlistPackages
        )
        val strictInstallSuspendable = resolver.filterSuspendable(
            packages = orchestrated.evaluation.strictInstallBlockedPackages,
            allowlist = allowlistPackages
        )
        val suspendableSet = linkedSetOf<String>().apply {
            addAll(budgetBlockedSuspendable)
            addAll(scheduleBlockedSuspendable)
            addAll(alwaysBlockedSuspendable)
            addAll(strictInstallSuspendable)
        }
        val normalizedBlockReasons = orchestrated.evaluation.blockReasonsByPackage
            .asSequence()
            .mapNotNull { (rawPackage, reasons) ->
                val normalizedPackage = rawPackage.trim()
                if (normalizedPackage.isBlank() || !suspendableSet.contains(normalizedPackage)) return@mapNotNull null
                val normalizedReasons = reasons.asSequence()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .toSet()
                if (normalizedReasons.isEmpty()) null else normalizedPackage to normalizedReasons
            }
            .toMap()
        val fullReasons = linkedMapOf<String, MutableSet<String>>()
        normalizedBlockReasons.forEach { (pkg, reasons) ->
            fullReasons.getOrPut(pkg) { linkedSetOf() }.addAll(reasons)
        }
        orchestrated.evaluation.strictInstallBlockedPackages.forEach { pkg ->
            if (strictInstallSuspendable.contains(pkg)) {
                fullReasons.getOrPut(pkg) { linkedSetOf() }.add(EffectiveBlockReason.STRICT_INSTALL.name)
            }
        }
        alwaysBlockedSuspendable.forEach { pkg ->
            fullReasons.getOrPut(pkg) { linkedSetOf() }.add(EffectiveBlockReason.ALWAYS_BLOCKED.name)
        }
        scheduleBlockedSuspendable.forEach { pkg ->
            val reasons = fullReasons.getOrPut(pkg) { linkedSetOf() }
            if (reasons.isEmpty()) {
                reasons.add(EffectiveBlockReason.SCHEDULED_BLOCK.name)
            }
        }
        val immutableReasonMap = fullReasons.mapValues { it.value.toSet() }
        val derivedPrimaryReasons = BlockReasonUtils.derivePrimaryByPackage(immutableReasonMap)
        val derivedSuspendTargets = immutableReasonMap.keys.toSet()
        store.setComputedPolicyState(
            scheduleLockComputed = orchestrated.scheduleDecision.shouldLock,
            scheduleStrictComputed = orchestrated.strictActive,
            scheduleBlockedGroups = orchestrated.scheduledGroupIds,
            scheduleBlockedPackages = scheduleBlockedSuspendable,
            scheduleLockReason = orchestrated.scheduleDecision.reason,
            scheduleNextTransitionAtMs = orchestrated.scheduleDecision.nextTransitionAt?.toInstant()?.toEpochMilli(),
            budgetBlockedPackages = derivedSuspendTargets,
            budgetBlockedGroupIds = orchestrated.evaluation.effectiveBlockedGroupIds,
            budgetReason = orchestrated.usageAccessComplianceState.reason
                ?: orchestrated.evaluation.usageReasonSummary
                ?: if (!orchestrated.usageAccessGranted) "Usage access not granted" else null,
            budgetUsageAccessGranted = orchestrated.usageAccessGranted,
            budgetNextCheckAtMs = orchestrated.nextCheckAtMs,
            primaryReasonByPackage = derivedPrimaryReasons,
            blockReasonsByPackage = immutableReasonMap,
            clearStrictInstallSuspendedPackages = !orchestrated.strictActive
        )
        if (
            orchestrated.usageAccessComplianceState.lockdownActive &&
            orchestrated.usageAccessComplianceState.reason?.contains("No Settings package resolved") == true
        ) {
            FocusLog.w(
                appContext,
                FocusEventId.POLICY_STATE_COMPUTED,
                "Usage Access recovery lockdown active without resolved Settings or launcher packages"
            )
        }
        FocusLog.i(
            appContext,
            FocusEventId.POLICY_STATE_COMPUTED,
            "scheduleLock=${orchestrated.scheduleDecision.shouldLock} strict=${orchestrated.strictActive} scheduledGroups=${orchestrated.scheduledGroupIds.size} scheduledApps=${orchestrated.evaluation.scheduledBlockedPackages.size} blockedPackages=${orchestrated.evaluation.effectiveBlockedPackages.size} blockedGroups=${orchestrated.evaluation.effectiveBlockedGroupIds.size} usageAccess=${orchestrated.usageAccessGranted}"
        )
    }

    private fun computeExternalState(nowMs: Long = System.currentTimeMillis()): ExternalState {
        val policyControls = loadPolicyControls()
        val allowlistPackages = resolver.resolveAllowlist(
            userChosenEmergencyApps = getEmergencyApps(),
            alwaysAllowedApps = policyControls.alwaysAllowedApps
        ).packages
        val usageAccessComplianceState = currentUsageAccessComplianceState()
        val scheduleComputed = store.isScheduleLockComputed()
        val strictActive = store.isScheduleStrictComputed()
        val strictInstallBlocked = if (strictActive) {
            store.getStrictInstallSuspendedPackages()
        } else {
            emptySet()
        }
        val storedReasonSets = store.getBlockReasonsByPackage()
        val budgetBlockedCandidates = (
            if (storedReasonSets.isNotEmpty()) {
                storedReasonSets.keys
            } else {
                store.getBudgetBlockedPackages() + store.getScheduleBlockedPackages()
            }
        )
        val budgetBlocked = resolver.filterSuspendable(
            packages = budgetBlockedCandidates,
            allowlist = allowlistPackages
        )
        val primaryReason = store.getPrimaryReasonByPackage().ifEmpty { computePrimaryReasonByPackage(
            alwaysBlocked = policyControls.alwaysBlockedInstalledPackages,
            budgetBlocked = budgetBlocked,
            scheduleBlocked = store.getScheduleBlockedPackages(),
            strictInstallBlocked = strictInstallBlocked
        ) }
        val fallbackReasonSets = if (storedReasonSets.isNotEmpty()) {
            storedReasonSets
        } else {
            primaryReason.mapValues { setOf(it.value) }
        }
        val filteredReasonSets = fallbackReasonSets
            .asSequence()
            .mapNotNull { (pkg, reasons) ->
                val normalizedPkg = pkg.trim()
                if (!budgetBlocked.contains(normalizedPkg)) {
                    return@mapNotNull null
                }
                val normalizedReasons = reasons
                    .asSequence()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .toSet()
                if (normalizedReasons.isEmpty()) null else normalizedPkg to normalizedReasons
            }
            .toMap()
        val effectiveMode = resolveEffectiveMode(
            manualMode = store.getManualMode(),
            scheduleComputed = scheduleComputed,
            touchGrassBreakUntilMs = null,
            nowMs = nowMs
        )
        val scheduleReason = store.getScheduleLockReason()
        val lockReason = when {
            usageAccessComplianceState.lockdownActive -> usageAccessComplianceState.reason
            strictActive -> scheduleReason ?: "Strict group schedule active"
            scheduleComputed -> scheduleReason ?: "Scheduled block active"
            budgetBlocked.isNotEmpty() -> store.getBudgetReason() ?: "Policy limits exceeded"
            else -> null
        }
        return ExternalState(
            effectiveMode = effectiveMode,
            budgetBlockedPackages = budgetBlocked,
            strictInstallBlockedPackages = strictInstallBlocked,
            touchGrassBreakActive = false,
            lockReason = lockReason,
            policyControls = policyControls,
            primaryReasonByPackage = primaryReason,
            blockReasonsByPackage = filteredReasonSets,
            usageAccessComplianceState = usageAccessComplianceState
        )
    }

    private data class ExternalState(
        val effectiveMode: ModeState,
        val budgetBlockedPackages: Set<String>,
        val strictInstallBlockedPackages: Set<String>,
        val touchGrassBreakActive: Boolean,
        val lockReason: String?,
        val policyControls: PolicyControls,
        val primaryReasonByPackage: Map<String, String>,
        val blockReasonsByPackage: Map<String, Set<String>>,
        val usageAccessComplianceState: UsageAccessComplianceState
    )

    private data class TrackedPackages(
        val previouslySuspended: Set<String>,
        val previouslyUninstallProtected: Set<String>
    )

    private data class PolicyControls(
        val globalControls: GlobalControls,
        val alwaysAllowedApps: Set<String>,
        val alwaysBlockedApps: Set<String>,
        val alwaysBlockedInstalledPackages: Set<String>,
        val uninstallProtectedApps: Set<String>
    )

    private data class LoadedPolicyRows(
        val groupLimits: List<GroupLimit>,
        val groupEmergencyConfigs: Map<String, GroupEmergencyConfig>,
        val appPolicies: List<AppPolicy>,
        val mappings: List<AppGroupMap>,
        val scheduleBlocks: List<com.ankit.destination.data.ScheduleBlock>,
        val scheduleBlockGroups: Map<Long, Set<String>>,
        val emergencyStates: List<EmergencyState>
    )

    private data class OrchestratedState(
        val nowMs: Long,
        val usageAccessGranted: Boolean,
        val usageAccessComplianceState: UsageAccessComplianceState,
        val nextCheckAtMs: Long?,
        val scheduleDecision: ScheduleDecision,
        val scheduledGroupIds: Set<String>,
        val policyControls: PolicyControls,
        val evaluation: EffectivePolicyEvaluation,
        val strictActive: Boolean
    )

    private fun loadPolicyControls(): PolicyControls = runBlocking { loadPolicyControlsAsync() }

    private suspend fun loadPolicyControlsAsync(): PolicyControls {
        policyControlsCache?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            val dao = db.budgetDao()
            val global = dao.getGlobalControls() ?: GlobalControls()
            val alwaysAllowed = normalizeConfiguredPackages(
                packages = dao.getAlwaysAllowedPackages(),
                implicitPackages = setOf(appContext.packageName)
            )
            val alwaysBlocked = normalizeConfiguredPackages(dao.getAlwaysBlockedPackages())
                .filterNot(alwaysAllowed::contains)
                .toCollection(linkedSetOf())
            val uninstallProtected = normalizeConfiguredPackages(
                packages = dao.getUninstallProtectedPackages(),
                implicitPackages = setOf(appContext.packageName)
            )
            val installedAlwaysBlocked = alwaysBlocked.filterTo(linkedSetOf()) { resolver.isPackageInstalled(it) }
            PolicyControls(
                globalControls = global,
                alwaysAllowedApps = alwaysAllowed,
                alwaysBlockedApps = alwaysBlocked,
                alwaysBlockedInstalledPackages = installedAlwaysBlocked,
                uninstallProtectedApps = uninstallProtected
            )
        }
        policyControlsCache = loaded
        return loaded
    }

    private fun invalidatePolicyControlsCache() {
        policyControlsCache = null
    }

    private fun GroupEmergencyConfig?.toEmergencyConfig(): EmergencyConfigInput {
        return EmergencyConfigInput(
            enabled = this?.enabled == true,
            unlocksPerDay = this?.unlocksPerDay ?: 0,
            minutesPerUnlock = this?.minutesPerUnlock ?: 0
        )
    }


    companion object {
        // Coalesce/serialize policy applications across BootReceiver + UI to avoid interleaving DPM calls.
        private val APPLY_LOCK = Any()


        internal fun resolveEmergencyApps(
            storedEmergencyApps: Set<String>,
            hasExplicitSelection: Boolean,
            defaultEmergencyApps: Set<String> = FocusConfig.defaultEmergencyPackages
        ): Set<String> {
            return if (storedEmergencyApps.isNotEmpty() || hasExplicitSelection) {
                storedEmergencyApps
            } else {
                defaultEmergencyApps
            }
        }

        internal fun retainInstalledTrackedPackages(
            trackedPackages: Set<String>,
            controllerPackageName: String,
            isInstalled: (String) -> Boolean
        ): Set<String> {
            return trackedPackages.filterTo(linkedSetOf()) { packageName ->
                packageName == controllerPackageName || isInstalled(packageName)
            }
        }

        internal fun normalizeConfiguredPackages(
            packages: Iterable<String>,
            implicitPackages: Set<String> = emptySet()
        ): Set<String> = buildSet {
            packages.asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .forEach(::add)
            implicitPackages.asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .forEach(::add)
        }

        internal fun isUsageAccessLockdownEligible(
            deviceOwnerActive: Boolean,
            provisioningFinalizationState: String?,
            hasSuccessfulPolicyApply: Boolean,
            hasAnyPriorApply: Boolean = hasSuccessfulPolicyApply
        ): Boolean {
            if (!deviceOwnerActive) return false
            return when (provisioningFinalizationState) {
                ProvisioningCoordinator.FinalizationState.SUCCESS.name -> true
                null -> hasSuccessfulPolicyApply
                else -> hasAnyPriorApply
            }
        }

        internal fun shouldEnforceVpnLockdown(
            globalControls: GlobalControls,
            controllerPackageName: String,
            featureEnabled: Boolean = FocusConfig.enforceAlwaysOnVpnLockdown
        ): Boolean {
            if (!featureEnabled) return false
            return when (val policy = globalControls.toManagedNetworkPolicy(controllerPackageName)) {
                is ManagedNetworkPolicy.ForcedVpn -> policy.lockdown
                else -> false
            }
        }

        internal fun combinedBlockedPackages(
            alwaysBlocked: Set<String>,
            budgetBlocked: Set<String>,
            scheduleBlocked: Set<String>,
            strictInstallBlocked: Set<String>
        ): Set<String> = buildSet {
            addAll(alwaysBlocked)
            addAll(budgetBlocked)
            addAll(scheduleBlocked)
            addAll(strictInstallBlocked)
        }

        internal fun computePrimaryReasonByPackage(
            alwaysBlocked: Set<String>,
            budgetBlocked: Set<String>,
            scheduleBlocked: Set<String>,
            strictInstallBlocked: Set<String>
        ): Map<String, String> {
            val ordered = linkedMapOf<String, String>()
            alwaysBlocked.forEach { ordered[it] = "ALWAYS_BLOCKED" }
            scheduleBlocked.forEach { pkg -> ordered.putIfAbsent(pkg, "SCHEDULE_GROUP") }
            strictInstallBlocked.forEach { pkg -> ordered.putIfAbsent(pkg, "STRICT_INSTALL") }
            budgetBlocked.forEach { pkg -> ordered.putIfAbsent(pkg, "BUDGET") }
            return ordered
        }

        internal fun resolveStrictScheduleActive(
            scheduleStrictActive: Boolean,
            groupInputs: List<GroupPolicyInput>
        ): Boolean {
            return scheduleStrictActive || groupInputs.any { it.scheduleBlocked && it.strictEnabled }
        }

        fun isBudgetEvaluationDue(nowMs: Long, nextCheckAtMs: Long?, graceMs: Long = 15_000L): Boolean {
            val nextCheckAt = nextCheckAtMs ?: return true
            return nowMs + graceMs >= nextCheckAt
        }

        internal fun pickNextAlarmAtMs(
            nowMs: Long,
            scheduleNextTransitionAtMs: Long?,
            budgetNextCheckAtMs: Long?,
            touchGrassBreakUntilMs: Long?,
            keepOverdueBudgetCheck: Boolean = false
        ): Long? {
            val normalizedBudgetCheck = when {
                budgetNextCheckAtMs == null -> null
                budgetNextCheckAtMs > nowMs -> budgetNextCheckAtMs
                keepOverdueBudgetCheck -> nowMs + 1L
                else -> null
            }
            return listOfNotNull(
                scheduleNextTransitionAtMs,
                normalizedBudgetCheck,
                touchGrassBreakUntilMs
            ).filter { it > nowMs }.minOrNull()
        }

        fun resolveEffectiveMode(
            manualMode: ModeState,
            scheduleComputed: Boolean,
            touchGrassBreakUntilMs: Long?,
            nowMs: Long
        ): ModeState {
            if (!FocusConfig.enableNuclearMode) return ModeState.NORMAL

            // Touch-grass break is modeled as a temporary relaxation window.
            // If it is active, do not escalate/maintain NUCLEAR mode via schedule.
            val touchGrassBreakActive = touchGrassBreakUntilMs?.let { it > nowMs } == true
            if (touchGrassBreakActive) return ModeState.NORMAL

            return if (manualMode == ModeState.NUCLEAR || scheduleComputed) {
                ModeState.NUCLEAR
            } else {
                ModeState.NORMAL
            }
        }

        internal fun encodeSingleAppScheduleTarget(packageName: String): String = "app:$packageName"

        internal fun decodeSingleAppScheduleTarget(targetId: String): String? {
            val trimmed = targetId.trim()
            return if (trimmed.startsWith("app:") && trimmed.length > 4) trimmed.removePrefix("app:") else null
        }

        internal fun isSingleAppScheduleTarget(targetId: String): Boolean {
            return decodeSingleAppScheduleTarget(targetId) != null
        }
    }
}












