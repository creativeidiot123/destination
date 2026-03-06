package com.ankit.destination.touchgrass

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ankit.destination.enforce.EnforcementExecutor
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.schedule.ScheduleEnforcer

class UnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        EnforcementExecutor.execute {
            try {
                val nowMs = System.currentTimeMillis()
                TouchGrassController(context).onUserUnlock(nowMs)
                val policyEngine = PolicyEngine(context)
                val budgetDue = policyEngine.shouldRunBudgetEvaluation(nowMs)
                ScheduleEnforcer(context).enforceNow(
                    trigger = intent?.action ?: "UserPresent",
                    includeBudgets = budgetDue
                )
            } finally {
                pending.finish()
            }
        }
    }
}
