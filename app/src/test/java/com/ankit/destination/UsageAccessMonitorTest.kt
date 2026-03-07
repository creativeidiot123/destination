package com.ankit.destination

import com.ankit.destination.usage.UsageAccessMonitor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageAccessMonitorTest {
    @Test
    fun shouldReuseRecentRefresh_onlyWhenWithinThrottleWindow() {
        assertTrue(
            UsageAccessMonitor.shouldReuseRecentRefresh(
                lastCheckAtMs = 1_000L,
                nowMs = 1_500L,
                minimumIntervalMs = 750L
            )
        )
        assertFalse(
            UsageAccessMonitor.shouldReuseRecentRefresh(
                lastCheckAtMs = 2_000L,
                nowMs = 1_000L,
                minimumIntervalMs = 750L
            )
        )
        assertFalse(
            UsageAccessMonitor.shouldReuseRecentRefresh(
                lastCheckAtMs = 1_000L,
                nowMs = 1_800L,
                minimumIntervalMs = 750L
            )
        )
        assertFalse(
            UsageAccessMonitor.shouldReuseRecentRefresh(
                lastCheckAtMs = 0L,
                nowMs = 1_500L,
                minimumIntervalMs = 750L
            )
        )
        assertFalse(
            UsageAccessMonitor.shouldReuseRecentRefresh(
                lastCheckAtMs = 1_000L,
                nowMs = 1_500L,
                minimumIntervalMs = 0L
            )
        )
    }

    @Test
    fun shouldHandleUsageAccessOpChange_matchingPackage_accepted() {
        assertTrue(
            UsageAccessMonitor.shouldHandleUsageAccessOpChange(
                op = "android:get_usage_stats",
                packageName = "com.ankit.destination",
                ownPackageName = "com.ankit.destination"
            )
        )
    }

    @Test
    fun shouldHandleUsageAccessOpChange_nullPackage_accepted() {
        assertTrue(
            UsageAccessMonitor.shouldHandleUsageAccessOpChange(
                op = "android:get_usage_stats",
                packageName = null,
                ownPackageName = "com.ankit.destination"
            )
        )
    }

    @Test
    fun shouldHandleUsageAccessOpChange_blankPackage_accepted() {
        assertTrue(
            UsageAccessMonitor.shouldHandleUsageAccessOpChange(
                op = "android:get_usage_stats",
                packageName = "",
                ownPackageName = "com.ankit.destination"
            )
        )
    }

    @Test
    fun shouldHandleUsageAccessOpChange_wrongOp_rejected() {
        assertFalse(
            UsageAccessMonitor.shouldHandleUsageAccessOpChange(
                op = "android:coarse_location",
                packageName = "com.ankit.destination",
                ownPackageName = "com.ankit.destination"
            )
        )
    }

    @Test
    fun shouldHandleUsageAccessOpChange_differentPackage_rejected() {
        assertFalse(
            UsageAccessMonitor.shouldHandleUsageAccessOpChange(
                op = "android:get_usage_stats",
                packageName = "com.other.app",
                ownPackageName = "com.ankit.destination"
            )
        )
    }

    @Test
    fun shouldSuppressPolicyRefresh_clockMovedBackwards_neverSuppresses() {
        assertFalse(
            UsageAccessMonitor.shouldSuppressPolicyRefresh(
                lastPolicyRefreshGranted = false,
                nextUsageAccessGranted = false,
                lastPolicyRefreshAtMs = 2_000L,
                nowMs = 1_000L,
                debounceMs = 1_000L
            )
        )
    }
}
