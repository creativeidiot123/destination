package com.ankit.destination.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ankit.destination.enforce.PolicyApplyOrchestrator
import com.ankit.destination.policy.FocusEventId
import com.ankit.destination.policy.FocusLog
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.usage.UsageAccessMonitor

class UserUnlockedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val trigger = intent?.action ?: Intent.ACTION_USER_UNLOCKED
        val pending = goAsync()
        try {
            UsageAccessMonitor.refreshNow(
                context = context,
                reason = "user_unlocked",
                requestPolicyRefreshIfChanged = false
            )
            val engine = PolicyEngine(context)
            if (!engine.isDeviceOwner()) {
                pending.finish()
                return
            }
            PolicyApplyOrchestrator.requestApply(
                context = context,
                reason = "UserUnlockedReceiver:$trigger",
                onComplete = { pending.finish() }
            )
        } catch (t: Throwable) {
            FocusLog.e(FocusEventId.BOOT_RETRY, "User unlock reapply failed", t)
            pending.finish()
        }
    }
}

