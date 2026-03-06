package com.ankit.destination.policy

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.ankit.destination.data.FocusDatabase
import com.ankit.destination.data.GlobalControls
import com.ankit.destination.schedule.ScheduleDecision
import com.ankit.destination.vpn.FocusVpnService
import com.ankit.destination.vpn.VpnStatusStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class PolicyEngine(context: Context) {
    private val appContext = context.applicationContext
    private val facade = DevicePolicyFacade(appContext)
    private val store = PolicyStore(appContext)
    private val resolver = PackageResolver(appContext)
    private val db by lazy { FocusDatabase.get(appContext) }
    private val evaluator = PolicyEvaluator(resolver)
    private val applier = PolicyApplier(facade)

    fun isDeviceOwner(): Boolean = facade.isDeviceOwner()

    fun getDesiredMode(): ModeState = store.getDesiredMode()

    fun getManualMode(): ModeState = store.getManualMode()

    fun isScheduleLockActive(): Boolean = store.isScheduleLockEnforced()

    fun isStrictScheduleActive(): Boolean = store.isScheduleStrictEnforced()

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

    fun getTouchGrassThreshold(): Int = store.getTouchGrassThreshold()

    fun getTouchGrassBreakMinutes(): Int = store.getTouchGrassBreakMinutes()

    fun setTouchGrassConfig(threshold: Int, breakMinutes: Int) {
        store.setTouchGrassThreshold(threshold)
        store.setTouchGrassBreakMinutes(breakMinutes)
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

    fun setGlobalControls(controls: GlobalControls) {
        runBlocking {
            withContext(Dispatchers.IO) {
                db.budgetDao().upsertGlobalControls(controls.copy(id = 1))
            }
        }
    }

    fun getAlwaysAllowedApps(): Set<String> = loadPolicyControls().alwaysAllowedApps

    fun getAlwaysBlockedApps(): Set<String> = loadPolicyControls().alwaysBlockedApps

    fun getUninstallProtectedApps(): Set<String> = loadPolicyControls().uninstallProtectedApps

    fun addAlwaysAllowedApp(packageName: String) {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return
        runBlocking {
            withContext(Dispatchers.IO) {
                db.budgetDao().addAlwaysAllowedExclusive(normalized)
            }
        }
    }

    fun removeAlwaysAllowedApp(packageName: String) {
        runBlocking {
            withContext(Dispatchers.IO) {
                db.budgetDao().deleteAlwaysAllowed(packageName.trim())
            }
        }
    }

    fun addAlwaysBlockedApp(packageName: String) {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return
        runBlocking {
            withContext(Dispatchers.IO) {
                db.budgetDao().addAlwaysBlockedExclusive(normalized)
            }
        }
    }

    fun removeAlwaysBlockedApp(packageName: String) {
        runBlocking {
            withContext(Dispatchers.IO) {
                db.budgetDao().deleteAlwaysBlocked(packageName.trim())
            }
        }
    }

    fun addUninstallProtectedApp(packageName: String) {
        val normalized = packageName.trim()
        if (normalized.isBlank() || normalized == appContext.packageName) return
        runBlocking {
            withContext(Dispatchers.IO) {
                db.budgetDao().upsertUninstallProtected(
                    com.ankit.destination.data.UninstallProtectedApp(normalized)
                )
            }
        }
    }

    fun removeUninstallProtectedApp(packageName: String) {
        runBlocking {
            withContext(Dispatchers.IO) {
                db.budgetDao().deleteUninstallProtected(packageName.trim())
            }
        }
    }

    fun setMode(mode: ModeState, hostActivity: Activity? = null, reason: String = "manual"): EngineResult {
        synchronized(APPLY_LOCK) {
            val previousEffectiveMode = store.getDesiredMode()
            val previousManualMode = store.getManualMode()
            val previousLockReason = store.getCurrentLockReason()
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
            if (mode == ModeState.NORMAL && isTouchGrassBreakEnforced()) {
                return failureResult(
                    message = "Touch Grass break is active and cannot be canceled.",
                    stateMode = previousEffectiveMode,
                    emergencyApps = emergencyApps
                )
            }

            store.setManualMode(mode)
            val external = computeExternalState()
            val result = applyModeInternal(
                targetMode = external.effectiveMode,
                hostActivity = hostActivity,
                reason = "manual:$reason",
                rollbackMode = previousEffectiveMode,
                rollbackLockReason = previousLockReason,
                emergencyApps = emergencyApps,
                external = external
            )
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
        synchronized(APPLY_LOCK) {
            val emergencyApps = getEmergencyApps()
            val previousEffective = store.getDesiredMode()
            val previousEnforced = store.isScheduleLockEnforced()
            val previousStrict = store.isScheduleStrictEnforced()
            val previousLockReason = store.getCurrentLockReason()
            val nextTransitionMs = decision.nextTransitionAt?.toInstant()?.toEpochMilli()
            store.setScheduleComputedState(
                active = decision.shouldLock,
                strictActive = decision.strictActive,
                blockedGroups = decision.blockedGroupIds,
                reason = decision.reason,
                nextTransitionAtMs = nextTransitionMs
            )
            store.setScheduleBlockedPackages(scheduleBlockedPackages)

            val external = computeExternalState()
            val result = applyModeInternal(
                targetMode = external.effectiveMode,
                hostActivity = hostActivity,
                reason = "schedule:$trigger",
                rollbackMode = previousEffective,
                rollbackLockReason = previousLockReason,
                emergencyApps = emergencyApps,
                external = external
            )
            val enforced = if (result.success) {
                decision.shouldLock
            } else {
                previousEnforced && store.getDesiredMode() == ModeState.NUCLEAR
            }
            store.setScheduleEnforced(enforced)
            val strictEnforced = if (result.success) {
                decision.strictActive
            } else {
                previousStrict && store.getDesiredMode() == ModeState.NUCLEAR
            }
            store.setScheduleStrictEnforced(strictEnforced)
            if (result.success) {
                store.setScheduleBlockedGroups(decision.blockedGroupIds)
                store.setScheduleBlockedPackages(scheduleBlockedPackages)
                if (previousStrict && !decision.strictActive) {
                    store.clearStrictInstallSuspendedPackages()
                }
            }
            return result
        }
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
        synchronized(APPLY_LOCK) {
            val previousMode = store.getDesiredMode()
            val previousLockReason = store.getCurrentLockReason()
            val emergencyApps = getEmergencyApps()
            val effectiveBlocked = if (usageAccessGranted) {
                blockedPackages
            } else {
                store.getBudgetBlockedPackages()
            }
            val effectiveBlockedGroups = if (usageAccessGranted) {
                blockedGroupIds
            } else {
                store.getBudgetBlockedGroupIds()
            }
            val effectiveReason = if (usageAccessGranted) {
                reason
            } else {
                "Usage access missing; keeping previous budget blocks"
            }
            store.setBudgetState(
                blockedPackages = effectiveBlocked,
                blockedGroupIds = effectiveBlockedGroups,
                reason = effectiveReason,
                usageAccessGranted = usageAccessGranted,
                nextCheckAtMs = nextCheckAtMs
            )
            val external = computeExternalState()
            return applyModeInternal(
                targetMode = external.effectiveMode,
                hostActivity = hostActivity,
                reason = "budget:$trigger",
                rollbackMode = previousMode,
                rollbackLockReason = previousLockReason,
                emergencyApps = emergencyApps,
                external = external
            )
        }
    }

    fun shouldRunBudgetEvaluation(nowMs: Long = System.currentTimeMillis(), graceMs: Long = 15_000L): Boolean {
        return isBudgetEvaluationDue(nowMs, store.getBudgetNextCheckAtMs(), graceMs)
    }

    fun reapplyDesiredMode(hostActivity: Activity? = null, reason: String = "reapply"): EngineResult {
        synchronized(APPLY_LOCK) {
            val emergencyApps = getEmergencyApps()
            val previousMode = store.getDesiredMode()
            val previousLockReason = store.getCurrentLockReason()

            if (!isDeviceOwner()) {
                return failureResult(
                    message = "Not device owner",
                    stateMode = previousMode,
                    emergencyApps = emergencyApps
                )
            }
            val external = computeExternalState()
            return applyModeInternal(
                targetMode = external.effectiveMode,
                hostActivity = hostActivity,
                reason = "reapply:$reason",
                rollbackMode = previousMode,
                rollbackLockReason = previousLockReason,
                emergencyApps = emergencyApps,
                external = external
            )
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
            uninstallProtectedApps = external.policyControls.uninstallProtectedApps,
            globalControls = external.policyControls.globalControls,
            previouslySuspended = trackedPackages.previouslySuspended,
            previouslyUninstallProtected = trackedPackages.previouslyUninstallProtected,
            budgetBlockedPackages = external.budgetBlockedPackages,
            primaryReasonByPackage = external.primaryReasonByPackage,
            lockReason = external.lockReason,
            touchGrassBreakActive = external.touchGrassBreakActive
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
            uninstallProtectedApps = external.policyControls.uninstallProtectedApps,
            globalControls = external.policyControls.globalControls,
            previouslySuspended = trackedPackages.previouslySuspended,
            previouslyUninstallProtected = trackedPackages.previouslyUninstallProtected,
            budgetBlockedPackages = external.budgetBlockedPackages,
            primaryReasonByPackage = external.primaryReasonByPackage,
            lockReason = external.lockReason,
            touchGrassBreakActive = external.touchGrassBreakActive
        )
        val restrictions = FocusConfig.nuclearRestrictions().associateWith { facade.hasUserRestriction(it) }
        val scheduleReason = store.getScheduleLockReason()
        val scheduleComputed = store.isScheduleLockComputed()
        val scheduleActive = store.isScheduleLockEnforced()
        val scheduleStrictComputed = store.isScheduleStrictComputed()
        val scheduleStrictActive = store.isScheduleStrictEnforced()
        return DiagnosticsSnapshot(
            deviceOwner = isDeviceOwner(),
            desiredMode = desiredMode,
            manualMode = store.getManualMode(),
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
            touchGrassBreakActive = external.touchGrassBreakActive,
            touchGrassBreakUntilMs = store.getTouchGrassBreakUntilMs(),
            unlockCountToday = store.getUnlockCountToday(),
            unlockCountDay = store.getUnlockCountDay(),
            touchGrassThreshold = store.getTouchGrassThreshold(),
            touchGrassBreakMinutes = store.getTouchGrassBreakMinutes(),
            lockTaskPackages = runCatching { facade.getLockTaskPackages().toSet() }.getOrDefault(emptySet()),
            lockTaskFeatures = runCatching { facade.getLockTaskFeatures() }.getOrNull() ?: expected.lockTaskFeatures,
            statusBarDisabled = expected.statusBarDisabled,
            lastAppliedAtMs = store.getLastAppliedAtMs(),
            lastVerificationPassed = store.getLastVerifyPassed(),
            lastError = store.getLastError(),
            lastSuspendedPackages = store.getLastSuspendedPackages(),
            restrictions = restrictions,
            vpnActive = isVpnActive(),
            vpnRequiredForNuclear = FocusConfig.requireVpnForNuclear,
            vpnLockdownRequired = FocusConfig.enforceAlwaysOnVpnLockdown,
            vpnLastError = VpnStatusStore(appContext).getLastError(),
            alwaysOnVpnPackage = runCatching { facade.getAlwaysOnVpnPackage() }.getOrNull(),
            alwaysOnVpnLockdown = runCatching { facade.isAlwaysOnVpnLockdownEnabled() }.getOrNull(),
            domainRuleCount = VpnStatusStore(appContext).getDomainRuleCount(),
            currentLockReason = store.getCurrentLockReason(),
            emergencyApps = getEmergencyApps(),
            allowlistReasons = store.getLastAllowlistReasons(),
            alwaysAllowedApps = external.policyControls.alwaysAllowedApps,
            alwaysBlockedApps = external.policyControls.alwaysBlockedApps,
            uninstallProtectedApps = external.policyControls.uninstallProtectedApps,
            globalControls = external.policyControls.globalControls,
            primaryReasonByPackage = external.primaryReasonByPackage
        )
    }

    fun onNewPackageInstalledDuringStrictSchedule(packageName: String): Boolean {
        synchronized(APPLY_LOCK) {
            if (!isDeviceOwner()) return false
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
        if (!isDeviceOwner()) {
            return failureResult(
                message = "Not device owner",
                stateMode = rollbackMode,
                emergencyApps = emergencyApps
            )
        }
        val vpnRequiredForThisApply =
            targetMode == ModeState.NUCLEAR &&
                FocusConfig.requireVpnForNuclear &&
                external.lockReason == "Manual Nuclear mode"
        if (vpnRequiredForThisApply && !isVpnActive()) {
            return failureResult(
                message = "VPN is required before enabling Nuclear mode",
                stateMode = rollbackMode,
                emergencyApps = emergencyApps
            )
        }

        val trackedPackages = sanitizeTrackedPackages()
        val state = evaluator.evaluate(
            mode = targetMode,
            emergencyApps = emergencyApps,
            alwaysAllowedApps = external.policyControls.alwaysAllowedApps,
            alwaysBlockedApps = external.policyControls.alwaysBlockedInstalledPackages,
            uninstallProtectedApps = external.policyControls.uninstallProtectedApps,
            globalControls = external.policyControls.globalControls,
            previouslySuspended = trackedPackages.previouslySuspended,
            previouslyUninstallProtected = trackedPackages.previouslyUninstallProtected,
            budgetBlockedPackages = external.budgetBlockedPackages,
            primaryReasonByPackage = external.primaryReasonByPackage,
            lockReason = external.lockReason,
            touchGrassBreakActive = external.touchGrassBreakActive
        )
        store.setPrimaryReasonByPackage(external.primaryReasonByPackage)
        if (state.alwaysOnVpnLockdown && FocusVpnService.isPrepared(appContext)) {
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
            store.setDesiredMode(targetMode)
            store.setEmergencyApps(state.emergencyApps)
            store.setCurrentLockReason(external.lockReason)
            store.recordApply(state, applyResult, verification, null)
            FocusLog.i(FocusEventId.POLICY_APPLY_DONE, "Apply success mode=${state.mode}")
            return EngineResult(true, "Policy applied", verification, state)
        }

        val errorMessage = buildList {
            addAll(applyResult.errors)
            addAll(verification.issues)
            if (!vpnSatisfied) add("VPN not active")
        }.joinToString(" | ")
        store.recordApply(state, applyResult, verification, errorMessage)
        FocusLog.w(FocusEventId.MODE_CHANGE_FAIL, "Apply failed mode=$targetMode error=$errorMessage")

        val rollbackState = evaluator.evaluate(
            mode = rollbackMode,
            emergencyApps = emergencyApps,
            alwaysAllowedApps = external.policyControls.alwaysAllowedApps,
            alwaysBlockedApps = external.policyControls.alwaysBlockedInstalledPackages,
            uninstallProtectedApps = external.policyControls.uninstallProtectedApps,
            globalControls = external.policyControls.globalControls,
            previouslySuspended = trackedPackages.previouslySuspended,
            previouslyUninstallProtected = trackedPackages.previouslyUninstallProtected,
            budgetBlockedPackages = external.budgetBlockedPackages,
            primaryReasonByPackage = external.primaryReasonByPackage,
            lockReason = rollbackLockReason,
            touchGrassBreakActive = external.touchGrassBreakActive
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
        store.recordApply(rollbackState, rollbackApply, rollbackVerify, rollbackError)

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
            uninstallProtectedApps = external.policyControls.uninstallProtectedApps,
            globalControls = external.policyControls.globalControls,
            previouslySuspended = trackedPackages.previouslySuspended,
            previouslyUninstallProtected = trackedPackages.previouslyUninstallProtected,
            budgetBlockedPackages = external.budgetBlockedPackages,
            primaryReasonByPackage = external.primaryReasonByPackage,
            lockReason = external.lockReason,
            touchGrassBreakActive = external.touchGrassBreakActive
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

    private fun isTouchGrassBreakEnforced(): Boolean {
        return store.isTouchGrassBreakActive() && store.getDesiredMode() == ModeState.NUCLEAR
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

    private fun computeExternalState(nowMs: Long = System.currentTimeMillis()): ExternalState {
        val policyControls = loadPolicyControls()
        val breakUntil = store.getTouchGrassBreakUntilMs()
        if (breakUntil != null && breakUntil <= nowMs) {
            store.setTouchGrassBreakUntilMs(null)
        }
        val activeBreakUntil = store.getTouchGrassBreakUntilMs()
        val touchGrassActive = activeBreakUntil != null && activeBreakUntil > nowMs
        val scheduleComputed = store.isScheduleLockComputed()
        val scheduleBlockedGroups = store.getScheduleBlockedGroups()
        val strictActive = store.isScheduleStrictComputed()
        val strictInstallBlocked = if (strictActive) {
            store.getStrictInstallSuspendedPackages()
        } else {
            emptySet()
        }
        val budgetBlocked = combinedBlockedPackages(
            alwaysBlocked = policyControls.alwaysBlockedInstalledPackages,
            budgetBlocked = store.getBudgetBlockedPackages(),
            scheduleBlocked = store.getScheduleBlockedPackages(),
            strictInstallBlocked = strictInstallBlocked
        )
        val primaryReason = computePrimaryReasonByPackage(
            alwaysBlocked = policyControls.alwaysBlockedInstalledPackages,
            budgetBlocked = store.getBudgetBlockedPackages(),
            scheduleBlocked = store.getScheduleBlockedPackages(),
            strictInstallBlocked = strictInstallBlocked
        )
        val effectiveMode = resolveEffectiveMode(
            manualMode = store.getManualMode(),
            scheduleComputed = scheduleComputed,
            touchGrassBreakUntilMs = activeBreakUntil,
            nowMs = nowMs
        )
        val lockReason = when {
            scheduleComputed -> store.getScheduleLockReason() ?: "Scheduled lock active"
            scheduleBlockedGroups.isNotEmpty() -> store.getScheduleLockReason() ?: "Scheduled group block active"
            touchGrassActive -> {
                val untilText = DateFormat.getDateTimeInstance().format(Date(activeBreakUntil!!))
                "Touch Grass break until $untilText"
            }
            effectiveMode == ModeState.NUCLEAR -> "Manual Nuclear mode"
            budgetBlocked.isNotEmpty() -> store.getBudgetReason() ?: "Budget limits exceeded"
            else -> null
        }
        return ExternalState(
            effectiveMode = effectiveMode,
            budgetBlockedPackages = budgetBlocked,
            touchGrassBreakActive = touchGrassActive,
            lockReason = lockReason,
            policyControls = policyControls,
            primaryReasonByPackage = primaryReason
        )
    }

    private data class ExternalState(
        val effectiveMode: ModeState,
        val budgetBlockedPackages: Set<String>,
        val touchGrassBreakActive: Boolean,
        val lockReason: String?,
        val policyControls: PolicyControls,
        val primaryReasonByPackage: Map<String, String>
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

    private fun loadPolicyControls(): PolicyControls = runBlocking {
        withContext(Dispatchers.IO) {
            val dao = db.budgetDao()
            val global = dao.getGlobalControls() ?: GlobalControls()
            val alwaysAllowed = dao.getAlwaysAllowedPackages().toSet()
            val alwaysBlocked = dao.getAlwaysBlockedPackages().toSet()
            val uninstallProtected = dao.getUninstallProtectedPackages().toMutableSet().apply {
                add(appContext.packageName)
            }.toSet()
            val installedAlwaysBlocked = alwaysBlocked.filterTo(linkedSetOf()) { resolver.isPackageInstalled(it) }
            PolicyControls(
                globalControls = global,
                alwaysAllowedApps = alwaysAllowed,
                alwaysBlockedApps = alwaysBlocked,
                alwaysBlockedInstalledPackages = installedAlwaysBlocked,
                uninstallProtectedApps = uninstallProtected
            )
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

        fun isBudgetEvaluationDue(nowMs: Long, nextCheckAtMs: Long?, graceMs: Long = 15_000L): Boolean {
            val nextCheckAt = nextCheckAtMs ?: return true
            return nowMs + graceMs >= nextCheckAt
        }

        fun resolveEffectiveMode(
            manualMode: ModeState,
            scheduleComputed: Boolean,
            touchGrassBreakUntilMs: Long?,
            nowMs: Long
        ): ModeState {
            if (scheduleComputed) return ModeState.NUCLEAR
            if (touchGrassBreakUntilMs != null && touchGrassBreakUntilMs > nowMs) return ModeState.NUCLEAR
            return manualMode
        }
    }
}








