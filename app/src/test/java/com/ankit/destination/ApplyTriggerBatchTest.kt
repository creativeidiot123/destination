package com.ankit.destination

import com.ankit.destination.policy.ApplyTrigger
import com.ankit.destination.policy.ApplyTriggerBatch
import com.ankit.destination.policy.ApplyTriggerCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplyTriggerBatchTest {
    @Test
    fun compatibilityLabel_coalescesDistinctSourcesAndCountsCategories() {
        val batch = ApplyTriggerBatch(
            listOf(
                ApplyTrigger(
                    category = ApplyTriggerCategory.SCHEDULE,
                    source = "ScheduleTickReceiver",
                    detail = "policy_wake"
                ),
                ApplyTrigger(
                    category = ApplyTriggerCategory.PACKAGE_CHANGE,
                    source = "PackageChangeReceiver",
                    detail = "INSTALLED:pkg.one",
                    packages = setOf("pkg.one")
                ),
                ApplyTrigger(
                    category = ApplyTriggerCategory.STRICT_INSTALL_STAGE,
                    source = "PackageChangeReceiver",
                    detail = "pkg.one",
                    packages = setOf("pkg.one"),
                    stagedStrictInstall = true
                )
            )
        )

        assertEquals(1, batch.categoryCounts[ApplyTriggerCategory.SCHEDULE])
        assertEquals(1, batch.categoryCounts[ApplyTriggerCategory.PACKAGE_CHANGE])
        assertEquals(1, batch.categoryCounts[ApplyTriggerCategory.STRICT_INSTALL_STAGE])
        assertTrue(batch.containsStrictInstallStage)
        assertTrue(batch.compatibilityLabel.contains("ScheduleTickReceiver:policy_wake"))
        assertTrue(batch.compatibilityLabel.contains("PackageChangeReceiver:INSTALLED:pkg.one"))
    }
}
