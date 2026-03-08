package com.ankit.destination.policy

import android.app.admin.DevicePolicyManager

object FocusConfig {
    val defaultEmergencyPackages: Set<String> = setOf(
        "com.google.android.apps.maps",
        "com.whatsapp",
        "com.ubercab"
    )

    // Phase 4: enforce always-on VPN with lockdown through Device Owner policy.
    const val enforceAlwaysOnVpnLockdown: Boolean = true

    // Internal/dev recovery path.
    const val debugRecoveryPin: String = "7391"
    const val defaultTouchGrassUnlockThreshold: Int = 30
    const val defaultTouchGrassBreakMinutes: Int = 10
    const val prototypeHiddenSuspendDialogEnabled: Boolean = true
    const val prototypeHiddenSuspendDialogTitle: String =
        "Go Touch Grass! Destination is Judging you!"
    const val prototypeHiddenSuspendDialogMessageTemplate: String =
        "%1\$s Blocked, policy block is active"
    const val prototypeHiddenSuspendScheduleMessageTemplate: String =
        "%1\$s Blocked, schedule block is active"
    const val prototypeHiddenSuspendHourlyMessageTemplate: String =
        "%1\$s Blocked, Hourly limit reached"
    const val prototypeHiddenSuspendDailyMessageTemplate: String =
        "%1\$s Blocked, Daily limit reached"
    const val prototypeHiddenSuspendOpensMessageTemplate: String =
        "%1\$s Blocked, Daily opens limit reached"

    val protectedExactPackages: Set<String> = setOf("android")

    val protectedPackagePrefixes: Set<String> = setOf(
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller"
    )

    const val normalLockTaskFeatures: Int =
        DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
            DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
            DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
            DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW or
            DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
            DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD
}
