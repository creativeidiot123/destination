package com.ankit.destination.policy

import android.app.admin.DevicePolicyManager
import android.os.Build
import android.os.UserManager

object FocusConfig {
    val defaultEmergencyPackages: Set<String> = setOf(
        "com.google.android.apps.maps",
        "com.whatsapp",
        "com.ubercab"
    )

    /**
     * High-risk kiosk-style lockdown mode.
     *
     * Keep DISABLED by default to avoid accidental partial enablement across the codebase.
     * Only enable after validating provisioning + escape hatches on all supported devices.
     */
    const val enableNuclearMode: Boolean = false

    // Manual nuclear toggle may require VPN; schedule/touch-grass enforcement should not depend on this.
    const val requireVpnForNuclear: Boolean = false

    // Phase 4: enforce always-on VPN with lockdown through Device Owner policy.
    const val enforceAlwaysOnVpnLockdown: Boolean = true

    // Internal/dev recovery path.
    const val debugRecoveryPin: String = "7391"
    const val defaultTouchGrassUnlockThreshold: Int = 30
    const val defaultTouchGrassBreakMinutes: Int = 10

    val protectedExactPackages: Set<String> = setOf("android")

    val protectedPackagePrefixes: Set<String> = setOf(
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller"
    )

    const val nuclearLockTaskFeatures: Int = DevicePolicyManager.LOCK_TASK_FEATURE_NONE
    const val normalLockTaskFeatures: Int =
        DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
            DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
            DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
            DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW or
            DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
            DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD

    fun nuclearRestrictions(): Set<String> {
        val restrictions = mutableSetOf(
            UserManager.DISALLOW_INSTALL_APPS,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_DEBUGGING_FEATURES,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_UNINSTALL_APPS,
            UserManager.DISALLOW_APPS_CONTROL
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            restrictions += UserManager.DISALLOW_CONFIG_DATE_TIME
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            restrictions += UserManager.DISALLOW_ADD_MANAGED_PROFILE
            restrictions += UserManager.DISALLOW_SAFE_BOOT
        }
        return restrictions
    }
}
