package com.ankit.destination.usage

import android.app.AppOpsManager
import android.content.Context
import com.ankit.destination.enforce.PolicyApplyOrchestrator
import com.ankit.destination.policy.FocusEventId
import com.ankit.destination.policy.FocusLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UsageAccessMonitorState(
    val usageAccessGranted: Boolean = false,
    val lastCheckAtMs: Long = 0L
)

object UsageAccessMonitor {
    private const val POLICY_REFRESH_DEBOUNCE_MS = 1_000L

    private val lock = Any()
    private val _currentState = MutableStateFlow(UsageAccessMonitorState())

    @Volatile
    private var appContext: Context? = null
    private var lastPolicyRefreshAtMs: Long = 0L
    private var lastPolicyRefreshGranted: Boolean? = null

    val currentState: StateFlow<UsageAccessMonitorState> = _currentState.asStateFlow()

    internal fun shouldHandleUsageAccessOpChange(
        op: String,
        packageName: String?,
        ownPackageName: String
    ): Boolean {
        if (op != AppOpsManager.OPSTR_GET_USAGE_STATS) return false
        if (packageName.isNullOrBlank()) return true
        return packageName == ownPackageName
    }

    private val opChangedListener = AppOpsManager.OnOpChangedListener { op, packageName ->
        val context = appContext ?: return@OnOpChangedListener
        if (!shouldHandleUsageAccessOpChange(op, packageName, context.packageName)) {
            return@OnOpChangedListener
        }
        refreshNow(
            context = context,
            reason = "app_ops",
            requestPolicyRefreshIfChanged = true
        )
    }

    fun initialize(context: Context) {
        val normalizedContext = context.applicationContext
        synchronized(lock) {
            if (appContext != null) return
            appContext = normalizedContext
            FocusLog.d(FocusEventId.USAGE_ACCESS_CHECK, "UsageAccessMonitor.initialize()")
            val appOps = normalizedContext.getSystemService(AppOpsManager::class.java) ?: return
            runCatching {
                @Suppress("DEPRECATION")
                appOps.startWatchingMode(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    normalizedContext.packageName,
                    opChangedListener
                )
                FocusLog.d(FocusEventId.USAGE_ACCESS_CHECK, "UsageAccessMonitor: AppOps watcher registered")
            }.onFailure { throwable ->
                FocusLog.e(
                    FocusEventId.POLICY_STATE_COMPUTED,
                    "Failed to register Usage Access watcher",
                    throwable
                )
            }
        }
    }

    fun refreshNow(
        context: Context,
        reason: String,
        requestPolicyRefreshIfChanged: Boolean = true,
        minimumIntervalMs: Long = 0L
    ): UsageAccessMonitorState {
        initialize(context)
        val normalizedContext = appContext ?: context.applicationContext
        val previous = _currentState.value
        val nowMs = System.currentTimeMillis()
        if (shouldReuseRecentRefresh(previous.lastCheckAtMs, nowMs, minimumIntervalMs)) {
            FocusLog.v(
                FocusEventId.USAGE_ACCESS_CHECK,
                "refreshNow($reason) REUSED (interval throttle ${nowMs - previous.lastCheckAtMs}ms < ${minimumIntervalMs}ms)"
            )
            return previous
        }
        val next = UsageAccessMonitorState(
            usageAccessGranted = UsageAccess.hasUsageAccess(normalizedContext),
            lastCheckAtMs = nowMs
        )
        _currentState.value = next
        val changed = previous.lastCheckAtMs > 0L && previous.usageAccessGranted != next.usageAccessGranted
        FocusLog.d(
            FocusEventId.USAGE_ACCESS_CHECK,
            "refreshNow($reason): granted=${next.usageAccessGranted} prev=${previous.usageAccessGranted} changed=$changed"
        )
        if (
            requestPolicyRefreshIfChanged &&
            changed
        ) {
            requestPolicyRefresh(normalizedContext, reason, next.usageAccessGranted)
        }
        return next
    }

    private fun requestPolicyRefresh(context: Context, reason: String, usageAccessGranted: Boolean) {
        val shouldDispatch = synchronized(lock) {
            val now = System.currentTimeMillis()
            val suppressed = shouldSuppressPolicyRefresh(
                lastPolicyRefreshGranted = lastPolicyRefreshGranted,
                nextUsageAccessGranted = usageAccessGranted,
                lastPolicyRefreshAtMs = lastPolicyRefreshAtMs,
                nowMs = now,
                debounceMs = POLICY_REFRESH_DEBOUNCE_MS
            )
            if (!suppressed) {
                lastPolicyRefreshAtMs = now
                lastPolicyRefreshGranted = usageAccessGranted
            }
            !suppressed
        }
        if (!shouldDispatch) {
            FocusLog.d(FocusEventId.USAGE_ACCESS_CHECK, "requestPolicyRefresh($reason) SUPPRESSED (debounce)")
            return
        }

        FocusLog.i(FocusEventId.USAGE_ACCESS_CHECK, "requestPolicyRefresh($reason) DISPATCHING granted=$usageAccessGranted")
        PolicyApplyOrchestrator.requestApply(
            context = context,
            reason = "usage_access:$reason"
        )
    }

    internal fun shouldSuppressPolicyRefresh(
        lastPolicyRefreshGranted: Boolean?,
        nextUsageAccessGranted: Boolean,
        lastPolicyRefreshAtMs: Long,
        nowMs: Long,
        debounceMs: Long
    ): Boolean {
        val delta = nowMs - lastPolicyRefreshAtMs
        if (delta < 0L) return false
        return lastPolicyRefreshGranted == nextUsageAccessGranted &&
            delta < debounceMs
    }

    internal fun shouldReuseRecentRefresh(
        lastCheckAtMs: Long,
        nowMs: Long,
        minimumIntervalMs: Long
    ): Boolean {
        val delta = nowMs - lastCheckAtMs
        if (delta < 0L) return false
        return minimumIntervalMs > 0L &&
            lastCheckAtMs > 0L &&
            delta < minimumIntervalMs
    }
}

