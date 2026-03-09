package com.ankit.destination.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ankit.destination.enforce.AccessibilityStatusMonitor
import com.ankit.destination.enforce.PolicyApplyOrchestrator
import com.ankit.destination.policy.ApplyTrigger
import com.ankit.destination.policy.ApplyTriggerCategory
import com.ankit.destination.policy.FocusEventId
import com.ankit.destination.policy.FocusLog
import com.ankit.destination.usage.UsageAccessMonitor

class ScheduleEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val trigger = intent?.action ?: "ScheduleEvent"
        val pending = goAsync()
        try {
            AccessibilityStatusMonitor.refreshNow(
                context = context,
                reason = "schedule_event:$trigger"
            )
            UsageAccessMonitor.refreshNow(
                context = context,
                reason = "schedule_event:$trigger",
                requestPolicyRefreshIfChanged = false
            )
            PolicyApplyOrchestrator.requestApply(
                context = context,
                trigger = ApplyTrigger(
                    category = ApplyTriggerCategory.SCHEDULE,
                    source = "schedule_event_receiver",
                    detail = trigger
                ),
                onComplete = { pending.finish() }
            )
        } catch (t: Throwable) {
            FocusLog.e(FocusEventId.SCHEDULE_ENFORCE_FAIL, "Schedule event handling failed", t)
            pending.finish()
        }
    }
}
