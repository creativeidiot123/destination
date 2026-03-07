package com.ankit.destination.vpn

import android.content.Context

class VpnStatusStore(context: Context) {
    private val storageContext = context.applicationContext.createDeviceProtectedStorageContext()
    private val prefs = storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setRunning(running: Boolean) {
        prefs.edit().putBoolean(KEY_RUNNING, running).apply()
    }

    fun isRunning(): Boolean = prefs.getBoolean(KEY_RUNNING, false)

    fun setLastError(error: String?) {
        prefs.edit().putString(KEY_LAST_ERROR, error).apply()
    }

    fun getLastError(): String? = prefs.getString(KEY_LAST_ERROR, null)

    fun setDomainRuleCount(count: Int) {
        prefs.edit().putInt(KEY_DOMAIN_RULE_COUNT, count.coerceAtLeast(0)).apply()
    }

    fun getDomainRuleCount(): Int = prefs.getInt(KEY_DOMAIN_RULE_COUNT, 0)

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "focus_vpn_store"
        private const val KEY_RUNNING = "vpn_running"
        private const val KEY_LAST_ERROR = "vpn_last_error"
        private const val KEY_DOMAIN_RULE_COUNT = "vpn_domain_rule_count"
    }
}
