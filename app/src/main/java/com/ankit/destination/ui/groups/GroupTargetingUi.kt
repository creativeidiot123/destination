package com.ankit.destination.ui.groups

import com.ankit.destination.data.GroupTargetMode

internal fun isAllAppsScheduleTargetEnabled(
    strictEnabled: Boolean,
    storedTargetMode: String
): Boolean {
    return strictEnabled && GroupTargetMode.fromStorage(storedTargetMode) == GroupTargetMode.ALL_APPS
}

internal fun resolvePersistedScheduleTargetMode(
    strictEnabled: Boolean,
    allAppsEnabled: Boolean
): String {
    return if (strictEnabled && allAppsEnabled) {
        GroupTargetMode.ALL_APPS.name
    } else {
        GroupTargetMode.SELECTED_APPS.name
    }
}

internal fun shouldDisableGroupMemberPicker(
    strictEnabled: Boolean,
    allAppsEnabled: Boolean
): Boolean {
    return strictEnabled && allAppsEnabled
}

internal fun validateGroupScheduleWindow(
    scheduleEnabled: Boolean,
    startMinute: Int,
    endMinute: Int
): String? {
    if (!scheduleEnabled) return null
    return if (startMinute == endMinute) {
        "Schedule start and end time cannot be the same."
    } else {
        null
    }
}
