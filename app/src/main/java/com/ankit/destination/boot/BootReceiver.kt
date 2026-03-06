package com.ankit.destination.boot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ankit.destination.enforce.EnforcementExecutor
import com.ankit.destination.policy.FocusEventId
import com.ankit.destination.policy.FocusLog
import com.ankit.destination.policy.ModeState
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.schedule.ScheduleEnforcer

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val attempt = intent.getIntExtra(EXTRA_RETRY_ATTEMPT, 0)
        val pending = goAsync()

        EnforcementExecutor.execute {
            try {
                val engine = PolicyEngine(context)
                if (!engine.isDeviceOwner()) {
                    cancelRetry(context)
                    return@execute
                }

                FocusLog.i(FocusEventId.BOOT_REAPPLY, "action=$action attempt=$attempt")
                val includeBudgets = engine.shouldRunBudgetEvaluation()
                val scheduleResult = ScheduleEnforcer(context).enforceNow(
                    trigger = "BootReceiver:$action",
                    includeBudgets = includeBudgets
                ).policyResult
                val finalResult = if (!scheduleResult.success) {
                    engine.reapplyDesiredMode(reason = action)
                } else {
                    scheduleResult
                }

                // Fallback: if schedule path fails, still attempt baseline reapply.
                if (finalResult.success || finalResult.state.mode != ModeState.NUCLEAR) {
                    cancelRetry(context)
                }
                if (!finalResult.success && finalResult.state.mode == ModeState.NUCLEAR && attempt < RETRY_DELAYS_MS.size) {
                    scheduleRetry(context, attempt + 1)
                }
            } catch (t: Throwable) {
                FocusLog.e(FocusEventId.BOOT_RETRY, "Boot reapply crashed", t)
                val shouldRetry = runCatching {
                    val engine = PolicyEngine(context)
                    engine.isDeviceOwner() && engine.getDesiredMode() == ModeState.NUCLEAR
                }.getOrDefault(false)
                if (shouldRetry && attempt < RETRY_DELAYS_MS.size) {
                    scheduleRetry(context, attempt + 1)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun scheduleRetry(context: Context, attempt: Int) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val delayMs = RETRY_DELAYS_MS[attempt - 1]
        val triggerAtMs = System.currentTimeMillis() + delayMs
        val retryIntent = Intent(context, BootReceiver::class.java).apply {
            action = ACTION_REAPPLY_RETRY
            putExtra(EXTRA_RETRY_ATTEMPT, attempt)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            RETRY_REQUEST_CODE,
            retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Retry after boot is best-effort; avoid exact-alarm requirements.
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
        FocusLog.w(FocusEventId.BOOT_RETRY, "Scheduled retry attempt=$attempt delayMs=$delayMs")
    }

    private fun cancelRetry(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val retryIntent = Intent(context, BootReceiver::class.java).apply {
            action = ACTION_REAPPLY_RETRY
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            RETRY_REQUEST_CODE,
            retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    companion object {
        const val ACTION_REAPPLY_RETRY = "com.ankit.destination.ACTION_REAPPLY_RETRY"
        private const val EXTRA_RETRY_ATTEMPT = "retry_attempt"
        private const val RETRY_REQUEST_CODE = 49001
        private val RETRY_DELAYS_MS = longArrayOf(5_000L, 30_000L, 120_000L)
    }
}
