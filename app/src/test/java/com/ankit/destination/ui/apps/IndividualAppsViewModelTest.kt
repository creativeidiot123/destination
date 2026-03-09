package com.ankit.destination.ui.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndividualAppsViewModelTest {

    private val apps = listOf(
        AppUsageItem(
            packageName = "com.example.alpha",
            label = "Alpha",
            usageTimeMs = 120_000L,
            blockMessage = null,
            hasCustomRules = false,
            opensToday = 3
        ),
        AppUsageItem(
            packageName = "com.example.beta",
            label = "Beta Blocked",
            usageTimeMs = 60_000L,
            blockMessage = "Blocked",
            hasCustomRules = true,
            opensToday = 1
        ),
        AppUsageItem(
            packageName = "com.example.gamma",
            label = "Gamma Rules",
            usageTimeMs = 30_000L,
            blockMessage = null,
            hasCustomRules = true,
            opensToday = 0
        )
    )

    @Test
    fun summarizeAppUsageItems_calculatesTotalsOnceFromRawApps() {
        val summary = summarizeAppUsageItems(apps)

        assertEquals(3, summary.totalUsageMinutes)
        assertEquals(2, summary.customRulesCount)
    }

    @Test
    fun filterAppUsageItems_returnsAllAppsForBlankQuery() {
        val filtered = filterAppUsageItems(
            apps = apps,
            query = "   ",
            filter = AppFilter.All
        )

        assertEquals(apps, filtered)
    }

    @Test
    fun filterAppUsageItems_matchesPackageNameSearch() {
        val filtered = filterAppUsageItems(
            apps = apps,
            query = "com.example.beta",
            filter = AppFilter.All
        )

        assertEquals(1, filtered.size)
        assertEquals("com.example.beta", filtered.single().packageName)
    }

    @Test
    fun filterAppUsageItems_returnsBlockedAppsOnly() {
        val filtered = filterAppUsageItems(
            apps = apps,
            query = "",
            filter = AppFilter.Blocked
        )

        assertEquals(1, filtered.size)
        assertEquals("com.example.beta", filtered.single().packageName)
    }

    @Test
    fun filterAppUsageItems_returnsCustomRuleAppsOnly() {
        val filtered = filterAppUsageItems(
            apps = apps,
            query = "",
            filter = AppFilter.CustomRules
        )

        assertEquals(2, filtered.size)
        assertTrue(filtered.any { it.packageName == "com.example.beta" })
        assertTrue(filtered.any { it.packageName == "com.example.gamma" })
    }

    @Test
    fun filterAppUsageItems_combinesQueryAndSelectedFilter() {
        val filtered = filterAppUsageItems(
            apps = apps,
            query = "gamma",
            filter = AppFilter.CustomRules
        )

        assertEquals(1, filtered.size)
        assertEquals("com.example.gamma", filtered.single().packageName)
    }

    @Test
    fun summarizeAppUsageItems_isStableAcrossQueryChanges() {
        val initialSummary = summarizeAppUsageItems(apps)
        filterAppUsageItems(apps, query = "alpha", filter = AppFilter.All)
        filterAppUsageItems(apps, query = "beta", filter = AppFilter.Blocked)
        val finalSummary = summarizeAppUsageItems(apps)

        assertEquals(initialSummary, finalSummary)
    }
}
