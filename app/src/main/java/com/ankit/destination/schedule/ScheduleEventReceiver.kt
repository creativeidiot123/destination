package com.ankit.destination.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ankit.destination.enforce.EnforcementExecutor
import com.ankit.destination.policy.FocusEventId
import com.ankit.destination.policy.FocusLog
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.usage.UsageAccessMonitor

class ScheduleEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val trigger = intent?.action ?: "ScheduleEvent"
        val pending = goAsync()
        EnforcementExecutor.executeLatest(
            key = EXECUTOR_KEY,
            onDropped = pending::finish
        ) {
            try {
                UsageAccessMonitor.refreshNow(
                    context = context,
                    reason = "schedule_event:$trigger",
                    // Receiver will apply policy explicitly; avoid duplicate apply via monitor.
                    requestPolicyRefreshIfChanged = false
                )
                val engine = PolicyEngine(context)
                if (!engine.isDeviceOwner()) return@executeLatest
                engine.requestApplyNow(reason = "ScheduleEventReceiver:$trigger")
            } catch (t: Throwable) {
                FocusLog.e(FocusEventId.SCHEDULE_ENFORCE_FAIL, "Schedule event handling failed trigger=$trigger", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        private const val EXECUTOR_KEY = "schedule-refresh"
    }
}
