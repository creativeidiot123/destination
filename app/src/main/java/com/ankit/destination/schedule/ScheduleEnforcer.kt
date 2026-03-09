package com.ankit.destination.schedule

import android.app.Activity
import android.content.Context
import com.ankit.destination.enforce.PolicyApplyOrchestrator
import com.ankit.destination.policy.ApplyTrigger
import com.ankit.destination.policy.ApplyTriggerBatch
import com.ankit.destination.policy.ApplyTriggerCategory
import com.ankit.destination.policy.EngineResult
import com.ankit.destination.policy.FocusEventId
import com.ankit.destination.policy.FocusLog
import com.ankit.destination.policy.PolicyEngine

data class ScheduleEnforceResult(
    val policyResult: EngineResult
)

@Deprecated(
    message = "Use PolicyEngine.requestApplyNow(). ScheduleEnforcer previously duplicated alarm scheduling and " +
        "encouraged non-canonical policy application paths."
)
class ScheduleEnforcer(context: Context) {
    private val appContext = context.applicationContext
    private val policyEngine by lazy { PolicyEngine(appContext) }

    fun enforceNow(
        trigger: String,
        hostActivity: Activity? = null,
        includeBudgets: Boolean = true
    ): ScheduleEnforceResult {
        FocusLog.d(
            FocusEventId.SCHEDULE_EVAL,
            "ScheduleEnforcer.enforceNow() trigger=$trigger includeBudgets=$includeBudgets"
        )
        val startNs = System.nanoTime()
        val result = PolicyApplyOrchestrator.applyNowBlocking(
            context = appContext,
            triggerBatch = ApplyTriggerBatch.single(
                ApplyTrigger(
                    category = ApplyTriggerCategory.SCHEDULE,
                    source = "schedule_enforcer",
                    detail = trigger
                )
            ),
            hostActivity = hostActivity
        )
        val applyMs = (System.nanoTime() - startNs) / 1_000_000.0
        FocusLog.d(
            FocusEventId.SCHEDULE_EVAL,
            "policy applied in %.1fms success=${result.success}".format(applyMs)
        )
        FocusLog.d(FocusEventId.SCHEDULE_EVAL, "ScheduleEnforcer.enforceNow() done")
        return ScheduleEnforceResult(policyResult = result)
    }

    fun isScheduleLockActiveNow(): Boolean {
        val snapshot = policyEngine.diagnosticsSnapshot()
        val active = snapshot.scheduleBlockedGroups.isNotEmpty()
        FocusLog.v(
            FocusEventId.SCHEDULE_EVAL,
            "isScheduleLockActiveNow=$active blockedGroups=${snapshot.scheduleBlockedGroups.size}"
        )
        return active
    }
}
