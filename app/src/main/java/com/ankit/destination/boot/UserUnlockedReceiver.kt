package com.ankit.destination.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ankit.destination.enforce.EnforcementExecutor
import com.ankit.destination.policy.FocusEventId
import com.ankit.destination.policy.FocusLog
import com.ankit.destination.policy.PolicyEngine

import com.ankit.destination.usage.UsageAccessMonitor

class UserUnlockedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val trigger = intent?.action ?: Intent.ACTION_USER_UNLOCKED
        val pending = goAsync()
        EnforcementExecutor.executeLatest(
            key = EXECUTOR_KEY,
            onDropped = pending::finish
        ) {
            try {
                UsageAccessMonitor.refreshNow(
                    context = context,
                    reason = "user_unlocked",
                    // This receiver triggers an explicit apply; avoid duplicate monitor-driven apply.
                    requestPolicyRefreshIfChanged = false
                )
                val engine = PolicyEngine(context)
                if (!engine.isDeviceOwner()) {
                    return@executeLatest
                }
                engine.requestApplyNow(reason = "UserUnlockedReceiver:$trigger")
            } catch (t: Throwable) {
                FocusLog.e(FocusEventId.BOOT_RETRY, "User unlock reapply failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        private const val EXECUTOR_KEY = "user-unlocked-reapply"
    }
}
