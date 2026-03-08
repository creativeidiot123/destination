package com.ankit.destination.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ankit.destination.policy.FocusEventId
import com.ankit.destination.policy.FocusLog

internal interface AlarmSchedulerClient {
    fun scheduleNextTransition(atMillis: Long)
    fun cancelNextTransition()
    fun scheduleReliabilityTick(atMillis: Long)
    fun cancelReliabilityTick()
}

internal class AlarmScheduler(private val context: Context) : AlarmSchedulerClient {
    override fun scheduleNextTransition(atMillis: Long) {
        scheduleAlarm(
            requestCode = POLICY_WAKE_REQUEST_CODE,
            action = ACTION_POLICY_WAKE,
            atMillis = atMillis,
            label = "scheduleNextTransition"
        )
    }

    override fun cancelNextTransition() {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(pendingIntent(ACTION_POLICY_WAKE, POLICY_WAKE_REQUEST_CODE))
        FocusLog.d(FocusEventId.ALARM_SCHEDULE, "cancelNextTransition: alarm cancelled")
    }

    override fun scheduleReliabilityTick(atMillis: Long) {
        scheduleAlarm(
            requestCode = RELIABILITY_REQUEST_CODE,
            action = ACTION_RELIABILITY_TICK,
            atMillis = atMillis,
            label = "scheduleReliabilityTick"
        )
    }

    override fun cancelReliabilityTick() {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(pendingIntent(ACTION_RELIABILITY_TICK, RELIABILITY_REQUEST_CODE))
        FocusLog.d(FocusEventId.ALARM_SCHEDULE, "cancelReliabilityTick: alarm cancelled")
    }

    fun scheduleUsageAccessPollIfNeeded(delayMs: Long = USAGE_ACCESS_POLL_DELAY_MS) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(ACTION_USAGE_ACCESS_POLL, USAGE_ACCESS_POLL_REQUEST_CODE)
        alarmManager.cancel(pi)
        val triggerAtMs = System.currentTimeMillis() + delayMs
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        FocusLog.d(FocusEventId.ALARM_SCHEDULE, "scheduleUsageAccessPoll: scheduled in ${delayMs}ms")
    }

    fun cancelUsageAccessPoll() {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(pendingIntent(ACTION_USAGE_ACCESS_POLL, USAGE_ACCESS_POLL_REQUEST_CODE))
    }

    private fun scheduleAlarm(
        requestCode: Int,
        action: String,
        atMillis: Long,
        label: String
    ) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(action, requestCode)
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
                    FocusLog.d(FocusEventId.ALARM_SCHEDULE, "$label: EXACT alarm action=$action at=$targetAtMs (in ${(targetAtMs - System.currentTimeMillis()) / 1000}s)")
                }.getOrElse { exactError ->
                    FocusLog.e(
                        context,
                        FocusEventId.SCHEDULE_ENFORCE_FAIL,
                        "Exact alarm scheduling failed for $action; falling back to inexact alarm",
                        exactError
                    )
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetAtMs, pi)
                    FocusLog.d(FocusEventId.ALARM_SCHEDULE, "$label: INEXACT fallback alarm action=$action at=$targetAtMs")
                }
            }
            else -> {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetAtMs, pi)
                FocusLog.d(FocusEventId.ALARM_SCHEDULE, "$label: INEXACT alarm action=$action at=$targetAtMs (cannot schedule exact)")
            }
        }
    }

    private fun pendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, ScheduleTickReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val ACTION_POLICY_WAKE = "com.ankit.destination.schedule.POLICY_WAKE"
        const val ACTION_SCHEDULE_TICK = ACTION_POLICY_WAKE
        const val ACTION_USAGE_ACCESS_POLL = "com.ankit.destination.schedule.USAGE_ACCESS_POLL"
        const val ACTION_RELIABILITY_TICK = "com.ankit.destination.schedule.RELIABILITY_TICK"
        private const val POLICY_WAKE_REQUEST_CODE = 1001
        private const val USAGE_ACCESS_POLL_REQUEST_CODE = 1002
        private const val RELIABILITY_REQUEST_CODE = 1003
        private const val MIN_ALARM_LEAD_MS = 1_000L
        private const val USAGE_ACCESS_POLL_DELAY_MS = 15_000L
    }
}
