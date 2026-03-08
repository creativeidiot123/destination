package com.ankit.destination.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ankit.destination.enforce.AccessibilityStatusMonitor
import com.ankit.destination.enforce.PolicyApplyOrchestrator
import com.ankit.destination.policy.FocusEventId
import com.ankit.destination.policy.FocusLog
import com.ankit.destination.usage.UsageAccessMonitor

class ScheduleTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: AlarmScheduler.ACTION_POLICY_WAKE
        val trigger = when (action) {
            AlarmScheduler.ACTION_POLICY_WAKE -> "policy_wake"
            AlarmScheduler.ACTION_USAGE_ACCESS_POLL -> "usage_access_poll"
            AlarmScheduler.ACTION_RELIABILITY_TICK -> "reliability_tick"
            else -> action
        }
        val pending = goAsync()
        try {
            AccessibilityStatusMonitor.refreshNow(
                context = context,
                reason = trigger
            )
            UsageAccessMonitor.refreshNow(
                context = context,
                reason = trigger,
                requestPolicyRefreshIfChanged = false
            )
            PolicyApplyOrchestrator.requestApply(
                context = context,
                reason = "ScheduleTickReceiver:$trigger",
                onComplete = { pending.finish() }
            )
        } catch (t: Throwable) {
            FocusLog.e(FocusEventId.SCHEDULE_ENFORCE_FAIL, "Schedule tick handling failed", t)
            pending.finish()
        }
    }
}
