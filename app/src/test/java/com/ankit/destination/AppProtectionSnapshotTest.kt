package com.ankit.destination

import com.ankit.destination.policy.AppProtectionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProtectionSnapshotTest {

    @Test
    fun allowlist_staysEligibleForManualPolicy_butNotAllAppsExpansion() {
        val snapshot = snapshot(allowlisted = setOf("allow.app"))

        assertTrue(snapshot.isEligibleForManualPolicy("allow.app"))
        assertTrue(snapshot.isEligibleForGroupMembership("allow.app"))
        assertFalse(snapshot.isEligibleForAllAppsExpansion("allow.app"))
        assertEquals(
            "Allowlist apps are excluded from all-apps expansion",
            snapshot.allAppsExclusionReason("allow.app")
        )
    }

    @Test
    fun hidden_appsAreFullyExempt_andHiddenFromStandardLists() {
        val snapshot = snapshot(hidden = setOf("hidden.app"), lockedHidden = setOf("hidden.app"))

        assertFalse(snapshot.isEligibleForManualPolicy("hidden.app"))
        assertFalse(snapshot.isEligibleForGroupMembership("hidden.app"))
        assertFalse(snapshot.isEligibleForAllAppsExpansion("hidden.app"))
        assertTrue(snapshot.shouldHideFromStandardLists("hidden.app"))
        assertEquals("Locked hidden app", snapshot.manualPolicyIneligibilityReason("hidden.app"))
    }

    @Test
    fun runtimeAndCoreProtectedPackagesRemainFullyExempt() {
        val snapshot = snapshot(
            runtimeExempt = setOf("dialer.app"),
            runtimeReasons = mapOf("dialer.app" to "default dialer"),
            hardProtected = setOf("core.app")
        )

        assertFalse(snapshot.isEligibleForManualPolicy("dialer.app"))
        assertFalse(snapshot.isEligibleForAllAppsExpansion("dialer.app"))
        assertEquals("default dialer", snapshot.manualPolicyIneligibilityReason("dialer.app"))

        assertFalse(snapshot.isEligibleForManualPolicy("core.app"))
        assertEquals(
            "Core protected apps do not participate in normal blocking",
            snapshot.manualPolicyIneligibilityReason("core.app")
        )
    }

    private fun snapshot(
        allowlisted: Set<String> = emptySet(),
        hidden: Set<String> = emptySet(),
        lockedHidden: Set<String> = emptySet(),
        runtimeExempt: Set<String> = emptySet(),
        runtimeReasons: Map<String, String> = emptyMap(),
        hardProtected: Set<String> = emptySet()
    ) = AppProtectionSnapshot(
        allowlistedPackages = allowlisted,
        hiddenPackages = hidden,
        lockedHiddenPackages = lockedHidden,
        runtimeExemptPackages = runtimeExempt,
        runtimeExemptionReasons = runtimeReasons,
        hardProtectedPackages = hardProtected
    )
}
