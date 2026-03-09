package com.ankit.destination

import com.ankit.destination.budgets.BudgetOrchestrator
import com.ankit.destination.policy.UsageInputs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetOrchestratorTest {
    @Test
    fun canReuseFallbackSnapshot_whenStillInSameHourAndDay() {
        val snapshot = BudgetOrchestrator.CachedUsageSnapshot(
            usageInputs = UsageInputs(emptyMap(), emptyMap(), emptyMap()),
            dayKey = "2026-03-09",
            hourStartMs = 1000L,
            capturedAtMs = 1500L
        )

        assertTrue(
            BudgetOrchestrator.canReuseFallbackSnapshot(
                cachedSnapshot = snapshot,
                dayKey = "2026-03-09",
                hourStartMs = 1000L
            )
        )
    }

    @Test
    fun canReuseFallbackSnapshot_rejectsPriorHourSnapshot() {
        val snapshot = BudgetOrchestrator.CachedUsageSnapshot(
            usageInputs = UsageInputs(emptyMap(), emptyMap(), emptyMap()),
            dayKey = "2026-03-09",
            hourStartMs = 1000L,
            capturedAtMs = 1500L
        )

        assertFalse(
            BudgetOrchestrator.canReuseFallbackSnapshot(
                cachedSnapshot = snapshot,
                dayKey = "2026-03-09",
                hourStartMs = 2000L
            )
        )
    }

    @Test
    fun canReuseFallbackSnapshot_rejectsPriorDaySnapshot() {
        val snapshot = BudgetOrchestrator.CachedUsageSnapshot(
            usageInputs = UsageInputs(emptyMap(), emptyMap(), emptyMap()),
            dayKey = "2026-03-09",
            hourStartMs = 1000L,
            capturedAtMs = 1500L
        )

        assertFalse(
            BudgetOrchestrator.canReuseFallbackSnapshot(
                cachedSnapshot = snapshot,
                dayKey = "2026-03-10",
                hourStartMs = 1000L
            )
        )
    }
}
