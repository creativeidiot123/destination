package com.ankit.destination.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

class AlarmScheduler(private val context: Context) {
    fun scheduleNextTransition(atMillis: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent()
        alarmManager.cancel(pi)
        val targetAtMs = atMillis.coerceAtLeast(System.currentTimeMillis() + MIN_ALARM_LEAD_MS)

        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        when {
            canExact -> alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetAtMs, pi)
            else -> alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetAtMs, pi)
        }
    }

    fun cancelNextTransition() {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(pendingIntent())
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, ScheduleTickReceiver::class.java).apply {
            action = ACTION_SCHEDULE_TICK
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val ACTION_SCHEDULE_TICK = "com.ankit.destination.schedule.TICK"
        private const val REQUEST_CODE = 1001
        private const val MIN_ALARM_LEAD_MS = 1_000L
    }
}
