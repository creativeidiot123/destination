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
            usageAccessGranted = true
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
            usageAccessGranted = false
        )

        assertEquals(
            "Grant Usage Access to Destination before finishing enrollment.",
            reason
        )
    }

    @Test
    fun provisioningPendingReason_returnsNull_whenPrerequisitesAreMet() {
        val reason = provisioningPendingReason(
            isDeviceOwner = true,
            usageAccessGranted = true
        )

        assertNull(reason)
    }
}
