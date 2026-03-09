package com.ankit.destination

import com.ankit.destination.data.GroupTargetMode
import com.ankit.destination.ui.groups.isAllAppsScheduleTargetEnabled
import com.ankit.destination.ui.groups.resolvePersistedScheduleTargetMode
import com.ankit.destination.ui.groups.shouldDisableGroupMemberPicker
import com.ankit.destination.ui.groups.validateGroupScheduleWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupTargetingUiTest {

    @Test
    fun strictOff_forcesSelectedAppsPersistence() {
        assertTrue(
            resolvePersistedScheduleTargetMode(
                strictEnabled = false,
                allAppsEnabled = true
            ) == GroupTargetMode.SELECTED_APPS.name
        )
    }

    @Test
    fun strictOn_allAppsEnablesAllAppsPersistence() {
        assertTrue(
            resolvePersistedScheduleTargetMode(
                strictEnabled = true,
                allAppsEnabled = true
            ) == GroupTargetMode.ALL_APPS.name
        )
    }

    @Test
    fun pickerDisablesOnlyForStrictAllApps() {
        assertTrue(shouldDisableGroupMemberPicker(strictEnabled = true, allAppsEnabled = true))
        assertFalse(shouldDisableGroupMemberPicker(strictEnabled = true, allAppsEnabled = false))
        assertFalse(shouldDisableGroupMemberPicker(strictEnabled = false, allAppsEnabled = true))
    }

    @Test
    fun storedAllAppsStateLoadsOnlyWhenStrictIsEnabled() {
        assertTrue(
            isAllAppsScheduleTargetEnabled(
                strictEnabled = true,
                storedTargetMode = GroupTargetMode.ALL_APPS.name
            )
        )
        assertFalse(
            isAllAppsScheduleTargetEnabled(
                strictEnabled = false,
                storedTargetMode = GroupTargetMode.ALL_APPS.name
            )
        )
    }

    @Test
    fun scheduleValidation_rejectsEqualStartAndEnd_whenEnabled() {
        assertEquals(
            "Schedule start and end time cannot be the same.",
            validateGroupScheduleWindow(
                scheduleEnabled = true,
                startMinute = 600,
                endMinute = 600
            )
        )
    }

    @Test
    fun scheduleValidation_allowsEqualStartAndEnd_whenDisabled() {
        assertNull(
            validateGroupScheduleWindow(
                scheduleEnabled = false,
                startMinute = 600,
                endMinute = 600
            )
        )
    }
}
