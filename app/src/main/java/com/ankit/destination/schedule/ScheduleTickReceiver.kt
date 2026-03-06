package com.ankit.destination.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ankit.destination.enforce.EnforcementExecutor
import com.ankit.destination.policy.PolicyEngine

class ScheduleTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        EnforcementExecutor.execute {
            try {
                val includeBudgets = PolicyEngine(context).shouldRunBudgetEvaluation()
                ScheduleEnforcer(context).enforceNow(
                    trigger = "AlarmTick",
                    includeBudgets = includeBudgets
                )
            } finally {
                pending.finish()
            }
        }
    }
}
