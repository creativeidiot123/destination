package com.ankit.destination.policy

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.ankit.destination.budgets.BudgetOrchestrator
import com.ankit.destination.budgets.PolicyBudgetClient
import com.ankit.destination.data.AppGroupMap
import com.ankit.destination.data.AppPolicy
import com.ankit.destination.data.EmergencyState
import com.ankit.destination.data.EmergencyStateMerger
import com.ankit.destination.data.EmergencyTargetType
import com.ankit.destination.data.FocusDatabase
import com.ankit.destination.data.GlobalControls
import com.ankit.destination.data.GroupEmergencyConfig
import com.ankit.destination.data.HiddenApp
import com.ankit.destination.data.GroupLimit
import com.ankit.destination.data.GroupTargetMode
import com.ankit.destination.data.ScheduleBlockGroup
import com.ankit.destination.enforce.AccessibilityStatusMonitor
import com.ankit.destination.packages.PackageChangeReceiver
import com.ankit.destination.schedule.AlarmScheduler
import com.ankit.destination.schedule.AlarmSchedulerClient
import com.ankit.destination.schedule.ScheduleDecision
import com.ankit.destination.schedule.ScheduleEvaluator
import com.ankit.destination.security.AppLockManager
import com.ankit.destination.usage.UsageAccess
import com.ankit.destination.usage.UsageAccessMonitor
import com.ankit.destination.usage.UsageWindow
import com.ankit.destination.vpn.FocusVpnService
import com.ankit.destination.vpn.VpnStatusStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

class PolicyEngine private constructor(
    private val appContext: Context,
    private val clock: PolicyClock,
    private val facade: DevicePolicyClient,
    private val resolver: PackageResolverClient,
    private val budgetClient: PolicyBudgetClient,
    private val alarmScheduler: AlarmSchedulerClient
) {
    private val store = PolicyStore(appContext)
    private val db by lazy { FocusDatabase.get(appContext) }
    private val evaluator = PolicyEvaluator(resolver, appContext.packageName)
    private val applier = PolicyApplier(facade, resolver)
    @Volatile private var policyControlsCache: PolicyControls? = null

    constructor(context: Context) : this(
        appContext = context.applicationContext,
        clock = SystemPolicyClock,
        facade = DevicePolicyFacade(context.applicationContext),
        resolver = PackageResolver(context.applicationContext),
        budgetClient = BudgetOrchestrator(context.applicationContext),
        alarmScheduler = AlarmScheduler(context.applicationContext)
    )

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

    fun isHiddenSuspendPrototypeEnabled(): Boolean = store.isHiddenSuspendPrototypeEnabled()

    fun setHiddenSuspendPrototypeEnabled(enabled: Boolean) {
        store.setHiddenSuspendPrototypeEnabled(enabled)
    }

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

    fun invalidatePolicyControlsForDiagnosticsImport() {
        invalidatePolicyControlsCache()
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

    fun getHiddenApps(): List<HiddenApp> = loadPolicyControls().hiddenApps

    suspend fun getHiddenAppsAsync(): List<HiddenApp> = loadPolicyControlsAsync().hiddenApps

    internal suspend fun getAppProtectionSnapshotAsync(
        usageAccessComplianceState: UsageAccessComplianceState = currentUsageAccessComplianceState()
    ): AppProtectionSnapshot {
        return buildAppProtectionSnapshot(loadPolicyControlsAsync(), usageAccessComplianceState)
    }

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
            db.budgetDao().upsertAlwaysAllowed(com.ankit.destination.data.AlwaysAllowedApp(normalized))
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
        if (normalized.isBlank()) return
        runBlocking {
            addAlwaysBlockedAppAsync(normalized)
        }
    }

    suspend fun addAlwaysBlockedAppAsync(packageName: String) {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return
        withContext(Dispatchers.IO) {
            db.budgetDao().upsertAlwaysBlocked(com.ankit.destination.data.AlwaysBlockedApp(normalized))
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

    suspend fun addHiddenAppAsync(packageName: String, locked: Boolean = false) {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return
        withContext(Dispatchers.IO) {
            db.budgetDao().upsertHiddenApp(HiddenApp(packageName = normalized, locked = locked))
        }
        invalidatePolicyControlsCache()
    }

    suspend fun removeHiddenAppAsync(packageName: String): Boolean {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return false
        val removed = withContext(Dispatchers.IO) {
            ensureDefaultHiddenAppsSeededLocked()
            val hidden = db.budgetDao().getHiddenApps()
                .firstOrNull { it.packageName == normalized }
                ?: return@withContext false
            if (hidden.locked) {
                return@withContext false
            }
            db.budgetDao().deleteHiddenApp(normalized)
            true
        }
        if (removed) {
            invalidatePolicyControlsCache()
        }
        return removed
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

    fun shouldRunBudgetEvaluation(nowMs: Long = clock.nowMs(), graceMs: Long = 15_000L): Boolean {
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
                protectionSnapshot = AppProtectionSnapshot(
                    allowlistedPackages = emptySet(),
                    hiddenPackages = emptySet(),
                    lockedHiddenPackages = emptySet(),
                    runtimeExemptPackages = emptySet(),
                    runtimeExemptionReasons = emptyMap(),
                    hardProtectedPackages = emptySet()
                ),
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
                AppLockManager(appContext).clearAll()
                invalidatePolicyControlsCache()
                VpnStatusStore(appContext).clear()
                PackageChangeReceiver.clearPersistedState(appContext)
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

                store.resetForFreshStart()
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
            protectionSnapshot = external.protectionSnapshot,
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
        val nowMs = clock.nowMs()
        val external = computeExternalState(nowMs)
        val liveScheduleTargetWarning = runCatching {
            computeLiveScheduleTargetWarning(clock.now())
        }.onFailure { throwable ->
            FocusLog.e(
                appContext,
                FocusEventId.POLICY_STATE_COMPUTED,
                "Failed to compute live schedule target warning",
                throwable
            )
        }.getOrNull()
        val expected = evaluator.evaluate(
            mode = desiredMode,
            emergencyApps = getEmergencyApps(),
            protectionSnapshot = external.protectionSnapshot,
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
        val restrictions = expected.restrictions.associateWith { facade.hasUserRestriction(it) }
        val scheduleReason = store.getScheduleLockReason()
        val scheduleComputed = store.isScheduleLockComputed()
        val scheduleActive = store.isScheduleLockEnforced()
        val scheduleStrictComputed = store.isScheduleStrictComputed()
        val scheduleStrictActive = store.isScheduleStrictEnforced()
        val scheduleBlockedPackages = store.getScheduleBlockedPackages()
        val compliance = external.usageAccessComplianceState
        val accessibilityState = AccessibilityStatusMonitor.refreshNow(appContext, "diagnostics_snapshot")
        val accessibilityRunning = AccessibilityStatusMonitor.serviceRunning(accessibilityState, nowMs)
        return DiagnosticsSnapshot(
            deviceOwner = isDeviceOwner(),
            desiredMode = desiredMode,
            manualMode = store.getManualMode(),
            usageAccessGranted = compliance.usageAccessGranted,
            accessibilityServiceEnabled = accessibilityState.enabled,
            accessibilityServiceRunning = accessibilityRunning,
            accessibilityDegradedReason = AccessibilityStatusMonitor.degradedReason(accessibilityState, nowMs),
            usageAccessRecoveryLockdownActive = compliance.lockdownActive,
            usageAccessRecoveryAllowlist = compliance.recoveryAllowlist,
            usageAccessRecoveryReason = compliance.reason,
            lastUsageAccessCheckAtMs = UsageAccessMonitor.currentState.value.lastCheckAtMs,
            lastAccessibilityStatusCheckAtMs = accessibilityState.lastCheckAtMs,
            lastAccessibilityServiceConnectAtMs = accessibilityState.lastConnectedAtMs.takeIf { it > 0L },
            lastAccessibilityHeartbeatAtMs = accessibilityState.lastHeartbeatAtMs.takeIf { it > 0L },
            scheduleLockComputed = scheduleComputed,
            scheduleLockActive = scheduleActive,
            scheduleStrictComputed = scheduleStrictComputed,
            scheduleStrictActive = scheduleStrictActive,
            scheduleBlockedGroups = store.getScheduleBlockedGroups(),
            scheduleBlockedPackages = scheduleBlockedPackages,
            scheduleLockReason = scheduleReason,
            scheduleTargetWarning = liveScheduleTargetWarning,
            scheduleNextTransitionAtMs = store.getScheduleNextTransitionAtMs(),
            budgetBlockedPackages = store.getBudgetBlockedPackages(),
            budgetBlockedGroupIds = store.getBudgetBlockedGroupIds(),
            budgetReason = store.getBudgetReason(),
            budgetUsageAccessGranted = store.isBudgetUsageAccessGranted(),
            budgetNextCheckAtMs = store.getBudgetNextCheckAtMs(),
            nextPolicyWakeAtMs = store.getNextPolicyWakeAtMs(),
            nextPolicyWakeReason = store.getNextPolicyWakeReason(),
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
            hiddenSuspendPrototypeEnabled = store.isHiddenSuspendPrototypeEnabled(),
            packageSuspendBackend = store.getLastPackageSuspendBackend(),
            packageSuspendPrototypeError = store.getLastPackageSuspendPrototypeError(),
            lastSuspendedPackages = store.getLastSuspendedPackages(),
            restrictions = restrictions,
            vpnActive = isVpnActive(),
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
            allowlistReasons = expected.allowlistReasons,
            alwaysAllowedApps = external.policyControls.alwaysAllowedApps,
            alwaysBlockedApps = external.policyControls.alwaysBlockedApps,
            uninstallProtectedApps = external.policyControls.uninstallProtectedApps,
            globalControls = external.policyControls.globalControls,
            primaryReasonByPackage = expected.primaryReasonByPackage,
            packageDiagnostics = buildPackageDiagnostics(
                nowMs = nowMs,
                expected = expected,
                external = external,
                scheduleBlockedPackages = scheduleBlockedPackages,
                protectionSnapshot = external.protectionSnapshot
            )
        )
    }

    fun dashboardSnapshot(nowMs: Long = clock.nowMs()): DashboardSnapshot {
        val usageAccessState = UsageAccessMonitor.currentState.value.let { state ->
            if (state.lastCheckAtMs > 0L) {
                state
            } else {
                UsageAccessMonitor.refreshNow(
                    context = appContext,
                    reason = "dashboard_snapshot",
                    requestPolicyRefreshIfChanged = false
                )
            }
        }
        val compliance = currentUsageAccessComplianceState(usageAccessState.usageAccessGranted)
        val accessibilityState = AccessibilityStatusMonitor.currentState.value.let { state ->
            if (state.lastCheckAtMs > 0L) {
                state
            } else {
                AccessibilityStatusMonitor.refreshNow(appContext, "dashboard_snapshot")
            }
        }
        val scheduleBlockedGroupsCount = store.getScheduleBlockedGroups().size
        return DashboardSnapshot(
            deviceOwner = isDeviceOwner(),
            usageAccessGranted = compliance.usageAccessGranted,
            accessibilityServiceEnabled = accessibilityState.enabled,
            accessibilityServiceRunning = AccessibilityStatusMonitor.serviceRunning(accessibilityState, nowMs),
            accessibilityDegradedReason = AccessibilityStatusMonitor.degradedReason(accessibilityState, nowMs),
            usageAccessRecoveryLockdownActive = compliance.lockdownActive,
            usageAccessRecoveryReason = compliance.reason,
            scheduleBlockedGroupsCount = scheduleBlockedGroupsCount,
            scheduleStrictActive = store.isScheduleStrictEnforced(),
            totalBlockedApps = (store.getBudgetBlockedPackages() + store.getLastSuspendedPackages()).size,
            lastAppliedAtMs = store.getLastAppliedAtMs(),
            lastError = store.getLastError(),
            nextPolicyWakeAtMs = store.getNextPolicyWakeAtMs(),
            nextPolicyWakeReason = store.getNextPolicyWakeReason(),
            vpnActive = isVpnActive()
        )
    }

    fun uiSnapshot(): PolicyUiSnapshot {
        return PolicyUiSnapshot(
            scheduleBlockedGroups = store.getScheduleBlockedGroups(),
            budgetBlockedPackages = store.getBudgetBlockedPackages(),
            budgetBlockedGroupIds = store.getBudgetBlockedGroupIds(),
            lastSuspendedPackages = store.getLastSuspendedPackages(),
            primaryReasonByPackage = store.getPrimaryReasonByPackage(),
            scheduleLockReason = store.getScheduleLockReason(),
            budgetReason = store.getBudgetReason(),
            currentLockReason = store.getCurrentLockReason()
        )
    }

    fun onNewPackageInstalledDuringStrictSchedule(packageName: String): Boolean {
        synchronized(APPLY_LOCK) {
            if (!isDeviceOwner()) return false
            if (
                shouldRefreshStrictScheduleForInstall(
                    scheduleStrictComputed = store.isScheduleStrictComputed(),
                    scheduleNextTransitionAtMs = store.getScheduleNextTransitionAtMs(),
                    nowMs = clock.nowMs()
                )
            ) {
                val orchestrated = orchestrateCurrentPolicy()
                persistComputedState(orchestrated)
                if (!orchestrated.scheduleState.strictActive) return false
            } else if (!store.isScheduleStrictComputed()) {
                return false
            }
            val usageAccessComplianceState = currentUsageAccessComplianceState()
            val controls = loadPolicyControls()
            val protectionSnapshot = buildAppProtectionSnapshot(controls, usageAccessComplianceState)
            if (!protectionSnapshot.isEligibleForAllAppsExpansion(packageName)) return false
            val suspendable = resolver.filterSuspendable(
                packages = setOf(packageName),
                allowlist = protectionSnapshot.fullyExemptPackages
            )
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
            nowMs = clock.nowMs()
        )
        if (!isDeviceOwner()) {
            return failureResult(
                message = "Not device owner",
                stateMode = rollbackMode,
                emergencyApps = emergencyApps
            )
        }
        val trackedPackages = sanitizeTrackedPackages()
        val state = evaluator.evaluate(
            mode = normalizedTargetMode,
            emergencyApps = emergencyApps,
            protectionSnapshot = external.protectionSnapshot,
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
        val success = applyResult.errors.isEmpty() && verification.passed
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
            protectionSnapshot = external.protectionSnapshot,
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
            protectionSnapshot = external.protectionSnapshot,
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
        val retainedSuspended = retainInstalledTrackedPackages(
            trackedPackages = store.getLastSuspendedPackages(),
            controllerPackageName = appContext.packageName,
            isInstalled = installedCheck
        )
        val recoveredSuspended = recoverTrackedSuspendedPackages(
            trackedPackages = retainedSuspended,
            candidatePackages = resolver.getInstalledTargetablePackages(),
            canVerifyPackageSuspension = facade.canVerifyPackageSuspension(),
            isPackageSuspended = facade::isPackageSuspended
        )
        val newlyRecoveredSuspended = recoveredSuspended - retainedSuspended
        if (newlyRecoveredSuspended.isNotEmpty()) {
            store.addLastSuspendedPackages(newlyRecoveredSuspended)
            FocusLog.w(
                appContext,
                FocusEventId.SUSPEND_TARGET,
                "Recovered ${newlyRecoveredSuspended.size} suspended packages missing from policy tracking"
            )
        }
        return TrackedPackages(
            previouslySuspended = recoveredSuspended,
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
        FocusLog.d(FocusEventId.POLICY_STATE_COMPUTED, "â•‘ effectiveMode=${external.effectiveMode} schedLock=${orchestrated.scheduleState.active} strict=${orchestrated.scheduleState.strictActive}")
        FocusLog.d(FocusEventId.POLICY_STATE_COMPUTED, "â•‘ suspendTargets=${external.budgetBlockedPackages.size} schedBlockedGroups=${orchestrated.scheduleState.blockedGroupIds.size} schedBlockedApps=${orchestrated.scheduleState.blockedAppPackages.size} budgetBlocked=${external.budgetBlockedPackages.size}")
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
            store.setScheduleEnforced(orchestrated.scheduleState.active)
            store.setScheduleStrictEnforced(orchestrated.scheduleState.strictActive)
        } else {
            store.setScheduleEnforced(previousScheduleEnforced)
            store.setScheduleStrictEnforced(previousStrictEnforced)
            FocusLog.w(FocusEventId.POLICY_STATE_COMPUTED, "â•‘ apply FAILED â€” rolled back schedule state")
        }
        syncNextAlarm(nowMs = orchestrated.nowMs)
        val totalMs = (System.nanoTime() - overallStartNs) / 1_000_000.0
        FocusLog.d(FocusEventId.POLICY_STATE_COMPUTED, "â•šâ•â• requestApplyNow() DONE in %.1fms success=${result.success} â•â•".format(totalMs))
        return result
    }

    private fun syncNextAlarm(nowMs: Long = clock.nowMs()) {
        val nextWakeAtMs = store.getNextPolicyWakeAtMs()
        val nextWakeReason = store.getNextPolicyWakeReason()
        if (nextWakeReason == WAKE_REASON_RELIABILITY_TICK) {
            alarmScheduler.cancelNextTransition()
            alarmScheduler.scheduleReliabilityTick(
                nextWakeAtMs ?: (nowMs + RELIABILITY_TICK_INTERVAL_MS)
            )
            return
        }
        alarmScheduler.cancelReliabilityTick()
        nextWakeAtMs?.let(alarmScheduler::scheduleNextTransition) ?: alarmScheduler.cancelNextTransition()
    }

    private fun orchestrateCurrentPolicy(now: ZonedDateTime = clock.now()): OrchestratedState {
        val policyControls = loadPolicyControls()
        val installedTargetablePackages = resolver.getInstalledTargetablePackages()
        val hardProtectedPackages = resolver.getHardProtectedPackages()
        val loaded = runBlocking {
            withContext(Dispatchers.IO) {
                val budgetDao = db.budgetDao()
                val scheduleDao = db.scheduleDao()
                val scheduleSnapshot = scheduleDao.getEnabledScheduleSnapshot()
                val scheduleTargets = splitScheduleTargetsByBlock(scheduleSnapshot.blockGroups)
                LoadedPolicyRows(
                    groupLimits = budgetDao.getEnabledGroupLimits(),
                    groupEmergencyConfigs = budgetDao.getAllGroupEmergencyConfigs().associateBy { it.groupId },
                    appPolicies = budgetDao.getEnabledAppPolicies(),
                    mappings = budgetDao.getAllMappings(),
                    scheduleBlocks = scheduleSnapshot.blocks,
                    scheduleBlockTargets = scheduleTargets.allTargetsByBlockId,
                    scheduleGroupTargetsByBlock = scheduleTargets.groupTargetsByBlockId,
                    scheduleAppTargetsByBlock = scheduleTargets.appTargetsByBlockId,
                    emergencyStates = budgetClient.getActiveEmergencyStates(now)
                )
            }
        }
        val scheduleDecision = ScheduleEvaluator.evaluate(now, loaded.scheduleBlocks, loaded.scheduleBlockTargets)
        val activeBlockIds = scheduleDecision.activeBlockIds
        val activeScheduleTargets = resolveActiveScheduleTargets(
            activeBlockIds = activeBlockIds,
            scheduleBlocks = loaded.scheduleBlocks,
            scheduleGroupTargetsByBlock = loaded.scheduleGroupTargetsByBlock,
            scheduleAppTargetsByBlock = loaded.scheduleAppTargetsByBlock,
            validGroupIds = loaded.groupLimits.mapTo(linkedSetOf(), GroupLimit::groupId),
            isPackageInstalled = resolver::isPackageInstalled
        )
        val usageSnapshot = runBlocking { budgetClient.readUsageSnapshot(now) }
        val groupMembers = loaded.mappings.groupBy(AppGroupMap::groupId) { it.packageName }
        val groupInputs = loaded.groupLimits.map { limit ->
            GroupPolicyInput(
                groupId = limit.groupId,
                priorityIndex = limit.priorityIndex,
                strictEnabled = limit.strictEnabled,
                targetMode = resolveScheduleTargetMode(limit),
                dailyLimitMs = limit.dailyLimitMs,
                hourlyLimitMs = limit.hourlyLimitMs,
                opensPerDay = limit.opensPerDay,
                members = groupMembers[limit.groupId].orEmpty().toSet(),
                emergencyConfig = loaded.groupEmergencyConfigs[limit.groupId].toEmergencyConfig(),
                scheduleBlocked = activeScheduleTargets.scheduledGroupIds.contains(limit.groupId)
            )
        }
        val baseScheduleState = resolveLiveScheduleState(
            scheduleDecision = scheduleDecision,
            scheduleBlocks = loaded.scheduleBlocks,
            targetedActiveBlockIds = activeScheduleTargets.targetedActiveBlockIds,
            scheduledGroupIds = activeScheduleTargets.scheduledGroupIds,
            scheduledAppPackages = activeScheduleTargets.scheduledAppPackages
        )
        val strictActive = resolveStrictScheduleActive(
            scheduleStrictActive = baseScheduleState.strictActive,
            groupInputs = groupInputs
        )
        val scheduleState = baseScheduleState.copy(strictActive = strictActive)
        val activeAllAppsScheduleGroupIds = groupInputs
            .asSequence()
            .filter { it.scheduleBlocked && it.targetMode == GroupTargetMode.ALL_APPS }
            .map { it.groupId }
            .toSet()
        val strictInstallBlocked = if (strictActive && activeAllAppsScheduleGroupIds.isEmpty()) {
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
                scheduleBlocked = activeScheduleTargets.scheduledAppPackages.contains(policy.packageName)
            )
        }
        val nowMs = now.toInstant().toEpochMilli()
        val usageAccessComplianceState = currentUsageAccessComplianceState(usageSnapshot.usageAccessGranted)
        val protectionSnapshot = buildAppProtectionSnapshot(policyControls, usageAccessComplianceState)
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
            fullyExemptPackages = protectionSnapshot.fullyExemptPackages,
            allAppsExcludedPackages = protectionSnapshot.allAppsExcludedPackages,
            installedTargetablePackages = installedTargetablePackages,
            hardProtectedPackages = hardProtectedPackages
        )
        val budgetNextCheckAtMs = computeClosestBudgetCheckAtMs(
            nowMs = nowMs,
            baseNextCheckAtMs = usageSnapshot.nextCheckAtMs,
            groupPolicies = groupInputs,
            appPolicies = appInputs,
            usageInputs = usageSnapshot.usageInputs,
            fullyExemptPackages = protectionSnapshot.fullyExemptPackages,
            emergencyStates = emergencyStates
        )
        val emergencyUnlockExpiresAtMs = emergencyStates
            .mapNotNull { it.activeUntilEpochMs?.takeIf { untilMs -> untilMs > nowMs } }
            .minOrNull()
        val nextPolicyWake = planNextPolicyWake(
            nowMs = nowMs,
            scheduleNextTransitionAtMs = scheduleDecision.nextTransitionAt?.toInstant()?.toEpochMilli(),
            budgetNextCheckAtMs = budgetNextCheckAtMs,
            emergencyUnlockExpiresAtMs = emergencyUnlockExpiresAtMs,
            touchGrassBreakUntilMs = null,
            reliabilityFallbackAtMs = nowMs + RELIABILITY_TICK_INTERVAL_MS
        )
        return OrchestratedState(
            nowMs = nowMs,
            usageAccessGranted = usageSnapshot.usageAccessGranted,
            usageAccessComplianceState = usageAccessComplianceState,
            nextCheckAtMs = budgetNextCheckAtMs,
            nextPolicyWakeAtMs = nextPolicyWake.atMs,
            nextPolicyWakeReason = nextPolicyWake.reason,
            scheduleDecision = scheduleDecision,
            scheduledGroupIds = activeScheduleTargets.scheduledGroupIds,
            scheduledAppPackages = activeScheduleTargets.scheduledAppPackages,
            scheduleState = scheduleState,
            scheduleTargetWarning = activeScheduleTargets.warning,
            policyControls = policyControls,
            protectionSnapshot = protectionSnapshot,
            installedTargetablePackages = installedTargetablePackages,
            hardProtectedPackages = hardProtectedPackages,
            evaluation = evaluation
        )
    }

    private fun computeLiveScheduleTargetWarning(now: ZonedDateTime): String? {
        return runBlocking {
            withContext(Dispatchers.IO) {
                val budgetDao = db.budgetDao()
                val scheduleDao = db.scheduleDao()
                val scheduleSnapshot = scheduleDao.getEnabledScheduleSnapshot()
                val scheduleTargets = splitScheduleTargetsByBlock(scheduleSnapshot.blockGroups)
                val scheduleDecision = ScheduleEvaluator.evaluate(
                    now = now,
                    blocks = scheduleSnapshot.blocks,
                    blockGroups = scheduleTargets.allTargetsByBlockId
                )
                resolveActiveScheduleTargets(
                    activeBlockIds = scheduleDecision.activeBlockIds,
                    scheduleBlocks = scheduleSnapshot.blocks,
                    scheduleGroupTargetsByBlock = scheduleTargets.groupTargetsByBlockId,
                    scheduleAppTargetsByBlock = scheduleTargets.appTargetsByBlockId,
                    validGroupIds = budgetDao.getEnabledGroupLimits().mapTo(linkedSetOf(), GroupLimit::groupId),
                    isPackageInstalled = resolver::isPackageInstalled
                ).warning
            }
        }
    }

    private fun persistComputedState(orchestrated: OrchestratedState) {
        val suspendPlan = computeSuspendPlan(
            evaluation = orchestrated.evaluation,
            alwaysBlockedPackages = orchestrated.policyControls.alwaysBlockedInstalledPackages,
            fullyExemptPackages = orchestrated.protectionSnapshot.fullyExemptPackages
        )
        store.setComputedPolicyState(
            scheduleLockComputed = orchestrated.scheduleState.active,
            scheduleStrictComputed = orchestrated.scheduleState.strictActive,
            scheduleBlockedGroups = orchestrated.scheduleState.blockedGroupIds,
            scheduleBlockedPackages = suspendPlan.scheduleBlockedPackages,
            scheduleLockReason = orchestrated.scheduleState.reason,
            scheduleNextTransitionAtMs = orchestrated.scheduleDecision.nextTransitionAt?.toInstant()?.toEpochMilli(),
            budgetBlockedPackages = suspendPlan.suspendTargets,
            budgetBlockedGroupIds = orchestrated.evaluation.effectiveBlockedGroupIds,
            budgetReason = orchestrated.usageAccessComplianceState.reason
                ?: orchestrated.evaluation.usageReasonSummary
                ?: if (!orchestrated.usageAccessGranted) "Usage access not granted" else null,
            budgetUsageAccessGranted = orchestrated.usageAccessGranted,
            budgetNextCheckAtMs = orchestrated.nextCheckAtMs,
            nextPolicyWakeAtMs = orchestrated.nextPolicyWakeAtMs,
            nextPolicyWakeReason = orchestrated.nextPolicyWakeReason,
            primaryReasonByPackage = suspendPlan.primaryReasons,
            blockReasonsByPackage = suspendPlan.blockReasonsByPackage,
            clearStrictInstallSuspendedPackages = !orchestrated.scheduleState.strictActive ||
                orchestrated.evaluation.activeAllAppsGroupIds.isNotEmpty()
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
        orchestrated.scheduleTargetWarning?.let { warning ->
            FocusLog.w(
                appContext,
                FocusEventId.POLICY_STATE_COMPUTED,
                warning
            )
        }
        FocusLog.i(
            appContext,
            FocusEventId.POLICY_STATE_COMPUTED,
            "scheduleLock=${orchestrated.scheduleState.active} strict=${orchestrated.scheduleState.strictActive} scheduledGroups=${orchestrated.scheduleState.blockedGroupIds.size} scheduledApps=${orchestrated.scheduleState.blockedAppPackages.size} activeAllAppsGroups=${orchestrated.evaluation.activeAllAppsGroupIds.size} targetable=${orchestrated.installedTargetablePackages.size} protected=${orchestrated.hardProtectedPackages.size} allowlisted=${orchestrated.policyControls.alwaysAllowedApps.size} hidden=${orchestrated.policyControls.hiddenApps.size} blockedPackages=${orchestrated.evaluation.effectiveBlockedPackages.size} blockedGroups=${orchestrated.evaluation.effectiveBlockedGroupIds.size} usageAccess=${orchestrated.usageAccessGranted} nextWake=${orchestrated.nextPolicyWakeReason}@${orchestrated.nextPolicyWakeAtMs}"
        )
        if (orchestrated.evaluation.activeAllAppsGroupIds.isNotEmpty()) {
            FocusLog.i(
                appContext,
                FocusEventId.GROUP_EVAL,
                "Active all-apps groups=${orchestrated.evaluation.activeAllAppsGroupIds.joinToString(",")}"
            )
        }
    }

    private fun computeSuspendPlan(
        evaluation: EffectivePolicyEvaluation,
        alwaysBlockedPackages: Set<String>,
        fullyExemptPackages: Set<String>
    ): SuspendPlan {
        val budgetBlockedSuspendable = resolver.filterSuspendable(
            packages = evaluation.effectiveBlockedPackages,
            allowlist = fullyExemptPackages
        )
        val scheduleBlockedSuspendable = resolver.filterSuspendable(
            packages = evaluation.scheduledBlockedPackages,
            allowlist = fullyExemptPackages
        )
        val alwaysBlockedSuspendable = resolver.filterSuspendable(
            packages = alwaysBlockedPackages,
            allowlist = fullyExemptPackages
        )
        val strictInstallSuspendable = resolver.filterSuspendable(
            packages = evaluation.strictInstallBlockedPackages,
            allowlist = fullyExemptPackages
        )
        val suspendableSet = linkedSetOf<String>().apply {
            addAll(budgetBlockedSuspendable)
            addAll(scheduleBlockedSuspendable)
            addAll(alwaysBlockedSuspendable)
            addAll(strictInstallSuspendable)
        }
        val normalizedBlockReasons = evaluation.blockReasonsByPackage
            .asSequence()
            .mapNotNull { (rawPackage, reasons) ->
                val normalizedPackage = rawPackage.trim()
                if (normalizedPackage.isBlank() || !suspendableSet.contains(normalizedPackage)) return@mapNotNull null
                val normalizedReasons = reasons
                    .asSequence()
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
        strictInstallSuspendable.forEach { pkg ->
            fullReasons.getOrPut(pkg) { linkedSetOf() }.add(EffectiveBlockReason.STRICT_INSTALL.name)
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
        val multiReasonPackages = immutableReasonMap.filterValues { it.size > 1 }
        if (multiReasonPackages.isNotEmpty()) {
            FocusLog.d(
                FocusEventId.SUSPEND_TARGET,
                "packagesWithMultipleReasons=${multiReasonPackages.size}: ${multiReasonPackages.keys.joinToString(",")}"
            )
        }
        return SuspendPlan(
            scheduleBlockedPackages = scheduleBlockedSuspendable,
            suspendTargets = immutableReasonMap.keys.toSet(),
            primaryReasons = BlockReasonUtils.derivePrimaryByPackage(immutableReasonMap),
            blockReasonsByPackage = immutableReasonMap
        )
    }

    private fun splitScheduleTargetsByBlock(
        blockGroups: List<ScheduleBlockGroup>
    ): ScheduleTargetsByBlock {
        val allTargets = linkedMapOf<Long, MutableSet<String>>()
        val groupTargets = linkedMapOf<Long, MutableSet<String>>()
        val appTargets = linkedMapOf<Long, MutableSet<String>>()
        blockGroups.forEach { row ->
            val targetId = row.groupId.trim()
            if (targetId.isBlank()) return@forEach
            allTargets.getOrPut(row.blockId) { linkedSetOf() }.add(targetId)
            val appPackage = decodeSingleAppScheduleTarget(targetId)
            if (appPackage == null) {
                groupTargets.getOrPut(row.blockId) { linkedSetOf() }.add(targetId)
            } else {
                appTargets.getOrPut(row.blockId) { linkedSetOf() }.add(appPackage)
            }
        }
        return ScheduleTargetsByBlock(
            allTargetsByBlockId = allTargets.mapValues { it.value.toSet() },
            groupTargetsByBlockId = groupTargets.mapValues { it.value.toSet() },
            appTargetsByBlockId = appTargets.mapValues { it.value.toSet() }
        )
    }

    private fun computeExternalState(nowMs: Long = clock.nowMs()): ExternalState {
        val policyControls = loadPolicyControls()
        val usageAccessComplianceState = currentUsageAccessComplianceState()
        val protectionSnapshot = buildAppProtectionSnapshot(policyControls, usageAccessComplianceState)
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
            allowlist = protectionSnapshot.fullyExemptPackages
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
            strictActive -> scheduleReason ?: "Strict scheduled block active"
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
            protectionSnapshot = protectionSnapshot,
            primaryReasonByPackage = primaryReason,
            blockReasonsByPackage = filteredReasonSets,
            usageAccessComplianceState = usageAccessComplianceState
        )
    }

    private fun buildPackageDiagnostics(
        nowMs: Long,
        expected: PolicyState,
        external: ExternalState,
        scheduleBlockedPackages: Set<String>,
        protectionSnapshot: AppProtectionSnapshot
    ): List<PackageDiagnostics> {
        val strictInstallStored = store.getStrictInstallSuspendedPackages()
        val candidatePackages = linkedSetOf<String>().apply {
            addAll(expected.suspendTargets)
            addAll(expected.blockReasonsByPackage.keys)
            addAll(external.blockReasonsByPackage.keys)
            addAll(scheduleBlockedPackages)
            addAll(external.strictInstallBlockedPackages)
            addAll(external.policyControls.alwaysBlockedInstalledPackages)
            addAll(protectionSnapshot.allowlistedPackages)
            addAll(protectionSnapshot.hiddenPackages)
            addAll(protectionSnapshot.runtimeExemptPackages)
            addAll(protectionSnapshot.hardProtectedPackages)
        }
        return candidatePackages
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
            .map { packageName ->
                val activeReasons = linkedSetOf<String>().apply {
                    addAll(external.blockReasonsByPackage[packageName].orEmpty())
                    addAll(expected.blockReasonsByPackage[packageName].orEmpty())
                    if (packageName in external.strictInstallBlockedPackages) {
                        add(EffectiveBlockReason.STRICT_INSTALL.name)
                    }
                    if (packageName in external.policyControls.alwaysBlockedInstalledPackages) {
                        add(EffectiveBlockReason.ALWAYS_BLOCKED.name)
                    }
                    if (
                        packageName in scheduleBlockedPackages &&
                        none { it.contains(EffectiveBlockReason.SCHEDULED_BLOCK.name, ignoreCase = true) }
                    ) {
                        add(EffectiveBlockReason.SCHEDULED_BLOCK.name)
                    }
                    if (
                        packageName in expected.suspendTargets &&
                        expected.primaryReasonByPackage[packageName] == EffectiveBlockReason.USAGE_ACCESS_RECOVERY_LOCKDOWN.name
                    ) {
                        add(EffectiveBlockReason.USAGE_ACCESS_RECOVERY_LOCKDOWN.name)
                    }
                }.toSet()
                val disposition = if (packageName in expected.suspendTargets) {
                    PackageDiagnosticsDisposition.SUSPEND_TARGET
                } else if (protectionSnapshot.isHidden(packageName)) {
                    PackageDiagnosticsDisposition.HIDDEN
                } else if (protectionSnapshot.isAllowlisted(packageName)) {
                    PackageDiagnosticsDisposition.ALLOWLIST_EXCLUDED
                } else if (protectionSnapshot.isRuntimeExempt(packageName)) {
                    PackageDiagnosticsDisposition.RUNTIME_EXEMPT
                } else if (protectionSnapshot.isCoreProtected(packageName)) {
                    PackageDiagnosticsDisposition.PROTECTED
                } else if (resolver.isPackageInstalled(packageName)) {
                    PackageDiagnosticsDisposition.ELIGIBLE_NOT_ACTIVE
                } else {
                    PackageDiagnosticsDisposition.NOT_INSTALLED
                }
                val primaryReason = expected.primaryReasonByPackage[packageName]
                    ?: external.primaryReasonByPackage[packageName]
                    ?: BlockReasonUtils.derivePrimaryReason(activeReasons)
                        .takeUnless(String::isBlank)
                PackageDiagnostics(
                    packageName = packageName,
                    activeReasons = activeReasons,
                    primaryReason = primaryReason,
                    disposition = disposition,
                    allowlistReason = protectionSnapshot.allAppsExclusionReason(packageName),
                    protectionReason = protectionSnapshot.manualPolicyIneligibilityReason(packageName),
                    hiddenLocked = protectionSnapshot.isHiddenLocked(packageName),
                    fromStrictInstallSuspended = packageName in strictInstallStored,
                    nextPotentialClearEvent = describeNextPotentialClearEvent(
                        nowMs = nowMs,
                        disposition = disposition,
                        primaryReason = primaryReason,
                        activeReasons = activeReasons,
                        scheduleNextTransitionAtMs = store.getScheduleNextTransitionAtMs(),
                        budgetNextCheckAtMs = store.getBudgetNextCheckAtMs()
                    )
                )
            }
            .toList()
    }

    private fun describeNextPotentialClearEvent(
        nowMs: Long,
        disposition: PackageDiagnosticsDisposition,
        primaryReason: String?,
        activeReasons: Set<String>,
        scheduleNextTransitionAtMs: Long?,
        budgetNextCheckAtMs: Long?
    ): String {
        when (disposition) {
            PackageDiagnosticsDisposition.ALLOWLIST_EXCLUDED ->
                return "Excluded from all-apps expansion until removed from Allowlist"
            PackageDiagnosticsDisposition.HIDDEN ->
                return "Hidden apps are excluded from normal policy paths until removed from Hidden Apps"
            PackageDiagnosticsDisposition.RUNTIME_EXEMPT ->
                return "Runtime exemption remains until the recovery/protected condition clears"
            PackageDiagnosticsDisposition.PROTECTED ->
                return "Core protected package rules keep this app outside normal targeting"
            PackageDiagnosticsDisposition.ELIGIBLE_NOT_ACTIVE ->
                return "Currently not targeted; next policy recompute may reactivate it"
            PackageDiagnosticsDisposition.NOT_INSTALLED ->
                return "Package is not installed; next install and policy recompute decide state"
            PackageDiagnosticsDisposition.SUSPEND_TARGET -> Unit
        }
        val normalizedPrimary = primaryReason.orEmpty().uppercase()
        val normalizedReasons = activeReasons.map { it.uppercase() }
        return when {
            normalizedPrimary == EffectiveBlockReason.ALWAYS_BLOCKED.name ||
                normalizedReasons.any { it.contains(EffectiveBlockReason.ALWAYS_BLOCKED.name) } ->
                "Config change: remove from always-blocked apps"
            normalizedPrimary == EffectiveBlockReason.USAGE_ACCESS_RECOVERY_LOCKDOWN.name ||
                normalizedReasons.any { it.contains(EffectiveBlockReason.USAGE_ACCESS_RECOVERY_LOCKDOWN.name) } ->
                "Restore Usage Access; receiver or poll alarm will recompute"
            normalizedPrimary.contains("SCHEDULED_BLOCK") ||
                normalizedPrimary == EffectiveBlockReason.STRICT_INSTALL.name ||
                normalizedReasons.any {
                    it.contains(EffectiveBlockReason.SCHEDULED_BLOCK.name) ||
                        it.contains(EffectiveBlockReason.STRICT_INSTALL.name)
                } ->
                scheduleNextTransitionAtMs
                    ?.takeIf { it > nowMs }
                    ?.let { "Schedule transition alarm at $it" }
                    ?: "Next schedule recompute or policy reapply"
            normalizedPrimary.contains("HOURLY_CAP") ||
                normalizedPrimary.contains("DAILY_CAP") ||
                normalizedPrimary.contains("OPENS_CAP") ||
                normalizedPrimary == "BUDGET" ||
                normalizedReasons.any {
                    it.contains(EffectiveBlockReason.HOURLY_CAP.name) ||
                        it.contains(EffectiveBlockReason.DAILY_CAP.name) ||
                        it.contains(EffectiveBlockReason.OPENS_CAP.name) ||
                        it == "BUDGET"
                } ->
                budgetNextCheckAtMs
                    ?.takeIf { it > nowMs }
                    ?.let { "Budget boundary recompute at $it" }
                    ?: "Next budget boundary or policy reapply"
            else -> "Next policy recompute"
        }
    }

    private data class ExternalState(
        val effectiveMode: ModeState,
        val budgetBlockedPackages: Set<String>,
        val strictInstallBlockedPackages: Set<String>,
        val touchGrassBreakActive: Boolean,
        val lockReason: String?,
        val policyControls: PolicyControls,
        val protectionSnapshot: AppProtectionSnapshot,
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
        val hiddenApps: List<HiddenApp>,
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
        val scheduleBlockTargets: Map<Long, Set<String>>,
        val scheduleGroupTargetsByBlock: Map<Long, Set<String>>,
        val scheduleAppTargetsByBlock: Map<Long, Set<String>>,
        val emergencyStates: List<EmergencyState>
    )

    private data class ScheduleTargetsByBlock(
        val allTargetsByBlockId: Map<Long, Set<String>>,
        val groupTargetsByBlockId: Map<Long, Set<String>>,
        val appTargetsByBlockId: Map<Long, Set<String>>
    )

    internal data class ActiveScheduleTargets(
        val targetedActiveBlockIds: Set<Long>,
        val scheduledGroupIds: Set<String>,
        val scheduledAppPackages: Set<String>,
        val warning: String?
    )

    private data class OrchestratedState(
        val nowMs: Long,
        val usageAccessGranted: Boolean,
        val usageAccessComplianceState: UsageAccessComplianceState,
        val nextCheckAtMs: Long?,
        val nextPolicyWakeAtMs: Long?,
        val nextPolicyWakeReason: String?,
        val scheduleDecision: ScheduleDecision,
        val scheduledGroupIds: Set<String>,
        val scheduledAppPackages: Set<String>,
        val scheduleState: LiveScheduleState,
        val scheduleTargetWarning: String?,
        val policyControls: PolicyControls,
        val protectionSnapshot: AppProtectionSnapshot,
        val installedTargetablePackages: Set<String>,
        val hardProtectedPackages: Set<String>,
        val evaluation: EffectivePolicyEvaluation
    )

    internal data class PolicyWakePlan(
        val atMs: Long?,
        val reason: String?
    )

    private data class SuspendPlan(
        val scheduleBlockedPackages: Set<String>,
        val suspendTargets: Set<String>,
        val primaryReasons: Map<String, String>,
        val blockReasonsByPackage: Map<String, Set<String>>
    )

    internal data class LiveScheduleState(
        val active: Boolean,
        val strictActive: Boolean,
        val blockedGroupIds: Set<String>,
        val blockedAppPackages: Set<String>,
        val reason: String
    )

    private fun loadPolicyControls(): PolicyControls = runBlocking { loadPolicyControlsAsync() }

    private suspend fun loadPolicyControlsAsync(): PolicyControls {
        policyControlsCache?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            val dao = db.budgetDao()
            ensureDefaultHiddenAppsSeededLocked(dao)
            val global = dao.getGlobalControls() ?: GlobalControls()
            val alwaysAllowed = normalizeConfiguredPackages(dao.getAlwaysAllowedPackages())
            val hiddenApps = dao.getHiddenApps()
            val alwaysBlocked = normalizeConfiguredPackages(dao.getAlwaysBlockedPackages())
                .toCollection(linkedSetOf())
            val uninstallProtected = normalizeConfiguredPackages(
                packages = dao.getUninstallProtectedPackages(),
                implicitPackages = setOf(appContext.packageName)
            )
            val installedAlwaysBlocked = alwaysBlocked.filterTo(linkedSetOf()) { resolver.isPackageInstalled(it) }
            PolicyControls(
                globalControls = global,
                alwaysAllowedApps = alwaysAllowed,
                hiddenApps = hiddenApps,
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

    private suspend fun ensureDefaultHiddenAppsSeededLocked() {
        ensureDefaultHiddenAppsSeededLocked(db.budgetDao())
    }

    private suspend fun ensureDefaultHiddenAppsSeededLocked(dao: com.ankit.destination.data.BudgetDao) {
        val resolver = DefaultHiddenAppResolver(appContext)
        val lockedDefaults = resolver.resolveLockedHiddenPackages()
        dao.getHiddenApps()
            .asSequence()
            .filter { it.locked && it.packageName !in lockedDefaults }
            .forEach { dao.deleteHiddenApp(it.packageName) }
        lockedDefaults.forEach { packageName ->
            dao.upsertHiddenApp(HiddenApp(packageName = packageName, locked = true))
        }
    }

    private fun buildAppProtectionSnapshot(
        policyControls: PolicyControls,
        usageAccessComplianceState: UsageAccessComplianceState
    ): AppProtectionSnapshot {
        val runtimeAllowlist = if (usageAccessComplianceState.lockdownActive) {
            AllowlistResolution(
                packages = usageAccessComplianceState.recoveryAllowlist,
                reasons = usageAccessComplianceState.recoveryAllowlist.associateWith { "usage access recovery" }
            )
        } else {
            resolver.resolveRuntimeAllowlist(getEmergencyApps())
        }
        val hiddenPackages = policyControls.hiddenApps
            .map { it.packageName.trim() }
            .filter(String::isNotBlank)
            .toSet()
        val lockedHiddenPackages = policyControls.hiddenApps
            .asSequence()
            .filter { it.locked }
            .map { it.packageName.trim() }
            .filter(String::isNotBlank)
            .toSet()
        return AppProtectionSnapshot(
            allowlistedPackages = policyControls.alwaysAllowedApps,
            hiddenPackages = hiddenPackages,
            lockedHiddenPackages = lockedHiddenPackages,
            runtimeExemptPackages = runtimeAllowlist.packages,
            runtimeExemptionReasons = runtimeAllowlist.reasons,
            hardProtectedPackages = resolver.getHardProtectedPackages()
        )
    }

    private fun GroupEmergencyConfig?.toEmergencyConfig(): EmergencyConfigInput {
        return EmergencyConfigInput(
            enabled = this?.enabled == true,
            unlocksPerDay = this?.unlocksPerDay ?: 0,
            minutesPerUnlock = this?.minutesPerUnlock ?: 0
        )
    }

    private fun resolveScheduleTargetMode(limit: GroupLimit): GroupTargetMode {
        return if (limit.strictEnabled) {
            GroupTargetMode.fromStorage(limit.scheduleTargetMode)
        } else {
            GroupTargetMode.SELECTED_APPS
        }
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

        internal fun recoverTrackedSuspendedPackages(
            trackedPackages: Set<String>,
            candidatePackages: Set<String>,
            canVerifyPackageSuspension: Boolean,
            isPackageSuspended: (String) -> Boolean?
        ): Set<String> {
            val normalizedTracked = trackedPackages
                .asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .toCollection(linkedSetOf())
            if (!canVerifyPackageSuspension) {
                return normalizedTracked
            }
            val candidates = linkedSetOf<String>().apply {
                addAll(normalizedTracked)
                candidatePackages
                    .asSequence()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .forEach(::add)
            }
            if (candidates.isEmpty()) {
                return normalizedTracked
            }
            val detectedSuspended = candidates.filterTo(linkedSetOf()) { packageName ->
                isPackageSuspended(packageName) == true
            }
            return linkedSetOf<String>().apply {
                addAll(normalizedTracked)
                addAll(detectedSuspended)
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

        internal fun shouldRefreshStrictScheduleForInstall(
            scheduleStrictComputed: Boolean,
            scheduleNextTransitionAtMs: Long?,
            nowMs: Long
        ): Boolean {
            if (!scheduleStrictComputed) return true
            val nextTransitionAt = scheduleNextTransitionAtMs ?: return true
            return nowMs >= nextTransitionAt
        }

        internal fun resolveActiveScheduleTargets(
            activeBlockIds: Set<Long>,
            scheduleBlocks: List<com.ankit.destination.data.ScheduleBlock>,
            scheduleGroupTargetsByBlock: Map<Long, Set<String>>,
            scheduleAppTargetsByBlock: Map<Long, Set<String>>,
            validGroupIds: Set<String>,
            isPackageInstalled: (String) -> Boolean
        ): ActiveScheduleTargets {
            val installCache = linkedMapOf<String, Boolean>()
            fun isInstalledCached(packageName: String): Boolean {
                return installCache.getOrPut(packageName) { isPackageInstalled(packageName) }
            }
            val rawTargetedActiveBlockIds = activeBlockIds.filterTo(linkedSetOf()) { blockId ->
                scheduleGroupTargetsByBlock[blockId].orEmpty().isNotEmpty() ||
                    scheduleAppTargetsByBlock[blockId].orEmpty().isNotEmpty()
            }
            val scheduledGroupIds = rawTargetedActiveBlockIds.flatMapTo(linkedSetOf()) { blockId ->
                scheduleGroupTargetsByBlock[blockId].orEmpty().filterTo(linkedSetOf()) { groupId ->
                    validGroupIds.contains(groupId)
                }
            }
            val scheduledAppPackages = rawTargetedActiveBlockIds.flatMapTo(linkedSetOf()) { blockId ->
                scheduleAppTargetsByBlock[blockId].orEmpty().filterTo(linkedSetOf()) { packageName ->
                    isInstalledCached(packageName)
                }
            }
            val targetedActiveBlockIds = rawTargetedActiveBlockIds.filterTo(linkedSetOf()) { blockId ->
                scheduleGroupTargetsByBlock[blockId].orEmpty().any(validGroupIds::contains) ||
                    scheduleAppTargetsByBlock[blockId].orEmpty().any(::isInstalledCached)
            }
            val warningBlockIds = when {
                activeBlockIds.isEmpty() -> emptySet()
                targetedActiveBlockIds.isNotEmpty() -> emptySet()
                rawTargetedActiveBlockIds.isEmpty() -> activeBlockIds
                else -> rawTargetedActiveBlockIds
            }
            val warning = if (warningBlockIds.isNotEmpty()) {
                val blockNamesById = scheduleBlocks.associateBy(com.ankit.destination.data.ScheduleBlock::id)
                val names = warningBlockIds.joinToString(", ") { blockId ->
                    blockNamesById[blockId]?.name ?: "block#$blockId"
                }
                if (rawTargetedActiveBlockIds.isEmpty()) {
                    "Schedule window active but no targets are configured: $names"
                } else {
                    "Schedule window active but no effective installed or valid targets: $names"
                }
            } else {
                null
            }
            return ActiveScheduleTargets(
                targetedActiveBlockIds = targetedActiveBlockIds,
                scheduledGroupIds = scheduledGroupIds,
                scheduledAppPackages = scheduledAppPackages,
                warning = warning
            )
        }

        internal fun resolveLiveScheduleState(
            scheduleDecision: ScheduleDecision,
            scheduleBlocks: List<com.ankit.destination.data.ScheduleBlock>,
            targetedActiveBlockIds: Set<Long>,
            scheduledGroupIds: Set<String>,
            scheduledAppPackages: Set<String>
        ): LiveScheduleState {
            val active = scheduledGroupIds.isNotEmpty() || scheduledAppPackages.isNotEmpty()
            if (!active) {
                return LiveScheduleState(
                    active = false,
                    strictActive = false,
                    blockedGroupIds = scheduledGroupIds,
                    blockedAppPackages = scheduledAppPackages,
                    reason = scheduleDecision.reason
                )
            }
            val activeBlocksById = scheduleBlocks.associateBy { it.id }
            val targetedActiveBlocks = targetedActiveBlockIds.mapNotNull(activeBlocksById::get)
            val strictActive = targetedActiveBlocks.any { it.strict }
            val names = targetedActiveBlocks.joinToString(", ") { it.name }
            val reason = when {
                scheduledGroupIds.isNotEmpty() && scheduledAppPackages.isNotEmpty() && strictActive ->
                    "Strict mixed schedule active: $names"
                scheduledGroupIds.isNotEmpty() && scheduledAppPackages.isNotEmpty() ->
                    "Mixed schedule active: $names"
                scheduledAppPackages.isNotEmpty() && strictActive ->
                    "Strict app schedule active: $names"
                scheduledAppPackages.isNotEmpty() ->
                    "App schedule active: $names"
                strictActive ->
                    "Strict group schedule active: $names"
                else ->
                    "Group schedule active: $names"
            }
            return LiveScheduleState(
                active = true,
                strictActive = strictActive,
                blockedGroupIds = scheduledGroupIds,
                blockedAppPackages = scheduledAppPackages,
                reason = reason
            )
        }

        fun isBudgetEvaluationDue(nowMs: Long, nextCheckAtMs: Long?, graceMs: Long = 15_000L): Boolean {
            val nextCheckAt = nextCheckAtMs ?: return true
            return nowMs + graceMs >= nextCheckAt
        }

        internal fun computeClosestBudgetCheckAtMs(
            nowMs: Long,
            baseNextCheckAtMs: Long?,
            groupPolicies: List<GroupPolicyInput>,
            appPolicies: List<AppPolicyInput>,
            usageInputs: UsageInputs,
            fullyExemptPackages: Set<String>,
            emergencyStates: List<EmergencyStateInput>
        ): Long? {
            val candidates = mutableListOf<Long>()
            if (hasBudgetBoundarySensitivePolicy(groupPolicies, appPolicies, emergencyStates)) {
                baseNextCheckAtMs?.let(candidates::add)
            }
            groupPolicies.forEach { group ->
                if (group.scheduleBlocked) return@forEach
                val eligibleMembers = group.members.filterNot(fullyExemptPackages::contains)
                if (eligibleMembers.isEmpty()) return@forEach
                val usedDay = eligibleMembers.sumOf { usageInputs.usedTodayMs[it] ?: 0L }
                val usedHour = eligibleMembers.sumOf { usageInputs.usedHourMs[it] ?: 0L }
                if (group.dailyLimitMs > 0L && usedDay < group.dailyLimitMs) {
                    candidates += nowMs + (group.dailyLimitMs - usedDay)
                }
                if (group.hourlyLimitMs > 0L && usedHour < group.hourlyLimitMs) {
                    candidates += nowMs + (group.hourlyLimitMs - usedHour)
                }
            }
            appPolicies.forEach { policy ->
                if (policy.scheduleBlocked || fullyExemptPackages.contains(policy.packageName)) return@forEach
                val usedDay = usageInputs.usedTodayMs[policy.packageName] ?: 0L
                val usedHour = usageInputs.usedHourMs[policy.packageName] ?: 0L
                if (policy.dailyLimitMs > 0L && usedDay < policy.dailyLimitMs) {
                    candidates += nowMs + (policy.dailyLimitMs - usedDay)
                }
                if (policy.hourlyLimitMs > 0L && usedHour < policy.hourlyLimitMs) {
                    candidates += nowMs + (policy.hourlyLimitMs - usedHour)
                }
            }
            return candidates.filter { it > nowMs }.minOrNull()
        }

        internal fun planNextPolicyWake(
            nowMs: Long,
            scheduleNextTransitionAtMs: Long?,
            budgetNextCheckAtMs: Long?,
            emergencyUnlockExpiresAtMs: Long?,
            touchGrassBreakUntilMs: Long?,
            reliabilityFallbackAtMs: Long?,
            keepOverdueBudgetCheck: Boolean = false
        ): PolicyWakePlan {
            val candidates = buildList {
                scheduleNextTransitionAtMs
                    ?.takeIf { it > nowMs }
                    ?.let { add(PolicyWakePlan(it, WAKE_REASON_SCHEDULE_TRANSITION)) }
                when {
                    budgetNextCheckAtMs == null -> Unit
                    budgetNextCheckAtMs > nowMs -> add(PolicyWakePlan(budgetNextCheckAtMs, WAKE_REASON_BUDGET_CHECK))
                    keepOverdueBudgetCheck -> add(PolicyWakePlan(nowMs + 1L, WAKE_REASON_BUDGET_CHECK))
                }
                emergencyUnlockExpiresAtMs
                    ?.takeIf { it > nowMs }
                    ?.let { add(PolicyWakePlan(it, WAKE_REASON_EMERGENCY_EXPIRY)) }
                touchGrassBreakUntilMs
                    ?.takeIf { it > nowMs }
                    ?.let { add(PolicyWakePlan(it, WAKE_REASON_TOUCH_GRASS_BREAK_END)) }
            }.sortedBy { it.atMs }
            return candidates.firstOrNull()
                ?: reliabilityFallbackAtMs
                    ?.takeIf { it > nowMs }
                    ?.let { PolicyWakePlan(it, WAKE_REASON_RELIABILITY_TICK) }
                ?: PolicyWakePlan(null, null)
        }

        internal fun pickNextAlarmAtMs(
            nowMs: Long,
            scheduleNextTransitionAtMs: Long?,
            budgetNextCheckAtMs: Long?,
            touchGrassBreakUntilMs: Long?,
            keepOverdueBudgetCheck: Boolean = false,
            emergencyUnlockExpiresAtMs: Long? = null,
            reliabilityFallbackAtMs: Long? = null
        ): Long? {
            return planNextPolicyWake(
                nowMs = nowMs,
                scheduleNextTransitionAtMs = scheduleNextTransitionAtMs,
                budgetNextCheckAtMs = budgetNextCheckAtMs,
                emergencyUnlockExpiresAtMs = emergencyUnlockExpiresAtMs,
                touchGrassBreakUntilMs = touchGrassBreakUntilMs,
                reliabilityFallbackAtMs = reliabilityFallbackAtMs,
                keepOverdueBudgetCheck = keepOverdueBudgetCheck
            ).atMs
        }

        private fun hasBudgetBoundarySensitivePolicy(
            groupPolicies: List<GroupPolicyInput>,
            appPolicies: List<AppPolicyInput>,
            emergencyStates: List<EmergencyStateInput>
        ): Boolean {
            if (emergencyStates.any { it.activeUntilEpochMs != null }) return true
            if (groupPolicies.any { it.dailyLimitMs > 0L || it.hourlyLimitMs > 0L || it.opensPerDay > 0 || it.emergencyConfig.enabled }) {
                return true
            }
            return appPolicies.any {
                it.dailyLimitMs > 0L || it.hourlyLimitMs > 0L || it.opensPerDay > 0 || it.emergencyConfig.enabled
            }
        }

        fun resolveEffectiveMode(
            manualMode: ModeState,
            scheduleComputed: Boolean,
            touchGrassBreakUntilMs: Long?,
            nowMs: Long
        ): ModeState {
            @Suppress("UNUSED_PARAMETER")
            manualMode
            @Suppress("UNUSED_PARAMETER")
            scheduleComputed
            @Suppress("UNUSED_PARAMETER")
            touchGrassBreakUntilMs
            @Suppress("UNUSED_PARAMETER")
            nowMs
            return ModeState.NORMAL
        }

        internal fun encodeSingleAppScheduleTarget(packageName: String): String = "app:$packageName"

        internal fun decodeSingleAppScheduleTarget(targetId: String): String? {
            val trimmed = targetId.trim()
            return if (trimmed.startsWith("app:") && trimmed.length > 4) trimmed.removePrefix("app:") else null
        }

        internal fun isSingleAppScheduleTarget(targetId: String): Boolean {
            return decodeSingleAppScheduleTarget(targetId) != null
        }

        private const val WAKE_REASON_SCHEDULE_TRANSITION = "schedule_transition"
        private const val WAKE_REASON_BUDGET_CHECK = "budget_check"
        private const val WAKE_REASON_EMERGENCY_EXPIRY = "emergency_expiry"
        private const val WAKE_REASON_TOUCH_GRASS_BREAK_END = "touch_grass_break_end"
        private const val WAKE_REASON_RELIABILITY_TICK = "reliability_tick"
        private const val RELIABILITY_TICK_INTERVAL_MS = 15L * 60_000L
    }
}












