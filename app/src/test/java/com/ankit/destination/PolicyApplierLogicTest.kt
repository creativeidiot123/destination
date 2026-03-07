package com.ankit.destination

import com.ankit.destination.policy.desiredUserControlDisabledPackages
import org.junit.Assert.assertEquals
import org.junit.Test

class PolicyApplierLogicTest {

    @Test
    fun desiredUserControlDisabledPackages_returnsNormalizedPackages_onAndroid11AndLater() {
        val packages = desiredUserControlDisabledPackages(
            uninstallProtectedPackages = setOf(" com.example.one ", "", "com.example.two"),
            sdkInt = 30
        )

        assertEquals(setOf("com.example.one", "com.example.two"), packages)
    }

    @Test
    fun desiredUserControlDisabledPackages_returnsEmpty_beforeAndroid11() {
        val packages = desiredUserControlDisabledPackages(
            uninstallProtectedPackages = setOf("com.example.one"),
            sdkInt = 29
        )

        assertEquals(emptySet<String>(), packages)
    }
}
