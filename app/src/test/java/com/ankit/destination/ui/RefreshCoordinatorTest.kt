package com.ankit.destination.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshCoordinatorTest {
    @Test
    fun firstRequest_startsImmediately() {
        val coordinator = RefreshCoordinator(
            staleAfterMs = 1_000L,
            clock = { 1_000L }
        )

        assertTrue(coordinator.tryStart())
    }

    @Test
    fun duplicateForcedRequestsDuringLoad_collapseIntoSingleFollowUpRun() {
        var nowMs = 1_000L
        val coordinator = RefreshCoordinator(
            staleAfterMs = 1_000L,
            clock = { nowMs }
        )

        assertTrue(coordinator.tryStart())
        assertFalse(coordinator.tryStart(force = true))
        assertFalse(coordinator.tryStart(force = true))

        nowMs += 100L
        assertTrue(coordinator.finish(success = true))
        assertFalse(coordinator.tryStart())

        nowMs += 100L
        assertFalse(coordinator.finish(success = true))
    }

    @Test
    fun refreshWithinFreshnessWindow_skipsReload() {
        var nowMs = 1_000L
        val coordinator = RefreshCoordinator(
            staleAfterMs = 1_000L,
            clock = { nowMs }
        )

        assertTrue(coordinator.tryStart())
        assertFalse(coordinator.finish(success = true))

        nowMs += 500L
        assertFalse(coordinator.tryStart())
    }

    @Test
    fun forcedRefresh_bypassesFreshnessWindow() {
        var nowMs = 1_000L
        val coordinator = RefreshCoordinator(
            staleAfterMs = 1_000L,
            clock = { nowMs }
        )

        assertTrue(coordinator.tryStart())
        assertFalse(coordinator.finish(success = true))

        nowMs += 500L
        assertTrue(coordinator.tryStart(force = true))
    }
}
