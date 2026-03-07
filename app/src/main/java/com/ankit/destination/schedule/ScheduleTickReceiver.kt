package com.ankit.destination.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ankit.destination.enforce.PolicyApplyOrchestrator
import com.ankit.destination.usage.UsageAccessMonitor

class ScheduleTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: AlarmScheduler.ACTION_SCHEDULE_TICK
        val trigger = when (action) {
            AlarmScheduler.ACTION_USAGE_ACCESS_POLL -> "usage_access_poll"
            AlarmScheduler.ACTION_SCHEDULE_TICK -> "schedule_tick"
            else -> action
        }
        val pending = goAsync()
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
    }
}

