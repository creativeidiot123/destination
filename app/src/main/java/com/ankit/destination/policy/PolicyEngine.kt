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
import com.ankit.destination.data.EnforcementStateEntity
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private suspend fun loadEnforcementState(): EnforcementStateEntity {
        val existing = db.enforcementStateDao().get()
        if (existing != null) return existing
        val bootstrapped = EnforcementStateEntity(
            scheduleLockComputed = store.isScheduleLockComputed(),
            scheduleLockEnforced = store.isScheduleLockEnforced(),
            scheduleStrictComputed = store.isScheduleStrictComputed(),
            scheduleStrictEnforced = store.isScheduleStrictEnforced(),
            scheduleBlockedGroupsEncoded = EnforcementStateCodecs.encodeStringSet(store.getScheduleBlockedGroups()),
            scheduleBlockedPackagesEncoded = EnforcementStateCodecs.encodeStringSet(store.getScheduleBlockedPackages()),
            strictInstallSuspendedPackagesEncoded = EnforcementStateCodecs.encodeStringSet(store.getStrictInstallSuspendedPackages()),
            scheduleLockReason = store.getScheduleLockReason(),
            scheduleTargetWarning = null,
            scheduleTargetDiagnosticCode = ScheduleTargetDiagnosticCode.NONE.name,
            scheduleNextTransitionAtMs = store.getScheduleNextTransitionAtMs(),
            budgetBlockedPackagesEncoded = EnforcementStateCodecs.encodeStringSet(store.getBudgetBlockedPackages()),
            budgetBlockedGroupIdsEncoded = EnforcementStateCodecs.encodeStringSet(store.getBudgetBlockedGroupIds()),
            budgetReason = store.getBudgetReason(),
            budgetUsageSnapshotStatus = UsageSnapshotStatus.fromUsageAccessGranted(store.isBudgetUsageAccessGranted()).name,
            budgetUsageAccessGranted = store.isBudgetUsageAccessGranted(),
            budgetNextCheckAtMs = store.getBudgetNextCheckAtMs(),
            nextPolicyWakeAtMs = store.getNextPolicyWakeAtMs(),
            nextPolicyWakeReason = store.getNextPolicyWakeReason(),
            primaryReasonByPackageEncoded = EnforcementStateCodecs.encodeStringMap(store.getPrimaryReasonByPackage()),
            blockReasonsByPackageEncoded = EnforcementStateCodecs.encodeReasonSetMap(store.getBlockReasonsByPackage()),
            lastSuspendedPackagesEncoded = EnforcementStateCodecs.encodeStringSet(store.getLastSuspendedPackages()),
            lastUninstallProtectedPackagesEncoded = EnforcementStateCodecs.encodeStringSet(store.getLastUninstallProtectedPackages()),
            lastAppliedAtMs = store.getLastAppliedAtMs(),
            lastVerificationPassed = store.getLastVerifyPassed(),
            lastError = store.getLastError(),
            lastSuccessfulApplyAtMs = if (store.hasSuccessfulPolicyApply()) store.getLastAppliedAtMs() else 0L,
            computedSnapshotVersion = 0L
        )
        db.enforcementStateDao().upsert(bootstrapped)
        return bootstrapped
    }

    private suspend fun persistEnforcementState(
        state: EnforcementStateEntity,
        mirrorScheduleEnforced: Boolean = state.scheduleLockEnforced,
        mirrorScheduleStrictEnforced: Boolean = state.scheduleStrictEnforced
    ) {
        db.enforcementStateDao().upsert(state)
        store.setComputedPolicyState(
            scheduleLockComputed = state.scheduleLockComputed,
            scheduleStrictComputed = state.scheduleStrictComputed,
            scheduleBlockedGroups = EnforcementStateCodecs.decodeStringSet(state.scheduleBlockedGroupsEncoded),
            scheduleBlockedPackages = EnforcementStateCodecs.decodeStringSet(state.scheduleBlockedPackagesEncoded),
            scheduleLockReason = state.scheduleLockReason,
            scheduleNextTransitionAtMs = state.scheduleNextTransitionAtMs,
            budgetBlockedPackages = EnforcementStateCodecs.decodeStringSet(state.budgetBlockedPackagesEncoded),
            budgetBlockedGroupIds = EnforcementStateCodecs.decodeStringSet(state.budgetBlockedGroupIdsEncoded),
            budgetReason = state.budgetReason,
            budgetUsageAccessGranted = state.budgetUsageAccessGranted,
            budgetNextCheckAtMs = state.budgetNextCheckAtMs,
            nextPolicyWakeAtMs = state.nextPolicyWakeAtMs,
            nextPolicyWakeReason = state.nextPolicyWakeReason,
            primaryReasonByPackage = EnforcementStateCodecs.decodeStringMap(state.primaryReasonByPackageEncoded),
            blockReasonsByPackage = EnforcementStateCodecs.decodeReasonSetMap(state.blockReasonsByPackageEncoded),
            clearStrictInstallSuspendedPackages = EnforcementStateCodecs.decodeStringSet(state.strictInstallSuspendedPackagesEncoded).isEmpty()
        )
        store.setScheduleEnforced(mirrorScheduleEnforced)
        store.setScheduleStrictEnforced(mirrorScheduleStrictEnforced)
    }

    private data class ApplyBookkeeping(
        val actualSuspended: Set<String>,
        val actualUninstallProtected: Set<String>
    )

    private suspend fun persistApplyBookkeeping(
        state: PolicyState,
        applyResult: ApplyResult,
        verification: PolicyVerificationResult,
        errorMessage: String?
    ): ApplyBookkeeping {
        val actualSuspended = if (applyResult.observedState.suspensionAuthoritative) {
            applyResult.observedState.suspendedPackages
        } else {
            PolicyStore.reconcileTrackedPackages(
                previousPackages = state.previouslySuspended,
                targetPackages = state.suspendTargets,
                failedAdds = applyResult.failedToSuspend,
                failedRemovals = applyResult.failedToUnsuspend
            )
        }
        val desiredUninstallProtected = PolicyStore.desiredUninstallProtectedPackages(state, appContext.packageName)
        val actualUninstallProtected = if (applyResult.observedState.uninstallProtectionAuthoritative) {
            applyResult.observedState.uninstallProtectedPackages
        } else {
            PolicyStore.reconcileTrackedPackages(
                previousPackages = state.previouslyUninstallProtectedPackages,
                targetPackages = desiredUninstallProtected,
                failedAdds = applyResult.failedToProtectUninstall,
                failedRemovals = applyResult.failedToUnprotectUninstall
            )
        }
        val nowMs = System.currentTimeMillis()
        val currentState = loadEnforcementState()
        persistEnforcementState(
            currentState.copy(
                lastSuspendedPackagesEncoded = EnforcementStateCodecs.encodeStringSet(actualSuspended),
                lastUninstallProtectedPackagesEncoded = EnforcementStateCodecs.encodeStringSet(actualUninstallProtected),
                lastAppliedAtMs = nowMs,
                lastVerificationPassed = verification.passed,
                lastError = errorMessage,
                lastSuccessfulApplyAtMs = if (errorMessage == null && verification.passed && applyResult.errors.isEmpty()) {
                    nowMs
                } else {
                    currentState.lastSuccessfulApplyAtMs
                },
                primaryReasonByPackageEncoded = EnforcementStateCodecs.encodeStringMap(state.primaryReasonByPackage),
                blockReasonsByPackageEncoded = EnforcementStateCodecs.encodeReasonSetMap(state.blockReasonsByPackage)
            )
        )
        store.recordApply(state, applyResult, verification, errorMessage, appContext.packageName)
        return ApplyBookkeeping(
            actualSuspended = actualSuspended,
            actualUninstallProtected = actualUninstallProtected
        )
    }

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

    private fun currentEnforcementState(): EnforcementStateEntity = runBlocking {
        loadEnforcementState()
    }

    fun isScheduleLockActive(): Boolean = currentEnforcementState().scheduleLockEnforced

    fun isStrictScheduleActive(): Boolean = currentEnforcementState().scheduleStrictEnforced

    fun isScheduleLockComputed(): Boolean = currentEnforcementState().scheduleLockComputed

    fun getScheduleLockReason(): String? = currentEnforcementState().scheduleLockReason

    fun getScheduleBlockedGroupIds(): Set<String> = EnforcementStateCodecs.decodeStringSet(
        currentEnforcementState().scheduleBlockedGroupsEncoded
    )

    fun getBudgetBlockedPackages(): Set<String> = EnforcementStateCodecs.decodeStringSet(
        currentEnforcementState().budgetBlockedPackagesEncoded
    )

    fun getBudgetBlockedGroupIds(): Set<String> = EnforcementStateCodecs.decodeStringSet(
        currentEnforcementState().budgetBlockedGroupIdsEncoded
    )

    fun getBudgetReason(): String? = currentEnforcementState().budgetReason

    fun getBudgetUsageSnapshotStatus(): UsageSnapshotStatus = parseUsageSnapshotStatus(
        raw = currentEnforcementState().budgetUsageSnapshotStatus,
        fallbackGranted = currentEnforcementState().budgetUsageAccessGranted
    )

    fun isBudgetUsageAccessGranted(): Boolean = currentEnforcementState().budgetUsageAccessGranted

    fun isHiddenSuspendPrototypeEnabled(): Boolean = store.isHiddenSuspendPrototypeEnabled()

    fun setHiddenSuspendPrototypeEnabled(enabled: Boolean) {
        store.setHiddenSuspendPrototypeEnabled(enabled)
    }

    fun getBudgetNextCheckAtMs(): Long? = currentEnforcementState().budgetNextCheckAtMs

    fun getStrictInstallSuspendedPackages(): Set<String> = EnforcementStateCodecs.decodeStringSet(
        currentEnforcementState().strictInstallSuspendedPackagesEncoded
    )

    fun getScheduleNextTransitionAtMs(): Long? = currentEnforcementState().scheduleNextTransitionAtMs

    fun currentUsageAccessComplianceState(
        usageSnapshotStatus: UsageSnapshotStatus = if (UsageAccess.hasUsageAccess(appContext)) {
            UsageSnapshotStatus.OK
        } else {
            UsageSnapshotStatus.ACCESS_MISSING
        }
    ): UsageAccessComplianceState {
        val lockdownEligible = isRecoveryLockdownEligible(
            deviceOwnerActive = isDeviceOwner(),
            provisioningFinalizationState = store.getProvisioningFinalizationState(),
            hasSuccessfulPolicyApply = store.hasSuccessfulPolicyApply(),
            hasAnyPriorApply = store.hasAnyPriorApply()
        )
        val usageAccessGranted = usageSnapshotStatus.usageAccessGranted
        val lockdownActive = lockdownEligible && usageSnapshotStatus == UsageSnapshotStatus.ACCESS_MISSING
        val recoveryResolution = if (lockdownActive) {
            resolver.resolveUsageAccessRecoveryPackages()
        } else {
            null
        }
        val baseReason = when {
            lockdownActive -> "Usage Access missing: recovery lockdown active"
            usageSnapshotStatus == UsageSnapshotStatus.INGESTION_FAILED ->
                "Usage ingestion failed: using last known usage snapshot when available"
            else -> null
        }
        val reason = when {
            baseReason == null -> null
            recoveryResolution == null -> baseReason
            recoveryResolution.warnings.isEmpty() -> baseReason
            else -> "$baseReason. ${recoveryResolution.warnings.joinToString("; ")}"
        }
        return UsageAccessComplianceState(
            snapshotStatus = usageSnapshotStatus,
            usageAccessGranted = usageAccessGranted,
            lockdownEligible = lockdownEligible,
            lockdownActive = lockdownActive,
            recoveryAllowlist = recoveryResolution?.packages.orEmpty(),
            reason = reason
        )
    }

    fun currentUsageAccessComplianceState(usageAccessGranted: Boolean): UsageAccessComplianceState {
        return currentUsageAccessComplianceState(UsageSnapshotStatus.fromUsageAccessGranted(usageAccessGranted))
    }

    fun currentAccessibilityComplianceState(
        accessibilityState: com.ankit.destination.enforce.AccessibilityMonitorState =
            AccessibilityStatusMonitor.refreshNow(appContext, "accessibility_compliance"),
        nowMs: Long = clock.nowMs()
    ): AccessibilityComplianceState {
        val accessibilityServiceRunning = AccessibilityStatusMonitor.serviceRunning(accessibilityState, nowMs)
        val lockdownEligible = isRecoveryLockdownEligible(
            deviceOwnerActive = isDeviceOwner(),
            provisioningFinalizationState = store.getProvisioningFinalizationState(),
            hasSuccessfulPolicyApply = store.hasSuccessfulPolicyApply(),
            hasAnyPriorApply = store.hasAnyPriorApply()
        )
        val lockdownActive = lockdownEligible && (!accessibilityState.enabled || !accessibilityServiceRunning)
        val recoveryResolution = if (lockdownActive) {
            resolver.resolveAccessibilityRecoveryPackages()
        } else {
            null
        }
        val baseReason = when {
            !accessibilityState.enabled && lockdownActive ->
                "Accessibility missing: recovery lockdown active"
            !accessibilityState.enabled ->
                "Enable Accessibility for real-time blocking and recovery enforcement."
            !accessibilityServiceRunning && lockdownActive ->
                "Accessibility service not reporting in: recovery lockdown active"
            !accessibilityServiceRunning ->
                "Accessibility is enabled, but the enforcement service is not reporting in. Open Accessibility settings and verify Destination is active."
            else -> null
        }
        val reason = when {
            baseReason == null -> null
            recoveryResolution == null -> baseReason
            recoveryResolution.warnings.isEmpty() -> baseReason
            else -> "$baseReason. ${recoveryResolution.warnings.joinToString("; ")}"
        }
        return AccessibilityComplianceState(
            accessibilityServiceEnabled = accessibilityState.enabled,
            accessibilityServiceRunning = accessibilityServiceRunning,
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

    internal suspend fun getAppProtectionSnapshotAsync(): AppProtectionSnapshot {
        val usageAccessComplianceState = currentUsageAccessComplianceState()
        val accessibilityComplianceState = currentAccessibilityComplianceState()
        val recoveryLockdownState = buildRecoveryLockdownState(
            usageAccessComplianceState = usageAccessComplianceState,
            accessibilityComplianceState = accessibilityComplianceState
        )
        return buildAppProtectionSnapshot(loadPolicyControlsAsync(), recoveryLockdownState)
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
        return runBlocking {
            APPLY_MUTEX.withLock {
                val previousEffectiveMode = store.getDesiredMode()
                val emergencyApps = getEmergencyApps()

                FocusLog.i(FocusEventId.MODE_CHANGE_REQUEST, "Requested mode=$mode reason=$reason")

                if (!isDeviceOwner()) {
                    return@withLock failureResult(
                        message = "Not device owner",
                        stateMode = previousEffectiveMode,
                        emergencyApps = emergencyApps
                    )
                }

                if (isScheduleLockActive() && mode == ModeState.NORMAL) {
                    return@withLock failureResult(
                        message = "This lock is scheduled and cannot be canceled.",
                        stateMode = previousEffectiveMode,
                        emergencyApps = emergencyApps
                    )
                }
                val previousManualMode = store.getManualMode()
                store.setManualMode(mode)
                val result = requestApplyNowLocked(
                    hostActivity = hostActivity,
                    triggerBatch = ApplyTriggerBatch.single(
                        ApplyTrigger(
                            category = ApplyTriggerCategory.MODE_CHANGE,
                            source = "manual",
                            detail = reason
                        )
                    )
                )
                if (!result.success) {
                    store.setManualMode(previousManualMode)
                }
                result
            }
        }
    }

    @Deprecated("Use triggerScheduleRefresh(trigger, hostActivity) instead")
    fun applyScheduleDecision(
        decision: ScheduleDecision,
        scheduleBlockedPackages: Set<String>,
        trigger: String,
        hostActivity: Activity? = null
    ): EngineResult {
        return triggerScheduleRefresh(
            trigger = ApplyTrigger(
                category = ApplyTriggerCategory.SCHEDULE,
                source = "schedule",
                detail = trigger
            ),
            hostActivity = hostActivity
        )
    }

    @Deprecated("Use triggerBudgetRefresh(trigger, hostActivity) instead")
    fun setBudgetState(
        blockedPackages: Set<String>,
        blockedGroupIds: Set<String>,
        reason: String?,
        usageAccessGranted: Boolean,
        nextCheckAtMs: Long?,
        hostActivity: Activity? = null,
        trigger: String = "budget"
    ): EngineResult {
        return triggerBudgetRefresh(
            trigger = ApplyTrigger(
                category = ApplyTriggerCategory.POLICY_MUTATION,
                source = "budget",
                detail = trigger
            ),
            hostActivity = hostActivity
        )
    }

    fun shouldRunBudgetEvaluation(nowMs: Long = clock.nowMs(), graceMs: Long = 15_000L): Boolean {
        return isBudgetEvaluationDue(nowMs, getBudgetNextCheckAtMs(), graceMs)
    }

    fun triggerScheduleRefresh(trigger: ApplyTrigger, hostActivity: Activity? = null): EngineResult {
        return requestApplyNow(hostActivity = hostActivity, triggerBatch = ApplyTriggerBatch.single(trigger))
    }

    fun triggerBudgetRefresh(trigger: ApplyTrigger, hostActivity: Activity? = null): EngineResult {
        return requestApplyNow(hostActivity = hostActivity, triggerBatch = ApplyTriggerBatch.single(trigger))
    }

    fun requestApplyNow(hostActivity: Activity? = null, reason: String = "apply_now"): EngineResult {
        return requestApplyNow(
            hostActivity = hostActivity,
            triggerBatch = ApplyTriggerBatch.single(
                ApplyTrigger(
                    category = ApplyTriggerCategory.UNKNOWN,
                    source = "legacy_request",
                    detail = reason
                )
            )
        )
    }

    fun requestApplyNow(
        hostActivity: Activity? = null,
        triggerBatch: ApplyTriggerBatch
    ): EngineResult {
        return runBlocking {
            APPLY_MUTEX.withLock {
                requestApplyNowLocked(hostActivity = hostActivity, triggerBatch = triggerBatch)
            }
        }
    }

    suspend fun requestApplyNowAsync(
        hostActivity: Activity? = null,
        triggerBatch: ApplyTriggerBatch
    ): EngineResult {
        return APPLY_MUTEX.withLock {
            requestApplyNowLocked(hostActivity = hostActivity, triggerBatch = triggerBatch)
        }
    }

    fun reapplyDesiredMode(hostActivity: Activity? = null, reason: String = "reapply"): EngineResult {
        return requestApplyNow(
            hostActivity = hostActivity,
            triggerBatch = ApplyTriggerBatch.single(
                ApplyTrigger(
                    category = ApplyTriggerCategory.MANUAL,
                    source = "reapply",
                    detail = reason
                )
            )
        )
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
                    snapshotStatus = UsageSnapshotStatus.OK,
                    usageAccessGranted = true,
                    lockdownEligible = false,
                    lockdownActive = false,
                    recoveryAllowlist = emptySet(),
                    reason = null
                ),
                accessibilityComplianceState = AccessibilityComplianceState(
                    accessibilityServiceEnabled = true,
                    accessibilityServiceRunning = true,
                    lockdownEligible = false,
                    lockdownActive = false,
                    recoveryAllowlist = emptySet(),
                    reason = null
                ),
                recoveryLockdownState = RecoveryLockdownState(
                    active = false,
                    allowlist = emptySet(),
                    allowlistReasons = emptyMap(),
                    reasonTokens = emptySet(),
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
                val actualSuspended = PolicyStore.reconcileTrackedPackages(
                    previousPackages = trackedPackages.previouslySuspended,
                    targetPackages = cleanState.suspendTargets,
                    failedAdds = applyResult.failedToSuspend,
                    failedRemovals = applyResult.failedToUnsuspend
                )
                val actualUninstallProtected = PolicyStore.reconcileTrackedPackages(
                    previousPackages = trackedPackages.previouslyUninstallProtected,
                    targetPackages = emptySet(),
                    failedAdds = applyResult.failedToProtectUninstall,
                    failedRemovals = applyResult.failedToUnprotectUninstall
                )
                val resetAppliedAtMs = System.currentTimeMillis()
                runBlocking {
                    persistEnforcementState(
                        EnforcementStateEntity(
                            strictInstallSuspendedPackagesEncoded = "",
                            lastSuspendedPackagesEncoded = EnforcementStateCodecs.encodeStringSet(actualSuspended),
                            lastUninstallProtectedPackagesEncoded = EnforcementStateCodecs.encodeStringSet(actualUninstallProtected),
                            lastAppliedAtMs = resetAppliedAtMs,
                            lastVerificationPassed = verification.passed,
                            lastError = errorMessage,
                            lastSuccessfulApplyAtMs = if (success) resetAppliedAtMs else 0L
                        )
                    )
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
                        state = cleanState,
                        repairPlan = null
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
                        state = cleanState,
                        repairPlan = null
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
                    state = cleanState,
                    repairPlan = null
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
            usageAccessComplianceState = external.usageAccessComplianceState,
            accessibilityComplianceState = external.accessibilityComplianceState,
            recoveryLockdownState = external.recoveryLockdownState
        )
        return applier.verify(state, hostActivity)
    }

    fun diagnosticsSnapshot(): DiagnosticsSnapshot {
        val desiredMode = store.getDesiredMode()
        val trackedPackages = sanitizeTrackedPackages()
        val nowMs = clock.nowMs()
        val external = computeExternalState(nowMs)
        val enforcementState = currentEnforcementState()
        val persistedScheduleTargetDiagnosticCode = runCatching {
            ScheduleTargetDiagnosticCode.valueOf(enforcementState.scheduleTargetDiagnosticCode)
        }.getOrDefault(ScheduleTargetDiagnosticCode.NONE)
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
            usageAccessComplianceState = external.usageAccessComplianceState,
            accessibilityComplianceState = external.accessibilityComplianceState,
            recoveryLockdownState = external.recoveryLockdownState
        )
        val restrictions = expected.restrictions.associateWith { facade.hasUserRestriction(it) }
        val scheduleReason = enforcementState.scheduleLockReason
        val scheduleComputed = enforcementState.scheduleLockComputed
        val scheduleActive = enforcementState.scheduleLockEnforced
        val scheduleStrictComputed = enforcementState.scheduleStrictComputed
        val scheduleStrictActive = enforcementState.scheduleStrictEnforced
        val scheduleBlockedGroups = EnforcementStateCodecs.decodeStringSet(enforcementState.scheduleBlockedGroupsEncoded)
        val scheduleBlockedPackages = EnforcementStateCodecs.decodeStringSet(enforcementState.scheduleBlockedPackagesEncoded)
        val budgetBlockedPackages = EnforcementStateCodecs.decodeStringSet(enforcementState.budgetBlockedPackagesEncoded)
        val budgetBlockedGroupIds = EnforcementStateCodecs.decodeStringSet(enforcementState.budgetBlockedGroupIdsEncoded)
        val lastSuspendedPackages = EnforcementStateCodecs.decodeStringSet(enforcementState.lastSuspendedPackagesEncoded)
        val persistedUsageSnapshotStatus = parseUsageSnapshotStatus(
            raw = enforcementState.budgetUsageSnapshotStatus,
            fallbackGranted = enforcementState.budgetUsageAccessGranted
        )
        val compliance = currentUsageAccessComplianceState(persistedUsageSnapshotStatus)
        val accessibilityCompliance = external.accessibilityComplianceState
        return DiagnosticsSnapshot(
            deviceOwner = isDeviceOwner(),
            desiredMode = desiredMode,
            manualMode = store.getManualMode(),
            usageSnapshotStatus = persistedUsageSnapshotStatus,
            usageAccessGranted = compliance.usageAccessGranted,
            accessibilityServiceEnabled = accessibilityCompliance.accessibilityServiceEnabled,
            accessibilityServiceRunning = accessibilityCompliance.accessibilityServiceRunning,
            accessibilityDegradedReason = accessibilityCompliance.reason,
            usageAccessRecoveryLockdownActive = compliance.lockdownActive,
            usageAccessRecoveryAllowlist = compliance.recoveryAllowlist,
            usageAccessRecoveryReason = compliance.reason,
            lastUsageAccessCheckAtMs = UsageAccessMonitor.currentState.value.lastCheckAtMs,
            lastAccessibilityStatusCheckAtMs = external.accessibilityState.lastCheckAtMs,
            lastAccessibilityServiceConnectAtMs = external.accessibilityState.lastConnectedAtMs.takeIf { it > 0L },
            lastAccessibilityHeartbeatAtMs = external.accessibilityState.lastHeartbeatAtMs.takeIf { it > 0L },
            scheduleLockComputed = scheduleComputed,
            scheduleLockActive = scheduleActive,
            scheduleStrictComputed = scheduleStrictComputed,
            scheduleStrictActive = scheduleStrictActive,
            scheduleBlockedGroups = scheduleBlockedGroups,
            scheduleBlockedPackages = scheduleBlockedPackages,
            scheduleLockReason = scheduleReason,
            scheduleTargetWarning = enforcementState.scheduleTargetWarning,
            scheduleTargetDiagnosticCode = persistedScheduleTargetDiagnosticCode,
            scheduleNextTransitionAtMs = enforcementState.scheduleNextTransitionAtMs,
            budgetBlockedPackages = budgetBlockedPackages,
            budgetBlockedGroupIds = budgetBlockedGroupIds,
            budgetReason = enforcementState.budgetReason,
            budgetUsageSnapshotStatus = persistedUsageSnapshotStatus,
            budgetUsageAccessGranted = enforcementState.budgetUsageAccessGranted,
            budgetNextCheckAtMs = enforcementState.budgetNextCheckAtMs,
            nextPolicyWakeAtMs = enforcementState.nextPolicyWakeAtMs,
            nextPolicyWakeReason = enforcementState.nextPolicyWakeReason,
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
            lastAppliedAtMs = enforcementState.lastAppliedAtMs,
            lastVerificationPassed = enforcementState.lastVerificationPassed,
            lastError = enforcementState.lastError,
            hiddenSuspendPrototypeEnabled = store.isHiddenSuspendPrototypeEnabled(),
            packageSuspendBackend = store.getLastPackageSuspendBackend(),
            packageSuspendPrototypeError = store.getLastPackageSuspendPrototypeError(),
            lastSuspendedPackages = lastSuspendedPackages,
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
                protectionSnapshot = external.protectionSnapshot,
                strictInstallStored = EnforcementStateCodecs.decodeStringSet(enforcementState.strictInstallSuspendedPackagesEncoded),
                scheduleNextTransitionAtMs = enforcementState.scheduleNextTransitionAtMs,
                budgetNextCheckAtMs = enforcementState.budgetNextCheckAtMs
            )
        )
    }

    fun dashboardSnapshot(nowMs: Long = clock.nowMs()): DashboardSnapshot {
        UsageAccessMonitor.currentState.value.takeIf { it.lastCheckAtMs > 0L } ?: UsageAccessMonitor.refreshNow(
            context = appContext,
            reason = "dashboard_snapshot",
            requestPolicyRefreshIfChanged = false
        )
        val enforcementState = currentEnforcementState()
        val persistedUsageSnapshotStatus = parseUsageSnapshotStatus(
            raw = enforcementState.budgetUsageSnapshotStatus,
            fallbackGranted = enforcementState.budgetUsageAccessGranted
        )
        val compliance = currentUsageAccessComplianceState(persistedUsageSnapshotStatus)
        val accessibilityState = AccessibilityStatusMonitor.currentState.value.let { state ->
            if (state.lastCheckAtMs > 0L) {
                state
            } else {
                AccessibilityStatusMonitor.refreshNow(appContext, "dashboard_snapshot")
            }
        }
        val accessibilityCompliance = currentAccessibilityComplianceState(
            accessibilityState = accessibilityState,
            nowMs = nowMs
        )
        val scheduleBlockedGroupsCount = EnforcementStateCodecs.decodeStringSet(
            enforcementState.scheduleBlockedGroupsEncoded
        ).size
        val budgetBlockedPackages = EnforcementStateCodecs.decodeStringSet(enforcementState.budgetBlockedPackagesEncoded)
        val lastSuspendedPackages = EnforcementStateCodecs.decodeStringSet(enforcementState.lastSuspendedPackagesEncoded)
        return DashboardSnapshot(
            deviceOwner = isDeviceOwner(),
            usageSnapshotStatus = persistedUsageSnapshotStatus,
            usageAccessGranted = compliance.usageAccessGranted,
            accessibilityServiceEnabled = accessibilityCompliance.accessibilityServiceEnabled,
            accessibilityServiceRunning = accessibilityCompliance.accessibilityServiceRunning,
            accessibilityDegradedReason = accessibilityCompliance.reason,
            usageAccessRecoveryLockdownActive = compliance.lockdownActive,
            usageAccessRecoveryReason = compliance.reason,
            scheduleBlockedGroupsCount = scheduleBlockedGroupsCount,
            scheduleStrictActive = enforcementState.scheduleStrictEnforced,
            totalBlockedApps = (budgetBlockedPackages + lastSuspendedPackages).size,
            lastAppliedAtMs = enforcementState.lastAppliedAtMs,
            lastError = enforcementState.lastError,
            nextPolicyWakeAtMs = enforcementState.nextPolicyWakeAtMs,
            nextPolicyWakeReason = enforcementState.nextPolicyWakeReason,
            vpnActive = isVpnActive()
        )
    }

    fun uiSnapshot(): PolicyUiSnapshot {
        val enforcementState = currentEnforcementState()
        return PolicyUiSnapshot(
            scheduleBlockedGroups = EnforcementStateCodecs.decodeStringSet(enforcementState.scheduleBlockedGroupsEncoded),
            budgetBlockedPackages = EnforcementStateCodecs.decodeStringSet(enforcementState.budgetBlockedPackagesEncoded),
            budgetBlockedGroupIds = EnforcementStateCodecs.decodeStringSet(enforcementState.budgetBlockedGroupIdsEncoded),
            lastSuspendedPackages = EnforcementStateCodecs.decodeStringSet(enforcementState.lastSuspendedPackagesEncoded),
            primaryReasonByPackage = EnforcementStateCodecs.decodeStringMap(enforcementState.primaryReasonByPackageEncoded),
            scheduleLockReason = enforcementState.scheduleLockReason,
            budgetReason = enforcementState.budgetReason,
            currentLockReason = store.getCurrentLockReason()
        )
    }

    fun onNewPackageInstalledDuringStrictSchedule(packageName: String): Boolean {
        return runBlocking {
            APPLY_MUTEX.withLock {
                if (!isDeviceOwner()) return@withLock false
                val enforcementState = loadEnforcementState()
                if (
                    shouldRefreshStrictScheduleForInstall(
                        scheduleStrictComputed = enforcementState.scheduleStrictComputed,
                        scheduleNextTransitionAtMs = enforcementState.scheduleNextTransitionAtMs,
                        nowMs = clock.nowMs()
                    )
                ) {
                    val orchestrated = orchestrateCurrentPolicy(
                        triggerBatch = ApplyTriggerBatch.single(
                            ApplyTrigger(
                                category = ApplyTriggerCategory.STRICT_INSTALL_STAGE,
                                source = "strict_install_stage",
                                detail = packageName,
                                packages = setOf(packageName),
                                stagedStrictInstall = true
                            )
                        )
                    )
                    persistComputedState(orchestrated)
                    if (!orchestrated.scheduleState.strictActive) return@withLock false
                } else if (!enforcementState.scheduleStrictComputed) {
                    return@withLock false
                }
                val usageAccessComplianceState = currentUsageAccessComplianceState()
                val accessibilityComplianceState = currentAccessibilityComplianceState()
                val recoveryLockdownState = buildRecoveryLockdownState(
                    usageAccessComplianceState = usageAccessComplianceState,
                    accessibilityComplianceState = accessibilityComplianceState
                )
                val controls = loadPolicyControlsAsync()
                val protectionSnapshot = buildAppProtectionSnapshot(controls, recoveryLockdownState)
                if (!protectionSnapshot.isEligibleForAllAppsExpansion(packageName)) return@withLock false
                val suspendable = resolver.filterSuspendable(
                    packages = setOf(packageName),
                    allowlist = protectionSnapshot.fullyExemptPackages
                )
                if (suspendable.isEmpty()) return@withLock false
                val refreshedState = loadEnforcementState()
                persistEnforcementState(
                    refreshedState.copy(
                        strictInstallSuspendedPackagesEncoded = EnforcementStateCodecs.encodeStringSet(
                            EnforcementStateCodecs.decodeStringSet(refreshedState.strictInstallSuspendedPackagesEncoded) + packageName
                        )
                    )
                )
                store.addStrictInstallSuspendedPackage(packageName)
                FocusLog.i(FocusEventId.STRICT_INSTALL_SUSPEND, "Strict schedule staged suspend pkg=$packageName")
                true
            }
        }
    }

    private suspend fun applyModeInternal(
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
            usageAccessComplianceState = external.usageAccessComplianceState,
            accessibilityComplianceState = external.accessibilityComplianceState,
            recoveryLockdownState = external.recoveryLockdownState
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
        val verification = applyResult.verification ?: applier.verify(state, hostActivity)
        val success = applyResult.errors.isEmpty() &&
            verification.passed &&
            !applyResult.coreFailure &&
            !applyResult.supportingFailure
        if (success) {
            store.setDesiredMode(normalizedTargetMode)
            store.setEmergencyApps(
                if (external.recoveryLockdownState.active) {
                    emergencyApps
                } else {
                    state.emergencyApps
                }
            )
            store.setCurrentLockReason(external.lockReason)
            persistApplyBookkeeping(state, applyResult, verification, null)
            FocusLog.i(FocusEventId.POLICY_APPLY_DONE, "Apply success mode=${state.mode}")
            return EngineResult(true, "Policy applied", verification, state, applyResult.repairPlan)
        }

        val errorMessage = buildList {
            addAll(applyResult.errors)
            addAll(verification.issues)
        }.joinToString(" | ")
        val bookkeeping = persistApplyBookkeeping(state, applyResult, verification, errorMessage)
        FocusLog.w(FocusEventId.MODE_CHANGE_FAIL, "Apply failed mode=$targetMode error=$errorMessage")

        val rollbackState = evaluator.evaluate(
            mode = rollbackMode,
            emergencyApps = emergencyApps,
            protectionSnapshot = external.protectionSnapshot,
            alwaysBlockedApps = external.policyControls.alwaysBlockedInstalledPackages,
            strictInstallBlockedPackages = external.strictInstallBlockedPackages,
            uninstallProtectedApps = external.policyControls.uninstallProtectedApps,
            globalControls = external.policyControls.globalControls,
            previouslySuspended = bookkeeping.actualSuspended,
            previouslyUninstallProtected = bookkeeping.actualUninstallProtected,
            budgetBlockedPackages = external.budgetBlockedPackages,
            blockReasonsByPackage = external.blockReasonsByPackage,
            primaryReasonByPackage = external.primaryReasonByPackage,
            lockReason = rollbackLockReason,
            touchGrassBreakActive = external.touchGrassBreakActive,
            usageAccessComplianceState = external.usageAccessComplianceState,
            accessibilityComplianceState = external.accessibilityComplianceState,
            recoveryLockdownState = external.recoveryLockdownState
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
        persistApplyBookkeeping(rollbackState, rollbackApply, rollbackVerify, rollbackError)

        return EngineResult(false, "Policy failed: $errorMessage", verification, state, applyResult.repairPlan)
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
            usageAccessComplianceState = external.usageAccessComplianceState,
            accessibilityComplianceState = external.accessibilityComplianceState,
            recoveryLockdownState = external.recoveryLockdownState
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
            state = state,
            repairPlan = null
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

    private fun sanitizeTrackedPackages(): TrackedPackages = runBlocking {
        sanitizeTrackedPackagesAsync()
    }

    private suspend fun sanitizeTrackedPackagesAsync(): TrackedPackages {
        val enforcementState = loadEnforcementState()
        val installedCheck = resolver::isPackageInstalled
        val trackedSuspendCandidates = linkedSetOf<String>().apply {
            addAll(EnforcementStateCodecs.decodeStringSet(enforcementState.lastSuspendedPackagesEncoded))
            addAll(EnforcementStateCodecs.decodeStringSet(enforcementState.scheduleBlockedPackagesEncoded))
            addAll(EnforcementStateCodecs.decodeStringSet(enforcementState.budgetBlockedPackagesEncoded))
            addAll(EnforcementStateCodecs.decodeStringSet(enforcementState.strictInstallSuspendedPackagesEncoded))
            addAll(EnforcementStateCodecs.decodeStringMap(enforcementState.primaryReasonByPackageEncoded).keys)
            addAll(EnforcementStateCodecs.decodeReasonSetMap(enforcementState.blockReasonsByPackageEncoded).keys)
        }
        val retainedSuspended = retainInstalledTrackedPackages(
            trackedPackages = trackedSuspendCandidates,
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
            persistEnforcementState(
                enforcementState.copy(
                    lastSuspendedPackagesEncoded = EnforcementStateCodecs.encodeStringSet(recoveredSuspended)
                )
            )
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
                trackedPackages = EnforcementStateCodecs.decodeStringSet(enforcementState.lastUninstallProtectedPackagesEncoded),
                controllerPackageName = appContext.packageName,
                isInstalled = installedCheck
            )
        )
    }

    private suspend fun requestApplyNowLocked(
        hostActivity: Activity?,
        triggerBatch: ApplyTriggerBatch
    ): EngineResult {
        val overallStartNs = System.nanoTime()
        val compatibilityReason = triggerBatch.compatibilityLabel
        FocusLog.d(FocusEventId.POLICY_STATE_COMPUTED, "â•”â•â• PolicyEngine.requestApplyNow() reason=$compatibilityReason categories=${triggerBatch.categoryCounts} â•â•")
        UsageAccessMonitor.refreshNow(
            context = appContext,
            reason = "policy_request:$compatibilityReason",
            requestPolicyRefreshIfChanged = false
        )
        val emergencyApps = getEmergencyApps()
        val previousMode = store.getDesiredMode()
        val previousLockReason = store.getCurrentLockReason()
        val previousEnforcementState = loadEnforcementState()
        val previousScheduleEnforced = previousEnforcementState.scheduleLockEnforced
        val previousStrictEnforced = previousEnforcementState.scheduleStrictEnforced
        val previousComputedSnapshot = store.captureComputedPolicySnapshot()
        FocusLog.d(FocusEventId.POLICY_STATE_COMPUTED, "â•‘ prevMode=$previousMode prevLockReason=$previousLockReason prevSchedEnforced=$previousScheduleEnforced prevStrict=$previousStrictEnforced")

        if (!isDeviceOwner()) {
            FocusLog.e(FocusEventId.POLICY_STATE_COMPUTED, "â•šâ•â• NOT device owner â€” aborting â•â•")
            return failureResult(
                message = "Not device owner",
                stateMode = previousMode,
                emergencyApps = emergencyApps
            )
        }

        val orchestrateStartNs = System.nanoTime()
        val orchestrated = orchestrateCurrentPolicy(triggerBatch = triggerBatch)
        FocusLog.d(
            FocusEventId.POLICY_STATE_COMPUTED,
            "orchestrateCurrentPolicy completed in %.1fms".format((System.nanoTime() - orchestrateStartNs) / 1_000_000.0)
        )
        val computedEnforcementState = persistComputedState(orchestrated)
        val external = computeExternalStateAsync(orchestrated.nowMs)
        FocusLog.d(FocusEventId.POLICY_STATE_COMPUTED, "â•‘ effectiveMode=${external.effectiveMode} schedLock=${orchestrated.scheduleState.active} strict=${orchestrated.scheduleState.strictActive}")
        FocusLog.d(FocusEventId.POLICY_STATE_COMPUTED, "â•‘ suspendTargets=${external.budgetBlockedPackages.size} schedBlockedGroups=${orchestrated.scheduleState.blockedGroupIds.size} schedBlockedApps=${orchestrated.scheduleState.blockedAppPackages.size} budgetBlocked=${external.budgetBlockedPackages.size}")
        val applyStartNs = System.nanoTime()
        val result = applyModeInternal(
            targetMode = external.effectiveMode,
            hostActivity = hostActivity,
            reason = "request:$compatibilityReason",
            rollbackMode = previousMode,
            rollbackLockReason = previousLockReason,
            emergencyApps = emergencyApps,
            external = external
        )
        FocusLog.d(
            FocusEventId.POLICY_APPLY_START,
            "applyModeInternal completed in %.1fms".format((System.nanoTime() - applyStartNs) / 1_000_000.0)
        )
        if (result.success) {
            persistEnforcementState(
                computedEnforcementState.copy(
                    scheduleLockEnforced = orchestrated.scheduleState.active,
                    scheduleStrictEnforced = orchestrated.scheduleState.strictActive
                ),
                mirrorScheduleEnforced = orchestrated.scheduleState.active,
                mirrorScheduleStrictEnforced = orchestrated.scheduleState.strictActive
            )
        } else {
            persistEnforcementState(
                previousEnforcementState,
                mirrorScheduleEnforced = previousScheduleEnforced,
                mirrorScheduleStrictEnforced = previousStrictEnforced
            )
            store.restoreComputedPolicySnapshot(previousComputedSnapshot)
            store.setScheduleEnforced(previousScheduleEnforced)
            store.setScheduleStrictEnforced(previousStrictEnforced)
            FocusLog.w(FocusEventId.POLICY_STATE_COMPUTED, "â•‘ apply FAILED â€” rolled back schedule state")
        }
        syncNextAlarm(nowMs = orchestrated.nowMs, repairPlan = result.repairPlan)
        val totalMs = (System.nanoTime() - overallStartNs) / 1_000_000.0
        FocusLog.d(FocusEventId.POLICY_STATE_COMPUTED, "â•šâ•â• requestApplyNow() DONE in %.1fms success=${result.success} â•â•".format(totalMs))
        return result
    }

    private fun syncNextAlarm(nowMs: Long = clock.nowMs(), repairPlan: PolicyRepairPlan? = null) {
        val enforcementState = currentEnforcementState()
        val repairWakeAtMs = repairPlan?.delayMs?.takeIf { repairPlan.required }?.let { nowMs + it }
        val nextWakeAtMs = listOfNotNull(enforcementState.nextPolicyWakeAtMs, repairWakeAtMs).minOrNull()
        val nextWakeReason = when (nextWakeAtMs) {
            repairWakeAtMs -> WAKE_REASON_POLICY_REPAIR
            else -> enforcementState.nextPolicyWakeReason
        }
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

    private suspend fun orchestrateCurrentPolicy(
        now: ZonedDateTime = clock.now(),
        triggerBatch: ApplyTriggerBatch
    ): OrchestratedState {
        val policyControls = loadPolicyControlsAsync()
        val installedTargetablePackages = resolver.getInstalledTargetablePackages()
        val hardProtectedPackages = resolver.getHardProtectedPackages()
        val loaded = withContext(Dispatchers.IO) {
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
        val usageSnapshot = budgetClient.readUsageSnapshot(now)
        val groupMembers = loaded.mappings.groupBy(AppGroupMap::groupId) { it.packageName }
        val groupInputs = loaded.groupLimits.map { limit ->
            val normalized = normalizeGroupPolicy(limit)
            GroupPolicyInput(
                groupId = normalized.groupId,
                priorityIndex = normalized.priorityIndex,
                strictInstallParticipates = normalized.strictInstallParticipates,
                targetMode = normalized.targetMode,
                dailyLimitMs = normalized.dailyLimitMs,
                hourlyLimitMs = normalized.hourlyLimitMs,
                opensPerDay = normalized.opensPerDay,
                members = groupMembers[normalized.groupId].orEmpty().toSet(),
                emergencyConfig = loaded.groupEmergencyConfigs[normalized.groupId].toEmergencyConfig(),
                scheduleBlocked = activeScheduleTargets.scheduledGroupIds.contains(normalized.groupId)
            )
        }
        val baseScheduleState = resolveLiveScheduleState(
            scheduleDecision = scheduleDecision,
            scheduleBlocks = loaded.scheduleBlocks,
            targetedActiveBlockIds = activeScheduleTargets.targetedActiveBlockIds,
            scheduledGroupIds = activeScheduleTargets.scheduledGroupIds,
            scheduledAppPackages = activeScheduleTargets.scheduledAppPackages
        )
        val strictActive = resolveStrictScheduleActive(scheduleStrictActive = baseScheduleState.strictActive)
        val scheduleState = baseScheduleState.copy(strictActive = strictActive)
        val activeAllAppsScheduleGroupIds = groupInputs
            .asSequence()
            .filter { it.scheduleBlocked && it.targetMode == GroupTargetMode.ALL_APPS }
            .map { it.groupId }
            .toSet()
        val enforcementState = loadEnforcementState()
        val strictInstallBlocked = if (strictActive && activeAllAppsScheduleGroupIds.isEmpty()) {
            EnforcementStateCodecs.decodeStringSet(enforcementState.strictInstallSuspendedPackagesEncoded)
        } else {
            emptySet()
        }
        val appInputs = loaded.appPolicies.map { policy ->
            val normalized = normalizeAppPolicy(policy)
            AppPolicyInput(
                packageName = normalized.packageName,
                dailyLimitMs = normalized.dailyLimitMs,
                hourlyLimitMs = normalized.hourlyLimitMs,
                opensPerDay = normalized.opensPerDay,
                emergencyConfig = EmergencyConfigInput(
                    enabled = policy.emergencyEnabled,
                    unlocksPerDay = policy.unlocksPerDay,
                    minutesPerUnlock = policy.minutesPerUnlock
                ),
                scheduleBlocked = activeScheduleTargets.scheduledAppPackages.contains(normalized.packageName)
            )
        }
        val nowMs = now.toInstant().toEpochMilli()
        val usageAccessComplianceState = currentUsageAccessComplianceState(usageSnapshot.status)
        val accessibilityComplianceState = currentAccessibilityComplianceState(nowMs = nowMs)
        val recoveryLockdownState = buildRecoveryLockdownState(
            usageAccessComplianceState = usageAccessComplianceState,
            accessibilityComplianceState = accessibilityComplianceState
        )
        val protectionSnapshot = buildAppProtectionSnapshot(policyControls, recoveryLockdownState)
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
            emergencyStates = emergencyStates,
            allAppsExcludedPackages = protectionSnapshot.allAppsExcludedPackages,
            installedTargetablePackages = installedTargetablePackages,
            hardProtectedPackages = hardProtectedPackages
        )
        val emergencyUnlockExpiresAtMs = emergencyStates
            .mapNotNull { it.activeUntilEpochMs?.takeIf { untilMs -> untilMs > nowMs } }
            .minOrNull()
        val hasActivePolicyRefreshNeed = scheduleState.active ||
            scheduleState.strictActive ||
            strictInstallBlocked.isNotEmpty() ||
            evaluation.effectiveBlockedPackages.isNotEmpty() ||
            (scheduleState.strictActive && activeScheduleTargets.diagnosticCode != ScheduleTargetDiagnosticCode.NONE)
        val activeEnforcementRefreshAtMs = if (hasActivePolicyRefreshNeed) {
            nowMs + ACTIVE_ENFORCEMENT_REFRESH_MS
        } else {
            null
        }
        val nextPolicyWake = planNextPolicyWake(
            nowMs = nowMs,
            scheduleNextTransitionAtMs = scheduleDecision.nextTransitionAt?.toInstant()?.toEpochMilli(),
            budgetNextCheckAtMs = budgetNextCheckAtMs,
            emergencyUnlockExpiresAtMs = emergencyUnlockExpiresAtMs,
            touchGrassBreakUntilMs = null,
            reliabilityFallbackAtMs = if (hasActivePolicyRefreshNeed) null else nowMs + RELIABILITY_TICK_INTERVAL_MS,
            activeEnforcementRefreshAtMs = activeEnforcementRefreshAtMs
        )
        return OrchestratedState(
            nowMs = nowMs,
            usageSnapshotStatus = usageSnapshot.status,
            usageAccessGranted = usageSnapshot.status.usageAccessGranted,
            usageAccessComplianceState = usageAccessComplianceState,
            accessibilityComplianceState = accessibilityComplianceState,
            recoveryLockdownState = recoveryLockdownState,
            nextCheckAtMs = budgetNextCheckAtMs,
            nextPolicyWakeAtMs = nextPolicyWake.atMs,
            nextPolicyWakeReason = nextPolicyWake.reason,
            scheduleDecision = scheduleDecision,
            scheduledGroupIds = activeScheduleTargets.scheduledGroupIds,
            scheduledAppPackages = activeScheduleTargets.scheduledAppPackages,
            scheduleState = scheduleState,
            scheduleTargetWarning = activeScheduleTargets.warning,
            scheduleTargetDiagnosticCode = activeScheduleTargets.diagnosticCode,
            policyControls = policyControls,
            protectionSnapshot = protectionSnapshot,
            installedTargetablePackages = installedTargetablePackages,
            hardProtectedPackages = hardProtectedPackages,
            evaluation = evaluation
        )
    }

    private suspend fun computeLiveScheduleTargetWarning(now: ZonedDateTime): String? {
        return withContext(Dispatchers.IO) {
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

    private suspend fun persistComputedState(orchestrated: OrchestratedState): EnforcementStateEntity {
        val suspendPlan = computeSuspendPlan(
            evaluation = orchestrated.evaluation,
            alwaysBlockedPackages = orchestrated.policyControls.alwaysBlockedInstalledPackages,
            fullyExemptPackages = orchestrated.protectionSnapshot.fullyExemptPackages,
            usageAccessComplianceState = orchestrated.usageAccessComplianceState,
            accessibilityComplianceState = orchestrated.accessibilityComplianceState,
            recoveryLockdownState = orchestrated.recoveryLockdownState
        )
        val previousState = loadEnforcementState()
        val strictInstallEncoded = when {
            !orchestrated.scheduleState.strictActive || orchestrated.evaluation.activeAllAppsGroupIds.isNotEmpty() -> ""
            else -> previousState.strictInstallSuspendedPackagesEncoded
        }
        val enforcementState = previousState.copy(
            scheduleLockComputed = orchestrated.scheduleState.active,
            scheduleStrictComputed = orchestrated.scheduleState.strictActive,
            scheduleBlockedGroupsEncoded = EnforcementStateCodecs.encodeStringSet(orchestrated.scheduleState.blockedGroupIds),
            scheduleBlockedPackagesEncoded = EnforcementStateCodecs.encodeStringSet(suspendPlan.scheduleBlockedPackages),
            strictInstallSuspendedPackagesEncoded = strictInstallEncoded,
            scheduleLockReason = orchestrated.scheduleState.reason,
            scheduleTargetWarning = orchestrated.scheduleTargetWarning,
            scheduleTargetDiagnosticCode = orchestrated.scheduleTargetDiagnosticCode.name,
            scheduleNextTransitionAtMs = orchestrated.scheduleDecision.nextTransitionAt?.toInstant()?.toEpochMilli(),
            budgetBlockedPackagesEncoded = EnforcementStateCodecs.encodeStringSet(suspendPlan.suspendTargets),
            budgetBlockedGroupIdsEncoded = EnforcementStateCodecs.encodeStringSet(orchestrated.evaluation.effectiveBlockedGroupIds),
            budgetReason = orchestrated.recoveryLockdownState.reason
                ?: orchestrated.usageAccessComplianceState.reason
                ?: orchestrated.evaluation.usageReasonSummary
                ?: when (orchestrated.usageSnapshotStatus) {
                    UsageSnapshotStatus.ACCESS_MISSING -> "Usage access not granted"
                    UsageSnapshotStatus.INGESTION_FAILED -> "Usage ingestion failed; using last known snapshot if available"
                    UsageSnapshotStatus.OK -> null
                },
            budgetUsageSnapshotStatus = orchestrated.usageSnapshotStatus.name,
            budgetUsageAccessGranted = orchestrated.usageSnapshotStatus.usageAccessGranted,
            budgetNextCheckAtMs = orchestrated.nextCheckAtMs,
            nextPolicyWakeAtMs = orchestrated.nextPolicyWakeAtMs,
            nextPolicyWakeReason = orchestrated.nextPolicyWakeReason,
            primaryReasonByPackageEncoded = EnforcementStateCodecs.encodeStringMap(suspendPlan.primaryReasons),
            blockReasonsByPackageEncoded = EnforcementStateCodecs.encodeReasonSetMap(suspendPlan.blockReasonsByPackage),
            computedSnapshotVersion = previousState.computedSnapshotVersion + 1
        )
        persistEnforcementState(enforcementState)
        if (
            orchestrated.recoveryLockdownState.active &&
            orchestrated.recoveryLockdownState.reason?.contains("No Settings package resolved") == true
        ) {
            FocusLog.w(
                appContext,
                FocusEventId.POLICY_STATE_COMPUTED,
                "Recovery lockdown active without resolved Settings or launcher packages"
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
        return enforcementState
    }

    private fun computeSuspendPlan(
        evaluation: EffectivePolicyEvaluation,
        alwaysBlockedPackages: Set<String>,
        fullyExemptPackages: Set<String>,
        usageAccessComplianceState: UsageAccessComplianceState,
        accessibilityComplianceState: AccessibilityComplianceState,
        recoveryLockdownState: RecoveryLockdownState
    ): SuspendPlan {
        if (recoveryLockdownState.active) {
            val recoverySuspendable = linkedSetOf<String>().apply {
                if (accessibilityComplianceState.lockdownActive) {
                    addAll(resolver.computeAccessibilityRecoverySuspendTargets(recoveryLockdownState.allowlist))
                }
                if (usageAccessComplianceState.lockdownActive) {
                    addAll(resolver.computeUsageAccessRecoverySuspendTargets(recoveryLockdownState.allowlist))
                }
            }
            val reasonMap = recoverySuspendable.associateWith { recoveryLockdownState.reasonTokens }
            return SuspendPlan(
                scheduleBlockedPackages = emptySet(),
                suspendTargets = recoverySuspendable,
                primaryReasons = BlockReasonUtils.derivePrimaryByPackage(reasonMap),
                blockReasonsByPackage = reasonMap
            )
        }
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

    private fun computeExternalState(nowMs: Long = clock.nowMs()): ExternalState = runBlocking {
        computeExternalStateAsync(nowMs)
    }

    private suspend fun computeExternalStateAsync(nowMs: Long = clock.nowMs()): ExternalState {
        val policyControls = loadPolicyControlsAsync()
        val usageAccessComplianceState = currentUsageAccessComplianceState()
        val accessibilityState = AccessibilityStatusMonitor.currentState.value.let { state ->
            if (state.lastCheckAtMs > 0L) {
                state
            } else {
                AccessibilityStatusMonitor.refreshNow(appContext, "external_state")
            }
        }
        val accessibilityComplianceState = currentAccessibilityComplianceState(
            accessibilityState = accessibilityState,
            nowMs = nowMs
        )
        val recoveryLockdownState = buildRecoveryLockdownState(
            usageAccessComplianceState = usageAccessComplianceState,
            accessibilityComplianceState = accessibilityComplianceState
        )
        val protectionSnapshot = buildAppProtectionSnapshot(policyControls, recoveryLockdownState)
        val enforcementState = loadEnforcementState()
        val scheduleComputed = enforcementState.scheduleLockComputed
        val strictActive = enforcementState.scheduleStrictComputed
        val strictInstallBlocked = if (strictActive) {
            EnforcementStateCodecs.decodeStringSet(enforcementState.strictInstallSuspendedPackagesEncoded)
        } else {
            emptySet()
        }
        val storedReasonSets = EnforcementStateCodecs.decodeReasonSetMap(enforcementState.blockReasonsByPackageEncoded)
        val budgetBlockedCandidates = (
            if (storedReasonSets.isNotEmpty()) {
                storedReasonSets.keys
            } else {
                EnforcementStateCodecs.decodeStringSet(enforcementState.budgetBlockedPackagesEncoded) +
                    EnforcementStateCodecs.decodeStringSet(enforcementState.scheduleBlockedPackagesEncoded)
            }
        )
        val budgetBlocked = resolver.filterSuspendable(
            packages = budgetBlockedCandidates,
            allowlist = protectionSnapshot.fullyExemptPackages
        )
        val primaryReason = EnforcementStateCodecs.decodeStringMap(enforcementState.primaryReasonByPackageEncoded).ifEmpty { computePrimaryReasonByPackage(
            alwaysBlocked = policyControls.alwaysBlockedInstalledPackages,
            budgetBlocked = budgetBlocked,
            scheduleBlocked = EnforcementStateCodecs.decodeStringSet(enforcementState.scheduleBlockedPackagesEncoded),
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
        val scheduleReason = enforcementState.scheduleLockReason
        val lockReason = when {
            recoveryLockdownState.active -> recoveryLockdownState.reason
            strictActive -> scheduleReason ?: "Strict scheduled block active"
            scheduleComputed -> scheduleReason ?: "Scheduled block active"
            budgetBlocked.isNotEmpty() -> enforcementState.budgetReason ?: "Policy limits exceeded"
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
            usageAccessComplianceState = usageAccessComplianceState,
            accessibilityState = accessibilityState,
            accessibilityComplianceState = accessibilityComplianceState,
            recoveryLockdownState = recoveryLockdownState
        )
    }

    private fun buildPackageDiagnostics(
        nowMs: Long,
        expected: PolicyState,
        external: ExternalState,
        scheduleBlockedPackages: Set<String>,
        protectionSnapshot: AppProtectionSnapshot,
        strictInstallStored: Set<String>,
        scheduleNextTransitionAtMs: Long?,
        budgetNextCheckAtMs: Long?
    ): List<PackageDiagnostics> {
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
                    if (
                        packageName in expected.suspendTargets &&
                        expected.primaryReasonByPackage[packageName] == EffectiveBlockReason.ACCESSIBILITY_RECOVERY_LOCKDOWN.name
                    ) {
                        add(EffectiveBlockReason.ACCESSIBILITY_RECOVERY_LOCKDOWN.name)
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
                        scheduleNextTransitionAtMs = scheduleNextTransitionAtMs,
                        budgetNextCheckAtMs = budgetNextCheckAtMs
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
            normalizedPrimary == EffectiveBlockReason.ACCESSIBILITY_RECOVERY_LOCKDOWN.name ||
                normalizedReasons.any { it.contains(EffectiveBlockReason.ACCESSIBILITY_RECOVERY_LOCKDOWN.name) } ->
                "Restore Accessibility; settings observer, service reconnect, or poll alarm will recompute"
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
        val usageAccessComplianceState: UsageAccessComplianceState,
        val accessibilityState: com.ankit.destination.enforce.AccessibilityMonitorState,
        val accessibilityComplianceState: AccessibilityComplianceState,
        val recoveryLockdownState: RecoveryLockdownState
    )

    private data class TrackedPackages(
        val previouslySuspended: Set<String>,
        val previouslyUninstallProtected: Set<String>
    )

    private data class NormalizedGroupPolicy(
        val groupId: String,
        val priorityIndex: Int,
        val strictInstallParticipates: Boolean,
        val targetMode: GroupTargetMode,
        val dailyLimitMs: Long?,
        val hourlyLimitMs: Long?,
        val opensPerDay: Int?
    )

    private data class NormalizedAppPolicy(
        val packageName: String,
        val dailyLimitMs: Long?,
        val hourlyLimitMs: Long?,
        val opensPerDay: Int?
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
        val warning: String?,
        val diagnosticCode: ScheduleTargetDiagnosticCode
    )

    private data class OrchestratedState(
        val nowMs: Long,
        val usageSnapshotStatus: UsageSnapshotStatus,
        val usageAccessGranted: Boolean,
        val usageAccessComplianceState: UsageAccessComplianceState,
        val accessibilityComplianceState: AccessibilityComplianceState,
        val recoveryLockdownState: RecoveryLockdownState,
        val nextCheckAtMs: Long?,
        val nextPolicyWakeAtMs: Long?,
        val nextPolicyWakeReason: String?,
        val scheduleDecision: ScheduleDecision,
        val scheduledGroupIds: Set<String>,
        val scheduledAppPackages: Set<String>,
        val scheduleState: LiveScheduleState,
        val scheduleTargetWarning: String?,
        val scheduleTargetDiagnosticCode: ScheduleTargetDiagnosticCode,
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
        recoveryLockdownState: RecoveryLockdownState
    ): AppProtectionSnapshot {
        val runtimeAllowlist = if (recoveryLockdownState.active) {
            AllowlistResolution(
                packages = recoveryLockdownState.allowlist,
                reasons = recoveryLockdownState.allowlistReasons
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

    private fun buildRecoveryLockdownState(
        usageAccessComplianceState: UsageAccessComplianceState,
        accessibilityComplianceState: AccessibilityComplianceState
    ): RecoveryLockdownState {
        if (!usageAccessComplianceState.lockdownActive && !accessibilityComplianceState.lockdownActive) {
            return RecoveryLockdownState(
                active = false,
                allowlist = emptySet(),
                allowlistReasons = emptyMap(),
                reasonTokens = emptySet(),
                reason = null
            )
        }
        val allowlistReasons = linkedMapOf<String, LinkedHashSet<String>>()
        fun addAllowlistReason(packageName: String, reason: String) {
            val normalizedPackage = packageName.trim()
            if (normalizedPackage.isBlank() || reason.isBlank()) return
            allowlistReasons.getOrPut(normalizedPackage) { linkedSetOf() }.add(reason)
        }
        if (usageAccessComplianceState.lockdownActive) {
            usageAccessComplianceState.recoveryAllowlist.forEach { pkg ->
                addAllowlistReason(pkg, "usage access recovery")
            }
        }
        if (accessibilityComplianceState.lockdownActive) {
            accessibilityComplianceState.recoveryAllowlist.forEach { pkg ->
                addAllowlistReason(pkg, "accessibility recovery")
            }
        }
        val reasonTokens = linkedSetOf<String>().apply {
            if (accessibilityComplianceState.lockdownActive) {
                add(EffectiveBlockReason.ACCESSIBILITY_RECOVERY_LOCKDOWN.name)
            }
            if (usageAccessComplianceState.lockdownActive) {
                add(EffectiveBlockReason.USAGE_ACCESS_RECOVERY_LOCKDOWN.name)
            }
        }
        val reason = buildList {
            accessibilityComplianceState.reason?.takeIf(String::isNotBlank)?.let(::add)
            usageAccessComplianceState.reason?.takeIf(String::isNotBlank)?.let(::add)
        }.distinct().joinToString(". ").ifBlank { null }
        return RecoveryLockdownState(
            active = true,
            allowlist = allowlistReasons.keys,
            allowlistReasons = allowlistReasons.mapValues { (_, reasons) -> reasons.joinToString(" + ") },
            reasonTokens = reasonTokens,
            reason = reason
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
        return GroupTargetMode.fromStorage(limit.scheduleTargetMode)
    }

    private fun normalizeGroupPolicy(limit: GroupLimit): NormalizedGroupPolicy {
        return NormalizedGroupPolicy(
            groupId = limit.groupId,
            priorityIndex = limit.priorityIndex,
            strictInstallParticipates = limit.strictEnabled,
            targetMode = resolveScheduleTargetMode(limit),
            dailyLimitMs = normalizeBudgetLimit(limit.dailyLimitMs),
            hourlyLimitMs = normalizeBudgetLimit(limit.hourlyLimitMs),
            opensPerDay = normalizeBudgetCount(limit.opensPerDay)
        )
    }

    private fun normalizeAppPolicy(policy: AppPolicy): NormalizedAppPolicy {
        return NormalizedAppPolicy(
            packageName = policy.packageName,
            dailyLimitMs = normalizeBudgetLimit(policy.dailyLimitMs),
            hourlyLimitMs = normalizeBudgetLimit(policy.hourlyLimitMs),
            opensPerDay = normalizeBudgetCount(policy.opensPerDay)
        )
    }

    private fun normalizeBudgetLimit(rawValue: Long): Long? {
        return rawValue.takeIf { it > 0L && it != Long.MAX_VALUE }
    }

    private fun normalizeBudgetCount(rawValue: Int): Int? {
        return rawValue.takeIf { it > 0 && it != Int.MAX_VALUE }
    }

    private fun parseUsageSnapshotStatus(raw: String?, fallbackGranted: Boolean): UsageSnapshotStatus {
        return raw?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { value -> runCatching { UsageSnapshotStatus.valueOf(value) }.getOrNull() }
            ?: UsageSnapshotStatus.fromUsageAccessGranted(fallbackGranted)
    }


    companion object {
        // Coalesce/serialize policy applications across BootReceiver + UI to avoid interleaving DPM calls.
        private val APPLY_LOCK = Any()
        private val APPLY_MUTEX = Mutex()


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

        internal fun isRecoveryLockdownEligible(
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

        internal fun isUsageAccessLockdownEligible(
            deviceOwnerActive: Boolean,
            provisioningFinalizationState: String?,
            hasSuccessfulPolicyApply: Boolean,
            hasAnyPriorApply: Boolean = hasSuccessfulPolicyApply
        ): Boolean {
            return isRecoveryLockdownEligible(
                deviceOwnerActive = deviceOwnerActive,
                provisioningFinalizationState = provisioningFinalizationState,
                hasSuccessfulPolicyApply = hasSuccessfulPolicyApply,
                hasAnyPriorApply = hasAnyPriorApply
            )
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

        internal fun resolveStrictScheduleActive(scheduleStrictActive: Boolean): Boolean {
            return scheduleStrictActive
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
            val diagnosticCode = when {
                warningBlockIds.isEmpty() -> ScheduleTargetDiagnosticCode.NONE
                rawTargetedActiveBlockIds.isEmpty() -> ScheduleTargetDiagnosticCode.NO_CONFIGURED_TARGETS
                else -> ScheduleTargetDiagnosticCode.NO_EFFECTIVE_TARGETS
            }
            return ActiveScheduleTargets(
                targetedActiveBlockIds = targetedActiveBlockIds,
                scheduledGroupIds = scheduledGroupIds,
                scheduledAppPackages = scheduledAppPackages,
                warning = warning,
                diagnosticCode = diagnosticCode
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
            emergencyStates: List<EmergencyStateInput>,
            allAppsExcludedPackages: Set<String> = emptySet(),
            installedTargetablePackages: Set<String> = emptySet(),
            hardProtectedPackages: Set<String> = emptySet()
        ): Long? {
            val candidates = mutableListOf<Long>()
            if (hasBudgetBoundarySensitivePolicy(groupPolicies, appPolicies, emergencyStates)) {
                baseNextCheckAtMs?.let(candidates::add)
            }
            groupPolicies.forEach { group ->
                if (group.scheduleBlocked) return@forEach
                val eligibleTargets = EffectivePolicyEvaluator.resolveGroupTargets(
                    members = group.members,
                    targetMode = group.targetMode,
                    fullyExemptPackages = fullyExemptPackages,
                    allAppsExcludedPackages = allAppsExcludedPackages,
                    installedTargetablePackages = installedTargetablePackages,
                    hardProtectedPackages = hardProtectedPackages
                ).resolvedUsageTargets
                if (eligibleTargets.isEmpty()) return@forEach
                val usedDay = eligibleTargets.sumOf { usageInputs.usedTodayMs[it] ?: 0L }
                val usedHour = eligibleTargets.sumOf { usageInputs.usedHourMs[it] ?: 0L }
                if (group.dailyLimitMs != null && usedDay < group.dailyLimitMs) {
                    candidates += nowMs + (group.dailyLimitMs - usedDay)
                }
                if (group.hourlyLimitMs != null && usedHour < group.hourlyLimitMs) {
                    candidates += nowMs + (group.hourlyLimitMs - usedHour)
                }
            }
            appPolicies.forEach { policy ->
                if (policy.scheduleBlocked || fullyExemptPackages.contains(policy.packageName)) return@forEach
                val usedDay = usageInputs.usedTodayMs[policy.packageName] ?: 0L
                val usedHour = usageInputs.usedHourMs[policy.packageName] ?: 0L
                if (policy.dailyLimitMs != null && usedDay < policy.dailyLimitMs) {
                    candidates += nowMs + (policy.dailyLimitMs - usedDay)
                }
                if (policy.hourlyLimitMs != null && usedHour < policy.hourlyLimitMs) {
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
            activeEnforcementRefreshAtMs: Long? = null,
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
                activeEnforcementRefreshAtMs
                    ?.takeIf { it > nowMs }
                    ?.let { add(PolicyWakePlan(it, WAKE_REASON_ACTIVE_ENFORCEMENT_REFRESH)) }
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
            reliabilityFallbackAtMs: Long? = null,
            activeEnforcementRefreshAtMs: Long? = null
        ): Long? {
            return planNextPolicyWake(
                nowMs = nowMs,
                scheduleNextTransitionAtMs = scheduleNextTransitionAtMs,
                budgetNextCheckAtMs = budgetNextCheckAtMs,
                emergencyUnlockExpiresAtMs = emergencyUnlockExpiresAtMs,
                touchGrassBreakUntilMs = touchGrassBreakUntilMs,
                reliabilityFallbackAtMs = reliabilityFallbackAtMs,
                activeEnforcementRefreshAtMs = activeEnforcementRefreshAtMs,
                keepOverdueBudgetCheck = keepOverdueBudgetCheck
            ).atMs
        }

        private fun hasBudgetBoundarySensitivePolicy(
            groupPolicies: List<GroupPolicyInput>,
            appPolicies: List<AppPolicyInput>,
            emergencyStates: List<EmergencyStateInput>
        ): Boolean {
            if (emergencyStates.any { it.activeUntilEpochMs != null }) return true
            if (groupPolicies.any { it.dailyLimitMs != null || it.hourlyLimitMs != null || it.opensPerDay != null || it.emergencyConfig.enabled }) {
                return true
            }
            return appPolicies.any {
                it.dailyLimitMs != null || it.hourlyLimitMs != null || it.opensPerDay != null || it.emergencyConfig.enabled
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
        private const val WAKE_REASON_ACTIVE_ENFORCEMENT_REFRESH = "active_enforcement_refresh"
        private const val WAKE_REASON_POLICY_REPAIR = "policy_repair"
        private const val WAKE_REASON_RELIABILITY_TICK = "reliability_tick"
        internal const val ACTIVE_ENFORCEMENT_REFRESH_MS = 5L * 60_000L
        private const val RELIABILITY_TICK_INTERVAL_MS = 15L * 60_000L
    }
}












