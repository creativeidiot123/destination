package com.ankit.destination

import com.ankit.destination.data.EmergencyTargetType
import com.ankit.destination.policy.AppPolicyInput
import com.ankit.destination.policy.EffectiveBlockReason
import com.ankit.destination.policy.EffectivePolicyEvaluator
import com.ankit.destination.policy.EmergencyConfigInput
import com.ankit.destination.policy.EmergencyStateInput
import com.ankit.destination.policy.GroupPolicyInput
import com.ankit.destination.policy.UsageInputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectivePolicyEvaluatorTest {

    @Test
    fun scheduledBlock_hasPriorityOverUsageCaps() {
        val result = EffectivePolicyEvaluator.evaluate(
            nowMs = 1_000L,
            usageInputs = UsageInputs(
                usedTodayMs = mapOf("a" to 120_000L),
                usedHourMs = mapOf("a" to 60_000L),
                opensToday = mapOf("a" to 5)
            ),
            groupPolicies = listOf(
                GroupPolicyInput(
                    groupId = "study",
                    priorityIndex = 0,
                    strictEnabled = true,
                    dailyLimitMs = 1L,
                    hourlyLimitMs = 1L,
                    opensPerDay = 1,
                    members = setOf("a"),
                    emergencyConfig = EmergencyConfigInput(false, 0, 0),
                    scheduleBlocked = true
                )
            ),
            appPolicies = emptyList(),
            emergencyStates = emptyList(),
            strictInstallBlockedPackages = emptySet(),
            alwaysAllowedPackages = emptySet()
        )

        assertEquals(setOf("a"), result.scheduledBlockedPackages)
        assertTrue(result.usageBlockedPackages.isEmpty())
        assertEquals("GROUP_SCHEDULED_BLOCK", result.primaryReasonByPackage["a"])
        assertEquals(setOf("study"), result.strictInstallActiveGroupIds)
    }

    @Test
    fun emergencyOverride_unblocksGroup_withoutDisablingStrictInstalls() {
        val result = EffectivePolicyEvaluator.evaluate(
            nowMs = 10_000L,
            usageInputs = UsageInputs(
                usedTodayMs = emptyMap(),
                usedHourMs = emptyMap(),
                opensToday = emptyMap()
            ),
            groupPolicies = listOf(
                GroupPolicyInput(
                    groupId = "study",
                    priorityIndex = 0,
                    strictEnabled = true,
                    dailyLimitMs = 60_000L,
                    hourlyLimitMs = 60_000L,
                    opensPerDay = 1,
                    members = setOf("a", "b"),
                    emergencyConfig = EmergencyConfigInput(true, 2, 10),
                    scheduleBlocked = true
                )
            ),
            appPolicies = emptyList(),
            emergencyStates = listOf(
                EmergencyStateInput(
                    targetType = EmergencyTargetType.GROUP,
                    targetId = "study",
                    unlocksUsedToday = 1,
                    activeUntilEpochMs = 20_000L
                )
            ),
            strictInstallBlockedPackages = setOf("fresh.app"),
            alwaysAllowedPackages = emptySet()
        )

        assertTrue(result.effectiveBlockedPackages.contains("fresh.app"))
        assertFalse(result.effectiveBlockedPackages.contains("a"))
        // Emergency must remove the group from effective blocked sets (post-override),
        // even though strict-install protections remain active.
        assertTrue(result.effectiveBlockedGroupIds.isEmpty())
        assertTrue(result.scheduledBlockedPackages.isEmpty())
        assertEquals(setOf("study"), result.strictInstallActiveGroupIds)
        assertEquals(EffectiveBlockReason.STRICT_INSTALL.name, result.primaryReasonByPackage["fresh.app"])
    }

    @Test
    fun multipleGroups_blockUnion_usesLowestPriorityGroupForUiReason() {
        val result = EffectivePolicyEvaluator.evaluate(
            nowMs = 5_000L,
            usageInputs = UsageInputs(
                usedTodayMs = emptyMap(),
                usedHourMs = mapOf("shared" to 45_000L, "a" to 15_000L, "d" to 1_000L),
                opensToday = emptyMap()
            ),
            groupPolicies = listOf(
                GroupPolicyInput(
                    groupId = "g1",
                    priorityIndex = 0,
                    strictEnabled = false,
                    dailyLimitMs = 999_999L,
                    hourlyLimitMs = 60_000L,
                    opensPerDay = 99,
                    members = setOf("shared", "a"),
                    emergencyConfig = EmergencyConfigInput(false, 0, 0),
                    scheduleBlocked = true
                ),
                GroupPolicyInput(
                    groupId = "g2",
                    priorityIndex = 1,
                    strictEnabled = false,
                    dailyLimitMs = 999_999L,
                    hourlyLimitMs = 1L,
                    opensPerDay = 99,
                    members = setOf("shared", "d"),
                    emergencyConfig = EmergencyConfigInput(false, 0, 0),
                    scheduleBlocked = false
                )
            ),
            appPolicies = emptyList(),
            emergencyStates = emptyList(),
            strictInstallBlockedPackages = emptySet(),
            alwaysAllowedPackages = emptySet()
        )

        assertTrue(result.effectiveBlockedPackages.containsAll(setOf("shared", "a", "d")))
        assertEquals("GROUP_SCHEDULED_BLOCK", result.primaryReasonByPackage["shared"])
        assertEquals("GROUP_HOURLY_CAP", result.primaryReasonByPackage["d"])
    }

    @Test
    fun appPolicy_actsAsImplicitSingleAppGroup() {
        val result = EffectivePolicyEvaluator.evaluate(
            nowMs = 5_000L,
            usageInputs = UsageInputs(
                usedTodayMs = mapOf("solo" to 30_000L),
                usedHourMs = mapOf("solo" to 20_000L),
                opensToday = mapOf("solo" to 2)
            ),
            groupPolicies = emptyList(),
            appPolicies = listOf(
                AppPolicyInput(
                    packageName = "solo",
                    dailyLimitMs = 999_999L,
                    hourlyLimitMs = 10_000L,
                    opensPerDay = 5,
                    emergencyConfig = EmergencyConfigInput(false, 0, 0),
                    scheduleBlocked = false
                )
            ),
            emergencyStates = emptyList(),
            strictInstallBlockedPackages = emptySet(),
            alwaysAllowedPackages = emptySet()
        )

        assertEquals(setOf("solo"), result.effectiveBlockedPackages)
        assertEquals("APP_HOURLY_CAP", result.primaryReasonByPackage["solo"])
    }

    @Test
    fun alwaysAllowed_excludesPackagesFromGroupAndAppBlocking() {
        val result = EffectivePolicyEvaluator.evaluate(
            nowMs = 5_000L,
            usageInputs = UsageInputs(
                usedTodayMs = mapOf("safe" to 999_999L),
                usedHourMs = mapOf("safe" to 999_999L),
                opensToday = mapOf("safe" to 999)
            ),
            groupPolicies = listOf(
                GroupPolicyInput(
                    groupId = "g1",
                    priorityIndex = 0,
                    strictEnabled = false,
                    dailyLimitMs = 1L,
                    hourlyLimitMs = 1L,
                    opensPerDay = 1,
                    members = setOf("safe"),
                    emergencyConfig = EmergencyConfigInput(false, 0, 0),
                    scheduleBlocked = true
                )
            ),
            appPolicies = listOf(
                AppPolicyInput(
                    packageName = "safe",
                    dailyLimitMs = 1L,
                    hourlyLimitMs = 1L,
                    opensPerDay = 1,
                    emergencyConfig = EmergencyConfigInput(false, 0, 0),
                    scheduleBlocked = false
                )
            ),
            emergencyStates = emptyList(),
            strictInstallBlockedPackages = emptySet(),
            alwaysAllowedPackages = setOf("safe")
        )

        assertTrue(result.effectiveBlockedPackages.isEmpty())
        assertFalse(result.primaryReasonByPackage.containsKey("safe"))
    }

    @Test
    fun appSchedule_blocksAsPartOfUnion() {
        val result = EffectivePolicyEvaluator.evaluate(
            nowMs = 5_000L,
            usageInputs = UsageInputs(
                usedTodayMs = emptyMap(),
                usedHourMs = emptyMap(),
                opensToday = emptyMap()
            ),
            groupPolicies = emptyList(),
            appPolicies = listOf(
                AppPolicyInput(
                    packageName = "x",
                    dailyLimitMs = 999_999L,
                    hourlyLimitMs = 999_999L,
                    opensPerDay = 99,
                    emergencyConfig = EmergencyConfigInput(false, 0, 0),
                    scheduleBlocked = true
                )
            ),
            emergencyStates = emptyList(),
            strictInstallBlockedPackages = emptySet(),
            alwaysAllowedPackages = emptySet()
        )

        assertEquals(setOf("x"), result.effectiveBlockedPackages)
        assertEquals("APP_SCHEDULED_BLOCK", result.primaryReasonByPackage["x"])
        assertEquals(setOf("x"), result.scheduledBlockedPackages)
    }
}
