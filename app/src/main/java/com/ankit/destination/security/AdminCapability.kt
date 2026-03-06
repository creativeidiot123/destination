package com.ankit.destination.security

enum class AdminCapability {
    EDIT_DEVICE_TOGGLES,
    UNENROLL_DEVICE_OWNER,
    ADD_ALLOWLIST_APP,
    REMOVE_ALLOWLIST_APP,
    EDIT_GROUP_SETTINGS,
    REMOVE_APP_FROM_GROUP,
    REORDER_GROUP_PRIORITY,
    EDIT_INDIVIDUAL_APP_SETTINGS,
    EDIT_UNINSTALL_PROTECTED_LIST,
    ADD_BLOCKLIST_APP,
    ADD_APP_TO_GROUP
}

object CapabilityPolicy {
    fun requiresPassword(capability: AdminCapability): Boolean {
        return when (capability) {
            AdminCapability.EDIT_DEVICE_TOGGLES,
            AdminCapability.UNENROLL_DEVICE_OWNER,
            AdminCapability.ADD_ALLOWLIST_APP,
            AdminCapability.REMOVE_ALLOWLIST_APP,
            AdminCapability.EDIT_GROUP_SETTINGS,
            AdminCapability.REMOVE_APP_FROM_GROUP,
            AdminCapability.REORDER_GROUP_PRIORITY,
            AdminCapability.EDIT_INDIVIDUAL_APP_SETTINGS,
            AdminCapability.EDIT_UNINSTALL_PROTECTED_LIST,
            AdminCapability.ADD_BLOCKLIST_APP,
            AdminCapability.ADD_APP_TO_GROUP -> true
        }
    }
}
