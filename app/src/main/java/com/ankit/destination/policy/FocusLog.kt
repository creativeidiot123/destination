package com.ankit.destination.policy

import android.util.Log

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
}

object FocusLog {
    private const val TAG = "FocusPolicy"

    fun i(eventId: String, message: String) {
        Log.i(TAG, "[$eventId] $message")
    }

    fun w(eventId: String, message: String) {
        Log.w(TAG, "[$eventId] $message")
    }

    fun e(eventId: String, message: String, throwable: Throwable? = null) {
        Log.e(TAG, "[$eventId] $message", throwable)
    }
}

