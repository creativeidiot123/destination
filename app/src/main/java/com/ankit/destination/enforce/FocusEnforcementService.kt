package com.ankit.destination.enforce

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.ankit.destination.packages.PackageChangeReceiver
import com.ankit.destination.policy.ApplyTrigger
import com.ankit.destination.policy.ApplyTriggerCategory
import com.ankit.destination.policy.FocusEventId
import com.ankit.destination.policy.FocusLog
import com.ankit.destination.policy.PolicyStore
import com.ankit.destination.schedule.AlarmScheduler
import java.util.concurrent.atomic.AtomicLong

class FocusEnforcementService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastTriggerAtMs = AtomicLong(0L)
    private val lastHeartbeatWriteAtMs = AtomicLong(0L)
    private var lastForegroundPackage: String? = null
    private var serviceActive: Boolean = false

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!serviceActive) return
            AccessibilityStatusMonitor.markHeartbeat(this@FocusEnforcementService, "service_heartbeat")
            ensurePolicyWakeScheduled()
            triggerEnforcement("heartbeat")
            mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceActive = true
        AccessibilityStatusMonitor.markServiceConnected(this, "service_connected")
        PackageChangeReceiver.ensureRuntimeRegistration(this)
        ensurePolicyWakeScheduled()
        triggerEnforcement("service_connected")
        mainHandler.removeCallbacks(heartbeatRunnable)
        mainHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString()?.trim().orEmpty()
        if (packageName.isBlank() || packageName == this.packageName || packageName == lastForegroundPackage) {
            return
        }
        maybeRecordAccessibilityActivity("window_state:$packageName")
        lastForegroundPackage = packageName
        val store = PolicyStore(this)
        if (isPersistedBlockedPackage(store, packageName)) {
            FocusLog.i(FocusEventId.ACCESSIBILITY_SERVICE, "Blocked package launch detected pkg=$packageName")
            triggerEnforcement("blocked_launch:$packageName")
            return
        }
        val lastAppliedAtMs = store.getLastAppliedAtMs()
        if (lastAppliedAtMs <= 0L || System.currentTimeMillis() - lastAppliedAtMs >= HEARTBEAT_INTERVAL_MS) {
            triggerEnforcement("foreground_refresh:$packageName")
        }
    }

    override fun onInterrupt() {
        FocusLog.w(FocusEventId.ACCESSIBILITY_SERVICE, "Accessibility service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        serviceActive = false
        mainHandler.removeCallbacks(heartbeatRunnable)
        AccessibilityStatusMonitor.markServiceDisconnected(this, "service_unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        serviceActive = false
        mainHandler.removeCallbacks(heartbeatRunnable)
        AccessibilityStatusMonitor.markServiceDisconnected(this, "service_destroyed")
        super.onDestroy()
    }

    private fun triggerEnforcement(reason: String) {
        val nowMs = System.currentTimeMillis()
        val previous = lastTriggerAtMs.get()
        if (nowMs - previous < TRIGGER_DEBOUNCE_MS) return
        if (!lastTriggerAtMs.compareAndSet(previous, nowMs)) return
        PolicyApplyOrchestrator.requestApply(
            context = this,
            trigger = ApplyTrigger(
                category = ApplyTriggerCategory.ACCESSIBILITY,
                source = "focus_enforcement_service",
                detail = reason
            )
        )
    }

    private fun ensurePolicyWakeScheduled() {
        val store = PolicyStore(this)
        val nextWakeAtMs = store.getNextPolicyWakeAtMs()
        val nextWakeReason = store.getNextPolicyWakeReason()
        val scheduler = AlarmScheduler(this)
        when {
            nextWakeReason == "reliability_tick" -> {
                scheduler.scheduleReliabilityTick(nextWakeAtMs ?: (System.currentTimeMillis() + RELIABILITY_TICK_INTERVAL_MS))
            }
            nextWakeAtMs != null -> {
                scheduler.scheduleNextTransition(nextWakeAtMs)
            }
            else -> {
                scheduler.scheduleReliabilityTick(System.currentTimeMillis() + RELIABILITY_TICK_INTERVAL_MS)
            }
        }
    }

    private fun maybeRecordAccessibilityActivity(reason: String) {
        val nowMs = System.currentTimeMillis()
        val previous = lastHeartbeatWriteAtMs.get()
        if (nowMs - previous < EVENT_HEARTBEAT_MIN_INTERVAL_MS) return
        if (!lastHeartbeatWriteAtMs.compareAndSet(previous, nowMs)) return
        AccessibilityStatusMonitor.markHeartbeat(this, reason)
    }

    private fun isPersistedBlockedPackage(store: PolicyStore, packageName: String): Boolean {
        return store.getBlockReasonsByPackage().containsKey(packageName) ||
            store.getPrimaryReasonByPackage().containsKey(packageName) ||
            packageName in store.getScheduleBlockedPackages() ||
            packageName in store.getBudgetBlockedPackages() ||
            packageName in store.getStrictInstallSuspendedPackages()
    }

    private companion object {
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val RELIABILITY_TICK_INTERVAL_MS = 15L * 60_000L
        private const val TRIGGER_DEBOUNCE_MS = 2_000L
        private const val EVENT_HEARTBEAT_MIN_INTERVAL_MS = 10_000L
    }
}
