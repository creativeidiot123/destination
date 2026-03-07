package com.ankit.destination.schedule

import android.app.Activity
import android.content.Context
import com.ankit.destination.policy.EngineResult
import com.ankit.destination.policy.FocusEventId
import com.ankit.destination.policy.FocusLog
import com.ankit.destination.policy.PolicyEngine

data class ScheduleEnforceResult(
    val policyResult: EngineResult
)

class ScheduleEnforcer(context: Context) {
    private val appContext = context.applicationContext
    private val policyEngine by lazy { PolicyEngine(appContext) }
    private val alarmScheduler by lazy { AlarmScheduler(appContext) }

    fun enforceNow(
        trigger: String,
        hostActivity: Activity? = null,
        includeBudgets: Boolean = true
    ): ScheduleEnforceResult {
        FocusLog.d(FocusEventId.SCHEDULE_EVAL, "┌── ScheduleEnforcer.enforceNow() trigger=$trigger includeBudgets=$includeBudgets")
        val startNs = System.nanoTime()
        val result = policyEngine.requestApplyNow(hostActivity = hostActivity, reason = trigger)
        val applyMs = (System.nanoTime() - startNs) / 1_000_000.0
        FocusLog.d(FocusEventId.SCHEDULE_EVAL, "│ policy applied in %.1fms success=${result.success}".format(applyMs))

        val snapshot = policyEngine.diagnosticsSnapshot()
        val nextAlarmAt = PolicyEngine.pickNextAlarmAtMs(
            nowMs = System.currentTimeMillis(),
            scheduleNextTransitionAtMs = snapshot.scheduleNextTransitionAtMs,
            budgetNextCheckAtMs = snapshot.budgetNextCheckAtMs,
            touchGrassBreakUntilMs = snapshot.touchGrassBreakUntilMs,
            keepOverdueBudgetCheck = includeBudgets && policyEngine.shouldRunBudgetEvaluation()
        )
        if (nextAlarmAt != null) {
            alarmScheduler.scheduleNextTransition(nextAlarmAt)
            FocusLog.d(FocusEventId.ALARM_SCHEDULE, "│ nextAlarm scheduled at=${nextAlarmAt} (in ${(nextAlarmAt - System.currentTimeMillis()) / 1000}s)")
        } else {
            alarmScheduler.cancelNextTransition()
            FocusLog.d(FocusEventId.ALARM_SCHEDULE, "│ no next alarm — cancelled")
        }
        FocusLog.d(FocusEventId.SCHEDULE_EVAL, "└── ScheduleEnforcer.enforceNow() done")
        return ScheduleEnforceResult(policyResult = result)
    }

    fun isScheduleLockActiveNow(): Boolean {
        val snapshot = policyEngine.diagnosticsSnapshot()
        val active = snapshot.scheduleBlockedGroups.isNotEmpty()
        FocusLog.v(FocusEventId.SCHEDULE_EVAL, "isScheduleLockActiveNow=$active blockedGroups=${snapshot.scheduleBlockedGroups.size}")
        return active
    }
}
