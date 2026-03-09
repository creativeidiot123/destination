package com.ankit.destination

import com.ankit.destination.enforce.AccessibilityMonitorState
import com.ankit.destination.enforce.AccessibilityStatusMonitor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityStatusMonitorTest {

    @Test
    fun serviceRunning_requiresRecentActivityWithinStaleWindow() {
        val nowMs = 500_000L

        assertTrue(
            AccessibilityStatusMonitor.serviceRunning(
                state = AccessibilityMonitorState(
                    enabled = true,
                    lastConnectedAtMs = nowMs - AccessibilityStatusMonitor.SERVICE_STALE_MS + 1L
                ),
                nowMs = nowMs
            )
        )
        assertFalse(
            AccessibilityStatusMonitor.serviceRunning(
                state = AccessibilityMonitorState(
                    enabled = true,
                    lastConnectedAtMs = nowMs - AccessibilityStatusMonitor.SERVICE_STALE_MS - 1L
                ),
                nowMs = nowMs
            )
        )
    }

    @Test
    fun serviceRunning_falseWhenDisconnectIsNewerThanHeartbeat() {
        val nowMs = 1_000_000L

        assertFalse(
            AccessibilityStatusMonitor.serviceRunning(
                state = AccessibilityMonitorState(
                    enabled = true,
                    lastConnectedAtMs = nowMs - 60_000L,
                    lastDisconnectedAtMs = nowMs - 10_000L,
                    lastHeartbeatAtMs = nowMs - 20_000L
                ),
                nowMs = nowMs
            )
        )
    }
}
