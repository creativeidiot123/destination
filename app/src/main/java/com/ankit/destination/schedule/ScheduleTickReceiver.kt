package com.ankit.destination.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ankit.destination.enforce.EnforcementExecutor
import com.ankit.destination.policy.FocusEventId
import com.ankit.destination.policy.FocusLog
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.usage.UsageAccessMonitor

class ScheduleTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        EnforcementExecutor.executeLatest(
            key = EXECUTOR_KEY,
            onDropped = pending::finish
        ) {
            try {
                UsageAccessMonitor.refreshNow(
                    context = context,
                    reason = "schedule_tick",
                    requestPolicyRefreshIfChanged = true
                )
                val includeBudgets = PolicyEngine(context).shouldRunBudgetEvaluation()
                ScheduleEnforcer(context).enforceNow(
                    trigger = "AlarmTick",
                    includeBudgets = includeBudgets
                )
            } catch (t: Throwable) {
                FocusLog.e(FocusEventId.SCHEDULE_ENFORCE_FAIL, "Schedule tick handling failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        private const val EXECUTOR_KEY = "schedule-refresh"
    }
}
