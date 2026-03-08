package com.ankit.destination.policy

import android.content.Context
import android.os.Looper
import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object FocusEventId {
    const val ADMIN_ENABLED = "ENROLL-001"
    const val ADMIN_DISABLED = "ENROLL-002"
    const val PROVISIONING_MODE_REQUEST = "ENROLL-003"
    const val PROVISIONING_SIGNAL = "ENROLL-004"
    const val PROVISIONING_FINALIZE_START = "ENROLL-005"
    const val PROVISIONING_FINALIZE_DONE = "ENROLL-006"
    const val PROVISIONING_FINALIZE_FAIL = "ENROLL-007"
    const val POLICY_APPLY_START = "POLICY-001"
    const val POLICY_APPLY_DONE = "POLICY-002"
    const val POLICY_VERIFY_FAIL = "POLICY-003"
    const val POLICY_VERIFY_PASS = "POLICY-004"
    const val MODE_CHANGE_REQUEST = "MODE-001"
    const val MODE_CHANGE_FAIL = "MODE-002"
    const val TOUCH_GRASS_TRIGGER = "MODE-003"
    const val BUDGET_EVAL = "MODE-004"
    const val STRICT_INSTALL_SUSPEND = "MODE-005"
    const val STRICT_INSTALL_SUSPEND_FAIL = "MODE-006"
    const val BOOT_REAPPLY = "BOOT-001"
    const val BOOT_RETRY = "BOOT-002"
    const val SCHEDULE_ENFORCE_FAIL = "SCHED-001"
    const val POLICY_STATE_COMPUTED = "POLICY-005"
    const val POLICY_RESET_START = "RECOVER-001"
    const val POLICY_RESET_DONE = "RECOVER-002"
    const val POLICY_RESET_FAIL = "RECOVER-003"
    const val MANAGED_NETWORK_CHANGE = "NET-001"
    const val ALLOWLIST_RESOLVE = "DETECT-001"
    const val SUSPEND_TARGET = "DETECT-002"
    const val PACKAGE_INSTALL_DETECT = "DETECT-003"
    const val USAGE_ACCESS_CHECK = "USAGE-001"
    const val USAGE_READ = "USAGE-002"
    const val BUDGET_SNAPSHOT = "BUDGET-001"
    const val BUDGET_EMERGENCY = "BUDGET-002"
    const val GROUP_EVAL = "GROUP-001"
    const val APP_EVAL = "APP-001"
    const val SCHEDULE_EVAL = "SCHED-002"
    const val ALARM_SCHEDULE = "SCHED-003"
    const val POLICY_WAKE = "SCHED-004"
    const val LOCK_CALC = "LOCK-001"
    const val ENFORCE_EXEC = "ENFORCE-001"
    const val ACCESSIBILITY_STATUS = "A11Y-001"
    const val ACCESSIBILITY_SERVICE = "A11Y-002"
    const val VPN_LIFECYCLE = "VPN-001"
    const val VPN_DNS = "VPN-002"
    const val STORE_WRITE = "STORE-001"
}

object FocusLog {
    private const val TAG = "FocusPolicy"
    private const val PREFS_NAME = "focus_debug_log"
    private const val KEY_HISTORY = "history"
    private const val HISTORY_LIMIT = 200

    /** Verbose trace — Logcat only, no persistence. Use for hot paths. */
    fun v(eventId: String, message: String) {
        safeLog { Log.v(TAG, "[${threadLabel()}][$eventId] $message") }
    }

    /** Debug trace — Logcat only, no persistence. Use for detailed logic tracing. */
    fun d(eventId: String, message: String) {
        safeLog { Log.d(TAG, "[${threadLabel()}][$eventId] $message") }
    }

    fun i(eventId: String, message: String) {
        safeLog { Log.i(TAG, "[${threadLabel()}][$eventId] $message") }
    }

    fun i(context: Context, eventId: String, message: String) {
        record(context, "I", eventId, message)
        i(eventId, message)
    }

    fun w(eventId: String, message: String) {
        safeLog { Log.w(TAG, "[${threadLabel()}][$eventId] $message") }
    }

    fun w(context: Context, eventId: String, message: String) {
        record(context, "W", eventId, message)
        w(eventId, message)
    }

    fun e(eventId: String, message: String, throwable: Throwable? = null) {
        safeLog { Log.e(TAG, "[${threadLabel()}][$eventId] $message", throwable) }
    }

    fun e(context: Context, eventId: String, message: String, throwable: Throwable? = null) {
        record(
            context = context,
            level = "E",
            eventId = eventId,
            message = if (throwable?.message.isNullOrBlank()) message else "$message | ${throwable?.message}"
        )
        e(eventId, message, throwable)
    }

    /** Measure and log duration of [block]. Returns the block's result. */
    inline fun <T> timed(eventId: String, label: String, block: () -> T): T {
        val startNs = System.nanoTime()
        val result = block()
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        d(eventId, "$label completed in %.1fms".format(elapsedMs))
        return result
    }

    /** Returns true if currently on the main/UI thread. */
    fun isMainThread(): Boolean = currentLooper() == mainLooper()

    /** Warn if executing on the main thread (for detecting blocking calls). */
    fun warnIfMainThread(caller: String) {
        if (isMainThread()) {
            w("THREAD", "⚠️ $caller called on MAIN thread — potential ANR risk")
        }
    }

    @Synchronized
    fun recentEntries(context: Context, limit: Int = 30): List<String> {
        val prefs = prefs(context)
        val raw = prefs.getString(KEY_HISTORY, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .filter(String::isNotBlank)
            .toList()
            .takeLast(limit.coerceAtLeast(1))
            .map(::formatEntry)
    }

    @Synchronized
    fun clearHistory(context: Context) {
        prefs(context).edit().remove(KEY_HISTORY).apply()
    }

    @Synchronized
    private fun record(context: Context, level: String, eventId: String, message: String) {
        val prefs = prefs(context)
        val cleanMessage = message.replace('\n', ' ').trim()
        val thread = threadLabel()
        val nextEntry = "${System.currentTimeMillis()}|$level|$eventId|[$thread] $cleanMessage"
        val existingEntries = prefs.getString(KEY_HISTORY, "").orEmpty()
            .lineSequence()
            .filter(String::isNotBlank)
            .toList()
        val combined = buildList {
            addAll(existingEntries.takeLast(HISTORY_LIMIT - 1))
            add(nextEntry)
        }
        prefs.edit().putString(KEY_HISTORY, combined.joinToString(separator = "\n")).apply()
    }

    private fun threadLabel(): String {
        val thread = Thread.currentThread()
        return if (currentLooper() != null && currentLooper() == mainLooper()) "main" else thread.name
    }

    private fun currentLooper(): Looper? = runCatching { Looper.myLooper() }.getOrNull()

    private fun mainLooper(): Looper? = runCatching { Looper.getMainLooper() }.getOrNull()

    private inline fun safeLog(block: () -> Unit) {
        runCatching(block)
    }

    private fun prefs(context: Context) = context.applicationContext
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun formatEntry(raw: String): String {
        val parts = raw.split('|', limit = 4)
        if (parts.size < 4) return raw
        val timestamp = parts[0].toLongOrNull()
        val label = if (timestamp != null) {
            timestampFormatter.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
        } else {
            "unknown-time"
        }
        return "$label ${parts[1]}/${parts[2]} ${parts[3]}"
    }

    private val timestampFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.US)
}
