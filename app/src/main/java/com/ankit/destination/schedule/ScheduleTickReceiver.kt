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
        val action = intent?.action ?: AlarmScheduler.ACTION_SCHEDULE_TICK
        val trigger = when (action) {
            AlarmScheduler.ACTION_USAGE_ACCESS_POLL -> "usage_access_poll"
            AlarmScheduler.ACTION_SCHEDULE_TICK -> "schedule_tick"
            else -> action
        }
        val pending = goAsync()
        EnforcementExecutor.executeLatest(
            key = EXECUTOR_KEY,
            onDropped = pending::finish
        ) {
            try {
                UsageAccessMonitor.refreshNow(
                    context = context,
                    reason = trigger,
                    // Receiver will apply policy explicitly; avoid duplicate apply via monitor.
                    requestPolicyRefreshIfChanged = false
                )
                val engine = PolicyEngine(context)
                if (!engine.isDeviceOwner()) return@executeLatest
                engine.requestApplyNow(reason = "ScheduleTickReceiver:$trigger")
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
