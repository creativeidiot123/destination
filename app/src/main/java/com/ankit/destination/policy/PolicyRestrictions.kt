package com.ankit.destination.policy

import android.os.Build
import android.os.UserManager
import com.ankit.destination.data.GlobalControls

internal object PolicyRestrictions {
    const val NO_ADD_CLONE_PROFILE = "no_add_clone_profile"
    const val NO_ADD_PRIVATE_PROFILE = "no_add_private_profile"

    private val bestEffortRestrictions = setOf(
        NO_ADD_CLONE_PROFILE,
        NO_ADD_PRIVATE_PROFILE
    )

    fun build(
        mode: ModeState,
        globalControls: GlobalControls,
        managedNetworkPolicy: ManagedNetworkPolicy = ManagedNetworkPolicy.Unmanaged,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Set<String> = buildSet {
        if (mode == ModeState.NUCLEAR) {
            addAll(FocusConfig.nuclearRestrictions())
        }
        when (managedNetworkPolicy) {
            is ManagedNetworkPolicy.ForcedVpn,
            is ManagedNetworkPolicy.ForcedPrivateDns -> {
                add(UserManager.DISALLOW_CONFIG_VPN)
                if (isSupported(UserManager.DISALLOW_CONFIG_PRIVATE_DNS, sdkInt)) {
                    add(UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
                }
            }

            ManagedNetworkPolicy.Unmanaged -> Unit
        }
        if (globalControls.lockTime && isSupported(UserManager.DISALLOW_CONFIG_DATE_TIME, sdkInt)) {
            add(UserManager.DISALLOW_CONFIG_DATE_TIME)
        }
        if (globalControls.lockDevOptions) {
            add(UserManager.DISALLOW_DEBUGGING_FEATURES)
        }
        if (globalControls.disableSafeMode && isSupported(UserManager.DISALLOW_SAFE_BOOT, sdkInt)) {
            add(UserManager.DISALLOW_SAFE_BOOT)
        }
        if (globalControls.lockUserCreation) {
            add(UserManager.DISALLOW_ADD_USER)
        }
        if (globalControls.lockWorkProfile && isSupported(UserManager.DISALLOW_ADD_MANAGED_PROFILE, sdkInt)) {
            add(UserManager.DISALLOW_ADD_MANAGED_PROFILE)
        }
        if (globalControls.lockCloningBestEffort) {
            if (isSupported(NO_ADD_CLONE_PROFILE, sdkInt)) {
                add(NO_ADD_CLONE_PROFILE)
            }
            if (isSupported(NO_ADD_PRIVATE_PROFILE, sdkInt)) {
                add(NO_ADD_PRIVATE_PROFILE)
            }
        }
    }

    fun managed(
        desiredRestrictions: Set<String>,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Set<String> = buildSet {
        addAll(FocusConfig.nuclearRestrictions())
        addAll(desiredRestrictions)
        bestEffortRestrictions
            .filterTo(this) { isSupported(it, sdkInt) }
    }

    private val bestEffortOnClearRestrictions = setOf(
        UserManager.DISALLOW_ADD_MANAGED_PROFILE,
        NO_ADD_CLONE_PROFILE,
        NO_ADD_PRIVATE_PROFILE
    )

    fun isBestEffort(restriction: String): Boolean = bestEffortRestrictions.contains(restriction)

    fun isBestEffortOnClear(restriction: String): Boolean =
        bestEffortOnClearRestrictions.contains(restriction)

    fun isSupported(restriction: String, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        return when (restriction) {
            UserManager.DISALLOW_CONFIG_DATE_TIME -> sdkInt >= Build.VERSION_CODES.P
            UserManager.DISALLOW_CONFIG_PRIVATE_DNS -> sdkInt >= Build.VERSION_CODES.Q
            UserManager.DISALLOW_ADD_MANAGED_PROFILE -> sdkInt >= Build.VERSION_CODES.O
            UserManager.DISALLOW_SAFE_BOOT -> sdkInt >= Build.VERSION_CODES.O
            NO_ADD_CLONE_PROFILE -> sdkInt >= 34
            NO_ADD_PRIVATE_PROFILE -> sdkInt >= 35
            else -> true
        }
    }
}
