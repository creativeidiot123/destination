package com.ankit.destination.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ankit.destination.enforce.EnforcementExecutor

class ScheduleEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val trigger = intent?.action ?: "ScheduleEvent"
        val pending = goAsync()
        EnforcementExecutor.execute {
            try {
                ScheduleEnforcer(context).enforceNow(trigger)
            } finally {
                pending.finish()
            }
        }
    }
}
