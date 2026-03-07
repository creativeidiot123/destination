package com.ankit.destination.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ankit.destination.policy.FocusEventId
import com.ankit.destination.policy.FocusLog

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
            canExact -> {
                runCatching {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetAtMs, pi)
                    FocusLog.d(FocusEventId.ALARM_SCHEDULE, "scheduleNextTransition: EXACT alarm at=$targetAtMs (in ${(targetAtMs - System.currentTimeMillis()) / 1000}s)")
                }.getOrElse { exactError ->
                    FocusLog.e(
                        context,
                        FocusEventId.SCHEDULE_ENFORCE_FAIL,
                        "Exact alarm scheduling failed; falling back to inexact alarm",
                        exactError
                    )
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetAtMs, pi)
                    FocusLog.d(FocusEventId.ALARM_SCHEDULE, "scheduleNextTransition: INEXACT fallback alarm at=$targetAtMs")
                }
            }
            else -> {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetAtMs, pi)
                FocusLog.d(FocusEventId.ALARM_SCHEDULE, "scheduleNextTransition: INEXACT alarm at=$targetAtMs (cannot schedule exact)")
            }
        }
    }

    fun cancelNextTransition() {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(pendingIntent())
        FocusLog.d(FocusEventId.ALARM_SCHEDULE, "cancelNextTransition: alarm cancelled")
    }

    fun scheduleUsageAccessPollIfNeeded(delayMs: Long = USAGE_ACCESS_POLL_DELAY_MS) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = usageAccessPollPendingIntent()
        alarmManager.cancel(pi)
        val triggerAtMs = System.currentTimeMillis() + delayMs
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        FocusLog.d(FocusEventId.ALARM_SCHEDULE, "scheduleUsageAccessPoll: scheduled in ${delayMs}ms")
    }

    fun cancelUsageAccessPoll() {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(usageAccessPollPendingIntent())
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

    private fun usageAccessPollPendingIntent(): PendingIntent {
        val intent = Intent(context, ScheduleTickReceiver::class.java).apply {
            action = ACTION_USAGE_ACCESS_POLL
        }
        return PendingIntent.getBroadcast(
            context,
            USAGE_ACCESS_POLL_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val ACTION_SCHEDULE_TICK = "com.ankit.destination.schedule.TICK"
        const val ACTION_USAGE_ACCESS_POLL = "com.ankit.destination.schedule.USAGE_ACCESS_POLL"
        private const val REQUEST_CODE = 1001
        private const val USAGE_ACCESS_POLL_REQUEST_CODE = 1002
        private const val MIN_ALARM_LEAD_MS = 1_000L
        private const val USAGE_ACCESS_POLL_DELAY_MS = 15_000L
    }
}
