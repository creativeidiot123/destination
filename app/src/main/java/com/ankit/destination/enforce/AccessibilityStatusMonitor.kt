package com.ankit.destination.enforce

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import com.ankit.destination.enforce.PolicyApplyOrchestrator
import com.ankit.destination.policy.ApplyTrigger
import com.ankit.destination.policy.ApplyTriggerCategory
import com.ankit.destination.policy.FocusEventId
import com.ankit.destination.policy.FocusLog
import com.ankit.destination.policy.PolicyStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AccessibilityMonitorState(
    val enabled: Boolean = false,
    val lastCheckAtMs: Long = 0L,
    val lastConnectedAtMs: Long = 0L,
    val lastDisconnectedAtMs: Long = 0L,
    val lastHeartbeatAtMs: Long = 0L
)

object AccessibilityStatusMonitor {
    private val lock = Any()
    private val _currentState = MutableStateFlow(AccessibilityMonitorState())

    @Volatile
    private var appContext: Context? = null
    private var settingsObserverRegistered: Boolean = false
    private var lastPolicyRefreshAtMs: Long = 0L
    private var lastPolicyRefreshEnabled: Boolean? = null
    private var lastPolicyRefreshRunning: Boolean? = null

    val currentState: StateFlow<AccessibilityMonitorState> = _currentState.asStateFlow()

    private val settingsObserver = object : ContentObserver(observerHandler()) {
        override fun onChange(selfChange: Boolean) {
            onChange(selfChange, null)
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            val context = appContext ?: return
            FocusLog.d(
                FocusEventId.ACCESSIBILITY_STATUS,
                "Accessibility settings changed uri=${uri ?: "unknown"}"
            )
            refreshNow(
                context = context,
                reason = "settings_observer",
                requestPolicyRefreshIfChanged = true,
                minimumIntervalMs = SETTINGS_OBSERVER_THROTTLE_MS
            )
        }
    }

    private fun observerHandler(): Handler? {
        val mainLooper = runCatching { Looper.getMainLooper() }.getOrNull() ?: return null
        return runCatching { Handler(mainLooper) }.getOrNull()
    }

    fun initialize(context: Context) {
        val normalizedContext = context.applicationContext
        synchronized(lock) {
            if (appContext == null) {
                appContext = normalizedContext
            }
            if (!settingsObserverRegistered) {
                normalizedContext.contentResolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                    false,
                    settingsObserver
                )
                normalizedContext.contentResolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
                    false,
                    settingsObserver
                )
                settingsObserverRegistered = true
            }
        }
    }

    fun refreshNow(
        context: Context,
        reason: String,
        requestPolicyRefreshIfChanged: Boolean = false,
        minimumIntervalMs: Long = 0L
    ): AccessibilityMonitorState {
        initialize(context)
        val normalizedContext = appContext ?: context.applicationContext
        val previous = _currentState.value
        val nowMs = System.currentTimeMillis()
        if (shouldReuseRecentRefresh(previous.lastCheckAtMs, nowMs, minimumIntervalMs)) {
            FocusLog.v(
                FocusEventId.ACCESSIBILITY_STATUS,
                "Accessibility refresh($reason) REUSED (interval throttle ${nowMs - previous.lastCheckAtMs}ms < ${minimumIntervalMs}ms)"
            )
            return previous
        }
        val store = PolicyStore(normalizedContext)
        val enabled = isAccessibilityServiceEnabled(normalizedContext)
        val checkedAtMs = nowMs
        store.setAccessibilityServiceEnabled(enabled = enabled, checkedAtMs = checkedAtMs)
        val next = AccessibilityMonitorState(
            enabled = enabled,
            lastCheckAtMs = checkedAtMs,
            lastConnectedAtMs = store.getAccessibilityLastConnectedAtMs() ?: 0L,
            lastDisconnectedAtMs = store.getAccessibilityLastDisconnectedAtMs() ?: 0L,
            lastHeartbeatAtMs = store.getAccessibilityLastHeartbeatAtMs() ?: 0L
        )
        _currentState.value = next
        val previousRunning = serviceRunning(previous, nowMs)
        val nextRunning = serviceRunning(next, nowMs)
        val changed = previous.lastCheckAtMs > 0L &&
            (previous.enabled != next.enabled || previousRunning != nextRunning)
        FocusLog.d(
            FocusEventId.ACCESSIBILITY_STATUS,
            "Accessibility refresh($reason): enabled=$enabled running=$nextRunning prevEnabled=${previous.enabled} prevRunning=$previousRunning changed=$changed"
        )
        if (requestPolicyRefreshIfChanged && changed) {
            requestPolicyRefresh(
                context = normalizedContext,
                reason = reason,
                accessibilityEnabled = next.enabled,
                serviceRunning = nextRunning
            )
        }
        return next
    }

    fun markServiceConnected(context: Context, reason: String): AccessibilityMonitorState {
        initialize(context)
        val normalizedContext = appContext ?: context.applicationContext
        val store = PolicyStore(normalizedContext)
        val nowMs = System.currentTimeMillis()
        store.markAccessibilityServiceConnected(nowMs)
        store.markAccessibilityHeartbeat(nowMs)
        val next = refreshNow(
            context = normalizedContext,
            reason = reason,
            requestPolicyRefreshIfChanged = true
        )
        FocusLog.i(FocusEventId.ACCESSIBILITY_SERVICE, "Accessibility service connected reason=$reason")
        return next
    }

    fun markServiceDisconnected(context: Context, reason: String): AccessibilityMonitorState {
        initialize(context)
        val normalizedContext = appContext ?: context.applicationContext
        val store = PolicyStore(normalizedContext)
        store.markAccessibilityServiceDisconnected()
        val next = refreshNow(
            context = normalizedContext,
            reason = reason,
            requestPolicyRefreshIfChanged = true
        )
        FocusLog.w(FocusEventId.ACCESSIBILITY_SERVICE, "Accessibility service disconnected reason=$reason")
        return next
    }

    fun markHeartbeat(context: Context, reason: String): AccessibilityMonitorState {
        initialize(context)
        val normalizedContext = appContext ?: context.applicationContext
        val store = PolicyStore(normalizedContext)
        store.markAccessibilityHeartbeat()
        val next = refreshNow(
            context = normalizedContext,
            reason = reason,
            requestPolicyRefreshIfChanged = true
        )
        FocusLog.d(FocusEventId.ACCESSIBILITY_SERVICE, "Accessibility heartbeat reason=$reason")
        return next
    }

    fun serviceRunning(state: AccessibilityMonitorState = currentState.value, nowMs: Long = System.currentTimeMillis()): Boolean {
        val lastActivityAtMs = maxOf(state.lastConnectedAtMs, state.lastHeartbeatAtMs)
        if (lastActivityAtMs <= 0L) return false
        if (state.lastDisconnectedAtMs > lastActivityAtMs) return false
        return state.enabled && nowMs - lastActivityAtMs <= SERVICE_STALE_MS
    }

    fun degradedReason(state: AccessibilityMonitorState = currentState.value, nowMs: Long = System.currentTimeMillis()): String? {
        return when {
            !state.enabled -> "Enable Accessibility for real-time blocking and recovery enforcement."
            !serviceRunning(state, nowMs) -> "Accessibility is enabled, but the enforcement service is not reporting in. Open Accessibility settings and verify Destination is active."
            else -> null
        }
    }

    internal fun isAccessibilityServiceEnabled(
        context: Context,
        enabledAccessibilityServices: String? = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ),
        accessibilityEnabledFlag: Int = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        )
    ): Boolean {
        val expected = ComponentName(context, FocusEnforcementService::class.java)
        val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)
        val enabledByManager = runCatching {
            accessibilityManager
                ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.any { serviceInfo ->
                    val serviceComponent = ComponentName(
                        serviceInfo.resolveInfo.serviceInfo.packageName,
                        serviceInfo.resolveInfo.serviceInfo.name
                    )
                    serviceComponent == expected
                }
                ?: false
        }.getOrDefault(false)
        if (enabledByManager) {
            return true
        }
        if (accessibilityEnabledFlag != 1) return false
        val expectedLong = expected.flattenToString()
        val expectedShort = expected.flattenToShortString()
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledAccessibilityServices.orEmpty())
        while (splitter.hasNext()) {
            val candidate = splitter.next()
            if (candidate == expectedLong || candidate == expectedShort) {
                return true
            }
        }
        return false
    }

    private fun requestPolicyRefresh(
        context: Context,
        reason: String,
        accessibilityEnabled: Boolean,
        serviceRunning: Boolean
    ) {
        val shouldDispatch = synchronized(lock) {
            val nowMs = System.currentTimeMillis()
            val sameState = lastPolicyRefreshEnabled == accessibilityEnabled &&
                lastPolicyRefreshRunning == serviceRunning
            val delta = nowMs - lastPolicyRefreshAtMs
            val suppressed = sameState && delta >= 0L && delta < POLICY_REFRESH_DEBOUNCE_MS
            if (!suppressed) {
                lastPolicyRefreshAtMs = nowMs
                lastPolicyRefreshEnabled = accessibilityEnabled
                lastPolicyRefreshRunning = serviceRunning
            }
            !suppressed
        }
        if (!shouldDispatch) {
            FocusLog.d(
                FocusEventId.ACCESSIBILITY_STATUS,
                "requestPolicyRefresh($reason) SUPPRESSED (debounce)"
            )
            return
        }
        FocusLog.i(
            FocusEventId.ACCESSIBILITY_STATUS,
            "requestPolicyRefresh($reason) DISPATCHING enabled=$accessibilityEnabled running=$serviceRunning"
        )
        PolicyApplyOrchestrator.requestApply(
            context = context,
            trigger = ApplyTrigger(
                category = ApplyTriggerCategory.ACCESSIBILITY,
                source = "accessibility_status_monitor",
                detail = reason
            )
        )
    }

    private fun shouldReuseRecentRefresh(
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

    internal const val SERVICE_STALE_MS = 3L * 60_000L
    private const val POLICY_REFRESH_DEBOUNCE_MS = 1_000L
    private const val SETTINGS_OBSERVER_THROTTLE_MS = 750L
}
