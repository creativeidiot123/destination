package com.ankit.destination

import com.ankit.destination.budgets.BudgetInputs
import com.ankit.destination.budgets.DynamicGroupBudgetEvaluator
import com.ankit.destination.budgets.GroupLimits
import com.ankit.destination.budgets.GroupState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicGroupBudgetEvaluatorTest {

    @Test
    fun dailyCap_blocksWholeGroup_whenTotalExceedsLimit() {
        val group = GroupState(
            limits = GroupLimits("social", dailyLimitMs = 60 * 60_000L, hourlyLimitMs = 120 * 60_000L, opensPerDay = 100),
            members = setOf("a", "b")
        )
        val result = DynamicGroupBudgetEvaluator.evaluate(
            groups = listOf(group),
            inputs = BudgetInputs(
                usedTodayMs = mapOf("a" to 55 * 60_000L, "b" to 10 * 60_000L),
                usedHourMs = emptyMap(),
                opensToday = emptyMap()
            )
        )
        assertEquals(setOf("a", "b"), result.blockedPackages)
        assertEquals(setOf("social"), result.blockedGroupIds)
        assertTrue(result.reasons.first().contains("daily limit exceeded"))
    }

    @Test
    fun dailyCap_doesNotBlock_whenTotalBelowLimit() {
        val group = GroupState(
            limits = GroupLimits("social", dailyLimitMs = 60 * 60_000L, hourlyLimitMs = 120 * 60_000L, opensPerDay = 100),
            members = setOf("a", "b")
        )
        val result = DynamicGroupBudgetEvaluator.evaluate(
            groups = listOf(group),
            inputs = BudgetInputs(
                usedTodayMs = mapOf("a" to 30 * 60_000L, "b" to 29 * 60_000L),
                usedHourMs = emptyMap(),
                opensToday = emptyMap()
            )
        )
        assertTrue(result.blockedPackages.isEmpty())
        assertTrue(result.blockedGroupIds.isEmpty())
    }

    @Test
    fun hourlyCap_blocksWholeGroup_whenTotalHitsLimit() {
        val group = GroupState(
            limits = GroupLimits("social", dailyLimitMs = 300 * 60_000L, hourlyLimitMs = 45 * 60_000L, opensPerDay = 100),
            members = setOf("a", "b")
        )
        val result = DynamicGroupBudgetEvaluator.evaluate(
            groups = listOf(group),
            inputs = BudgetInputs(
                usedTodayMs = emptyMap(),
                usedHourMs = mapOf("a" to 20 * 60_000L, "b" to 25 * 60_000L),
                opensToday = emptyMap()
            )
        )
        assertEquals(setOf("a", "b"), result.blockedPackages)
        assertEquals(setOf("social"), result.blockedGroupIds)
        assertTrue(result.reasons.first().contains("hourly limit exceeded"))
    }

    @Test
    fun opensCap_blocksWholeGroup_whenTotalHitsLimit() {
        val group = GroupState(
            limits = GroupLimits("social", dailyLimitMs = 300 * 60_000L, hourlyLimitMs = 120 * 60_000L, opensPerDay = 5),
            members = setOf("a", "b")
        )
        val result = DynamicGroupBudgetEvaluator.evaluate(
            groups = listOf(group),
            inputs = BudgetInputs(
                usedTodayMs = emptyMap(),
                usedHourMs = emptyMap(),
                opensToday = mapOf("a" to 2, "b" to 3)
            )
        )
        assertEquals(setOf("a", "b"), result.blockedPackages)
        assertEquals(setOf("social"), result.blockedGroupIds)
        assertTrue(result.reasons.first().contains("opens limit exceeded"))
    }

    @Test
    fun zeroMembers_groupIsIgnored() {
        val group = GroupState(
            limits = GroupLimits("social", dailyLimitMs = 60_000L, hourlyLimitMs = 60_000L, opensPerDay = 2),
            members = emptySet()
        )
        val result = DynamicGroupBudgetEvaluator.evaluate(
            groups = listOf(group),
            inputs = BudgetInputs(
                usedTodayMs = mapOf("a" to 999_999L),
                usedHourMs = mapOf("a" to 999_999L),
                opensToday = mapOf("a" to 999)
            )
        )
        assertTrue(result.blockedPackages.isEmpty())
        assertTrue(result.blockedGroupIds.isEmpty())
        assertTrue(result.reasons.isEmpty())
    }

    @Test
    fun invalidLimit_isIgnoredAndReported_asMisconfig() {
        val group = GroupState(
            limits = GroupLimits("social", dailyLimitMs = 0L, hourlyLimitMs = 60_000L, opensPerDay = 2),
            members = setOf("a", "b")
        )
        val result = DynamicGroupBudgetEvaluator.evaluate(
            groups = listOf(group),
            inputs = BudgetInputs(
                usedTodayMs = emptyMap(),
                usedHourMs = emptyMap(),
                opensToday = emptyMap()
            )
        )
        assertTrue(result.blockedPackages.isEmpty())
        assertTrue(result.blockedGroupIds.isEmpty())
        assertTrue(result.reasons.first().contains("invalid daily limit"))
    }

    @Test
    fun packageInMultipleGroups_blockedIfAnyGroupExceeded() {
        val social = GroupState(
            limits = GroupLimits("social", dailyLimitMs = 60_000L, hourlyLimitMs = 60_000L, opensPerDay = 100),
            members = setOf("shared", "a")
        )
        val games = GroupState(
            limits = GroupLimits("games", dailyLimitMs = 10_000_000L, hourlyLimitMs = 10_000_000L, opensPerDay = 100),
            members = setOf("shared", "b")
        )
        val result = DynamicGroupBudgetEvaluator.evaluate(
            groups = listOf(social, games),
            inputs = BudgetInputs(
                usedTodayMs = mapOf("shared" to 60_000L),
                usedHourMs = emptyMap(),
                opensToday = emptyMap()
            )
        )
        assertTrue(result.blockedPackages.contains("shared"))
        assertTrue(result.blockedPackages.contains("a"))
        assertTrue(!result.blockedPackages.contains("b"))
        assertTrue(result.blockedGroupIds.contains("social"))
        assertTrue(!result.blockedGroupIds.contains("games"))
    }

    @Test
    fun emergencyBoost_onlyAffectsDailyCap() {
        val group = GroupState(
            limits = GroupLimits("social", dailyLimitMs = 60 * 60_000L, hourlyLimitMs = 120 * 60_000L, opensPerDay = 100),
            members = setOf("a", "b")
        )
        val result = DynamicGroupBudgetEvaluator.evaluate(
            groups = listOf(group),
            inputs = BudgetInputs(
                usedTodayMs = mapOf("a" to 55 * 60_000L, "b" to 10 * 60_000L),
                usedHourMs = emptyMap(),
                opensToday = emptyMap()
            ),
            emergencyDailyBoostMsByGroup = mapOf("social" to 10 * 60_000L)
        )
        assertTrue(result.blockedPackages.isEmpty())
        assertTrue(result.blockedGroupIds.isEmpty())
    }

    @Test
    fun emergencyBoost_doesNotBypassHourlyOrOpens() {
        val group = GroupState(
            limits = GroupLimits("social", dailyLimitMs = 600 * 60_000L, hourlyLimitMs = 30 * 60_000L, opensPerDay = 3),
            members = setOf("a", "b")
        )
        val result = DynamicGroupBudgetEvaluator.evaluate(
            groups = listOf(group),
            inputs = BudgetInputs(
                usedTodayMs = mapOf("a" to 1 * 60_000L),
                usedHourMs = mapOf("a" to 31 * 60_000L),
                opensToday = mapOf("a" to 3)
            ),
            emergencyDailyBoostMsByGroup = mapOf("social" to 120 * 60_000L)
        )
        assertEquals(setOf("a", "b"), result.blockedPackages)
        assertEquals(setOf("social"), result.blockedGroupIds)
    }
}
