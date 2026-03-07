package com.ankit.destination.security

object AdminGateChecker {
    fun canPerform(capability: AdminCapability, appLock: AppLockManager): Boolean {
        if (!CapabilityPolicy.requiresPassword(capability)) return true
        return appLock.isAdminSessionActive()
    }
}
