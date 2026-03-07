package com.ankit.destination.boot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ankit.destination.enforce.PolicyApplyOrchestrator
import com.ankit.destination.policy.FocusEventId
import com.ankit.destination.policy.FocusLog
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.usage.UsageAccessMonitor

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val attempt = intent.getIntExtra(EXTRA_RETRY_ATTEMPT, 0)
        val pending = goAsync()
        var requestEnqueued = false

        FocusLog.i(FocusEventId.BOOT_REAPPLY, "BootReceiver: action=$action attempt=$attempt")
        try {
            UsageAccessMonitor.refreshNow(
                context = context,
                reason = "boot:$action",
                requestPolicyRefreshIfChanged = false
            )
            val engine = PolicyEngine(context)
            if (!engine.isDeviceOwner()) {
                FocusLog.d(FocusEventId.BOOT_REAPPLY, "NOT device owner, skipping boot reapply")
                cancelRetry(context)
                return
            }

            requestEnqueued = true
            PolicyApplyOrchestrator.requestApply(
                context = context,
                reason = "BootReceiver:$action"
            ) { result ->
                FocusLog.d(FocusEventId.BOOT_REAPPLY, "apply: success=${result.success}")
                if (result.success) {
                    FocusLog.i(FocusEventId.BOOT_REAPPLY, "Boot reapply SUCCESS")
                    cancelRetry(context)
                } else {
                    FocusLog.w(FocusEventId.BOOT_REAPPLY, "Boot reapply FAILED")
                    if (attempt < RETRY_DELAYS_MS.size) {
                        FocusLog.w(FocusEventId.BOOT_RETRY, "Scheduling retry attempt=${attempt + 1}")
                        scheduleRetry(context, attempt + 1)
                    }
                }
                pending.finish()
            }
        } catch (t: Throwable) {
            FocusLog.e(FocusEventId.BOOT_RETRY, "Boot reapply crashed", t)
            val shouldRetry = runCatching {
                PolicyEngine(context).isDeviceOwner()
            }.getOrDefault(false)
            if (shouldRetry && attempt < RETRY_DELAYS_MS.size) {
                scheduleRetry(context, attempt + 1)
            }
        } finally {
            if (!requestEnqueued) {
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

    private companion object {
        const val ACTION_REAPPLY_RETRY = "com.ankit.destination.ACTION_REAPPLY_RETRY"
        private const val EXTRA_RETRY_ATTEMPT = "retry_attempt"
        private const val RETRY_REQUEST_CODE = 49001
        private val RETRY_DELAYS_MS = longArrayOf(5_000L, 30_000L, 120_000L)
    }
}

