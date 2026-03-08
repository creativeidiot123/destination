package com.ankit.destination

import com.ankit.destination.policy.provisioningPendingReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProvisioningCoordinatorLogicTest {

    @Test
    fun provisioningPendingReason_requiresDeviceOwner_first() {
        val reason = provisioningPendingReason(
            isDeviceOwner = false,
            usageAccessGranted = true,
            accessibilityServiceEnabled = true,
            accessibilityServiceRunning = true
        )

        assertEquals(
            "Device owner not active yet; continue setup to finish enrollment.",
            reason
        )
    }

    @Test
    fun provisioningPendingReason_requiresUsageAccess_afterDeviceOwner() {
        val reason = provisioningPendingReason(
            isDeviceOwner = true,
            usageAccessGranted = false,
            accessibilityServiceEnabled = true,
            accessibilityServiceRunning = true
        )

        assertEquals(
            "Grant Usage Access to Destination before finishing enrollment.",
            reason
        )
    }

    @Test
    fun provisioningPendingReason_requiresAccessibility_afterUsageAccess() {
        val reason = provisioningPendingReason(
            isDeviceOwner = true,
            usageAccessGranted = true,
            accessibilityServiceEnabled = false,
            accessibilityServiceRunning = false
        )

        assertEquals(
            "Enable Destination Accessibility before finishing enrollment.",
            reason
        )
    }

    @Test
    fun provisioningPendingReason_returnsNull_whenPrerequisitesAreMet() {
        val reason = provisioningPendingReason(
            isDeviceOwner = true,
            usageAccessGranted = true,
            accessibilityServiceEnabled = true,
            accessibilityServiceRunning = true
        )

        assertNull(reason)
    }

    @Test
    fun provisioningPendingReason_requiresAccessibilityServiceToBeRunning() {
        val reason = provisioningPendingReason(
            isDeviceOwner = true,
            usageAccessGranted = true,
            accessibilityServiceEnabled = true,
            accessibilityServiceRunning = false
        )

        assertEquals(
            "Destination Accessibility is enabled, but the enforcement service is not running yet.",
            reason
        )
    }
}
