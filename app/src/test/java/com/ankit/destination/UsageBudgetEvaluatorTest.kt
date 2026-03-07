package com.ankit.destination

import com.ankit.destination.usage.AppGroupRule
import com.ankit.destination.usage.AppLimitRule
import com.ankit.destination.usage.GroupLimitRule
import com.ankit.destination.usage.UsageBudgetEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageBudgetEvaluatorTest {

    @Test
    fun invalidAppLimit_isReportedWithoutBlocking() {
        val result = UsageBudgetEvaluator.evaluate(
            usedTodayMs = mapOf("com.example.app" to 5_000L),
            usedHourMs = emptyMap(),
            opensToday = emptyMap(),
            appLimits = listOf(AppLimitRule("com.example.app", 0L)),
            groupLimits = emptyList(),
            appGroups = emptyList()
        )

        assertTrue(result.blockedPackages.isEmpty())
        assertTrue(result.blockedGroupIds.isEmpty())
        assertTrue(result.reasons.first().contains("invalid daily limit"))
    }

    @Test
    fun duplicateAppGroupRules_lastMappingWins() {
        val result = UsageBudgetEvaluator.evaluate(
            usedTodayMs = mapOf("shared" to 60_000L),
            usedHourMs = emptyMap(),
            opensToday = emptyMap(),
            appLimits = emptyList(),
            groupLimits = listOf(
                GroupLimitRule(groupId = "social", dailyLimitMs = 60_000L, hourlyLimitMs = 60_000L, opensPerDay = 10),
                GroupLimitRule(groupId = "games", dailyLimitMs = 10_000_000L, hourlyLimitMs = 10_000_000L, opensPerDay = 10)
            ),
            appGroups = listOf(
                AppGroupRule(packageName = "shared", groupId = "social"),
                AppGroupRule(packageName = "shared", groupId = "games")
            )
        )

        assertFalse(result.blockedPackages.contains("shared"))
        assertTrue(result.blockedGroupIds.isEmpty())
        assertEquals("No limits exceeded", result.reason)
    }
}
