package com.ankit.destination

import com.ankit.destination.policy.AppPolicyInput
import com.ankit.destination.policy.EmergencyConfigInput
import com.ankit.destination.policy.GroupPolicyInput
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.policy.UsageInputs
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

    @Test
    fun `closest budget wake mirrors evaluator filtering`() {
        val nextWakeAt = PolicyEngine.computeClosestBudgetCheckAtMs(
            nowMs = 10_000L,
            baseNextCheckAtMs = 100_000L,
            groupPolicies = listOf(
                GroupPolicyInput(
                    groupId = "night",
                    priorityIndex = 0,
                    strictEnabled = false,
                    dailyLimitMs = 15_000L,
                    hourlyLimitMs = 0L,
                    opensPerDay = 0,
                    members = setOf("allowed.app", "limited.app"),
                    emergencyConfig = EmergencyConfigInput(false, 0, 0),
                    scheduleBlocked = false
                )
            ),
            appPolicies = listOf(
                AppPolicyInput(
                    packageName = "limited.app",
                    dailyLimitMs = 0L,
                    hourlyLimitMs = 12_000L,
                    opensPerDay = 0,
                    emergencyConfig = EmergencyConfigInput(false, 0, 0),
                    scheduleBlocked = false
                )
            ),
            usageInputs = UsageInputs(
                usedTodayMs = mapOf("allowed.app" to 9_000L, "limited.app" to 2_000L),
                usedHourMs = mapOf("limited.app" to 4_000L),
                opensToday = emptyMap()
            ),
            fullyExemptPackages = emptySet(),
            emergencyStates = emptyList()
        )

        assertEquals(16_000L, nextWakeAt)
    }

    @Test
    fun `reliability fallback is selected when no earlier wake exists`() {
        val wake = PolicyEngine.planNextPolicyWake(
            nowMs = 10_000L,
            scheduleNextTransitionAtMs = null,
            budgetNextCheckAtMs = null,
            emergencyUnlockExpiresAtMs = null,
            touchGrassBreakUntilMs = null,
            reliabilityFallbackAtMs = 910_000L
        )

        assertEquals(910_000L, wake.atMs)
        assertEquals("reliability_tick", wake.reason)
    }

    @Test
    fun `emergency expiry beats later reliability wake`() {
        val wake = PolicyEngine.planNextPolicyWake(
            nowMs = 10_000L,
            scheduleNextTransitionAtMs = null,
            budgetNextCheckAtMs = 40_000L,
            emergencyUnlockExpiresAtMs = 25_000L,
            touchGrassBreakUntilMs = null,
            reliabilityFallbackAtMs = 910_000L
        )

        assertEquals(25_000L, wake.atMs)
        assertEquals("emergency_expiry", wake.reason)
    }
}
