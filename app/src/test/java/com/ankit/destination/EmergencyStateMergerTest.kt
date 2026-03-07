package com.ankit.destination

import com.ankit.destination.data.EmergencyState
import com.ankit.destination.data.EmergencyStateMerger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class EmergencyStateMergerTest {
    @Test
    fun merge_crossMidnightCarry_usesTodayUnlockCount_andMaxActiveUntil() {
        val today = "2026-01-02"
        val nowMs = 1_000L

        val rows = listOf(
            EmergencyState(
                dayKey = "2026-01-01",
                targetType = "GROUP",
                targetId = "study",
                unlocksUsedToday = 1,
                activeUntilEpochMs = 2_000L
            ),
            EmergencyState(
                dayKey = today,
                targetType = "GROUP",
                targetId = "study",
                unlocksUsedToday = 2,
                activeUntilEpochMs = null
            )
        ).shuffled()

        val merged = EmergencyStateMerger.merge(dayKey = today, nowMs = nowMs, rows = rows)
        val state = merged.firstOrNull { it.targetType == "GROUP" && it.targetId == "study" }
        assertNotNull(state)
        assertEquals(2, state!!.unlocksUsedToday)
        assertEquals(2_000L, state.activeUntilEpochMs)
    }
}
