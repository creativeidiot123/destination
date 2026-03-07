package com.ankit.destination

import com.ankit.destination.policy.PolicyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PolicyEngineAlarmPlanningTest {
    @Test
    fun `earliest future transition wins`() {
        val nextAlarmAt = PolicyEngine.pickNextAlarmAtMs(
            nowMs = 1_000L,
            scheduleNextTransitionAtMs = 5_000L,
            budgetNextCheckAtMs = 4_000L,
            touchGrassBreakUntilMs = 6_000L
        )

        assertEquals(4_000L, nextAlarmAt)
    }

    @Test
    fun `overdue budget check is preserved when requested`() {
        val nextAlarmAt = PolicyEngine.pickNextAlarmAtMs(
            nowMs = 10_000L,
            scheduleNextTransitionAtMs = 30_000L,
            budgetNextCheckAtMs = 9_000L,
            touchGrassBreakUntilMs = null,
            keepOverdueBudgetCheck = true
        )

        assertEquals(10_001L, nextAlarmAt)
    }

    @Test
    fun `no future transition returns null`() {
        val nextAlarmAt = PolicyEngine.pickNextAlarmAtMs(
            nowMs = 10_000L,
            scheduleNextTransitionAtMs = null,
            budgetNextCheckAtMs = 9_000L,
            touchGrassBreakUntilMs = null,
            keepOverdueBudgetCheck = false
        )

        assertNull(nextAlarmAt)
    }
}
