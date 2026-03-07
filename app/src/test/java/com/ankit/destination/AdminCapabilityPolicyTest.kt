package com.ankit.destination

import com.ankit.destination.security.AdminCapability
import com.ankit.destination.security.CapabilityPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminCapabilityPolicyTest {

    @Test
    fun additiveActions_remainAvailableWhileLocked() {
        assertFalse(CapabilityPolicy.requiresPassword(AdminCapability.ADD_BLOCKLIST_APP))
        assertFalse(CapabilityPolicy.requiresPassword(AdminCapability.ADD_APP_TO_GROUP))
    }

    @Test
    fun weakeningActions_stillRequirePassword() {
        assertTrue(CapabilityPolicy.requiresPassword(AdminCapability.REMOVE_APP_FROM_GROUP))
        assertTrue(CapabilityPolicy.requiresPassword(AdminCapability.REMOVE_ALLOWLIST_APP))
    }
}
