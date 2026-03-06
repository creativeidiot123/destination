package com.ankit.destination.schedule

import android.app.Activity
import android.content.Context
import com.ankit.destination.budgets.BudgetEnforceResult
import com.ankit.destination.budgets.BudgetOrchestrator
import com.ankit.destination.data.FocusDatabase
import com.ankit.destination.data.ScheduleBlockGroup
import com.ankit.destination.policy.EngineResult
import com.ankit.destination.policy.ModeState
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.policy.PackageResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

data class ScheduleEnforceResult(
    val decision: ScheduleDecision,
    val budget: BudgetEnforceResult?,
    val policyResult: EngineResult
)

class ScheduleEnforcer(context: Context) {
    private val appContext = context.applicationContext
    private val db by lazy { FocusDatabase.get(appContext) }
    private val policyEngine by lazy { PolicyEngine(appContext) }
    private val alarmScheduler by lazy { AlarmScheduler(appContext) }
    private val budgetOrchestrator by lazy { BudgetOrchestrator(appContext) }
    private val packageResolver by lazy { PackageResolver(appContext) }

    suspend fun enforceNowAsync(
        trigger: String,
        hostActivity: Activity? = null,
        includeBudgets: Boolean = true
    ): ScheduleEnforceResult {
        val (enabledBlocks, blockGroups) = withContext(Dispatchers.IO) {
            val dao = db.scheduleDao()
            val blocks = dao.getEnabledBlocks()
            val groups = dao.getAllBlockGroups()
                .groupBy(ScheduleBlockGroup::blockId, ScheduleBlockGroup::groupId)
                .mapValues { it.value.toSet() }
            blocks to groups
        }
        val now = ZonedDateTime.now()
        val decision = ScheduleEvaluator.evaluate(now, enabledBlocks, blockGroups)
        val scheduleGroupPackages = if (decision.blockedGroupIds.isEmpty()) {
            emptySet()
        } else {
            withContext(Dispatchers.IO) {
                db.budgetDao().getPackagesForGroups(decision.blockedGroupIds.toList()).toSet()
            }
        }

        val scheduleResult = withContext(Dispatchers.Default) {
            policyEngine.applyScheduleDecision(decision, scheduleGroupPackages, trigger, hostActivity)
        }
        val emergencyApps = policyEngine.getEmergencyApps()
        val allowlist = packageResolver.resolveAllowlist(
            userChosenEmergencyApps = emergencyApps,
            alwaysAllowedApps = policyEngine.getAlwaysAllowedApps()
        ).packages
        val (budget, result) = if (includeBudgets) {
            val computedBudget = budgetOrchestrator.evaluateNow(now = now, emergencyAllowlist = allowlist)
            val budgetResult = withContext(Dispatchers.Default) {
                policyEngine.setBudgetState(
                    blockedPackages = computedBudget.blockedPackages,
                    blockedGroupIds = computedBudget.blockedGroupIds,
                    reason = computedBudget.reason,
                    usageAccessGranted = computedBudget.usageAccessGranted,
                    nextCheckAtMs = computedBudget.nextCheckAtMs,
                    hostActivity = hostActivity,
                    trigger = trigger
                )
            }
            computedBudget to budgetResult
        } else {
            val deferredReason = policyEngine.getBudgetReason() ?: "Budget evaluation deferred"
            val deferredBudget = BudgetEnforceResult(
                blockedPackages = policyEngine.getBudgetBlockedPackages(),
                blockedGroupIds = policyEngine.getBudgetBlockedGroupIds(),
                reason = deferredReason,
                reasons = listOf("Budget evaluation deferred"),
                usageAccessGranted = policyEngine.isBudgetUsageAccessGranted(),
                nextCheckAtMs = policyEngine.getBudgetNextCheckAtMs(),
                emergencyDailyBoostMsByGroup = emptyMap(),
                emergencyActiveUntilByGroup = emptyMap()
            )
            deferredBudget to scheduleResult
        }
        val snapshot = policyEngine.diagnosticsSnapshot()
        val nextAlarmAt = listOfNotNull(
            decision.nextTransitionAt?.toInstant()?.toEpochMilli(),
            snapshot.budgetNextCheckAtMs,
            snapshot.touchGrassBreakUntilMs
        ).minOrNull()
        nextAlarmAt?.let { alarmScheduler.scheduleNextTransition(it) } ?: alarmScheduler.cancelNextTransition()

        return ScheduleEnforceResult(
            decision = decision,
            budget = budget,
            policyResult = result
        )
    }

    fun enforceNow(
        trigger: String,
        hostActivity: Activity? = null,
        includeBudgets: Boolean = true
    ): ScheduleEnforceResult {
        return runBlocking {
            enforceNowAsync(
                trigger = trigger,
                hostActivity = hostActivity,
                includeBudgets = includeBudgets
            )
        }
    }

    fun isScheduleLockActiveNow(): Boolean {
        val snapshot = policyEngine.diagnosticsSnapshot()
        return snapshot.scheduleLockActive && snapshot.desiredMode == ModeState.NUCLEAR
    }
}

