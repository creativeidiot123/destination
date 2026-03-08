package com.ankit.destination

import com.ankit.destination.data.EmergencyTargetType
import com.ankit.destination.data.GroupTargetMode
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
        val result = evaluate(
            usageInputs = UsageInputs(
                usedTodayMs = mapOf("a" to 120_000L),
                usedHourMs = mapOf("a" to 60_000L),
                opensToday = mapOf("a" to 5)
            ),
            groupPolicies = listOf(
                groupPolicy(
                    groupId = "study",
                    members = setOf("a"),
                    strictEnabled = true,
                    dailyLimitMs = 1L,
                    hourlyLimitMs = 1L,
                    opensPerDay = 1,
                    scheduleBlocked = true
                )
            )
        )

        assertEquals(setOf("a"), result.scheduledBlockedPackages)
        assertTrue(result.usageBlockedPackages.isEmpty())
        assertEquals("GROUP_SCHEDULED_BLOCK", result.primaryReasonByPackage["a"])
        assertEquals(setOf("study"), result.strictInstallActiveGroupIds)
    }

    @Test
    fun emergencyOverride_unblocksGroup_withoutDisablingStrictInstalls() {
        val result = evaluate(
            groupPolicies = listOf(
                groupPolicy(
                    groupId = "study",
                    members = setOf("a", "b"),
                    strictEnabled = true,
                    dailyLimitMs = 60_000L,
                    hourlyLimitMs = 60_000L,
                    opensPerDay = 1,
                    scheduleBlocked = true,
                    emergencyEnabled = true,
                    unlocksPerDay = 2,
                    minutesPerUnlock = 10
                )
            ),
            emergencyStates = listOf(
                EmergencyStateInput(
                    targetType = EmergencyTargetType.GROUP,
                    targetId = "study",
                    unlocksUsedToday = 1,
                    activeUntilEpochMs = 20_000L
                )
            ),
            strictInstallBlockedPackages = setOf("fresh.app")
        )

        assertTrue(result.effectiveBlockedPackages.contains("fresh.app"))
        assertFalse(result.effectiveBlockedPackages.contains("a"))
        assertTrue(result.effectiveBlockedGroupIds.isEmpty())
        assertTrue(result.scheduledBlockedPackages.isEmpty())
        assertEquals(setOf("study"), result.strictInstallActiveGroupIds)
        assertEquals(EffectiveBlockReason.STRICT_INSTALL.name, result.primaryReasonByPackage["fresh.app"])
    }

    @Test
    fun allowlist_appPolicyStillBlocksDirectly() {
        val result = evaluate(
            usageInputs = UsageInputs(
                usedTodayMs = mapOf("safe" to 120_000L),
                usedHourMs = emptyMap(),
                opensToday = emptyMap()
            ),
            appPolicies = listOf(
                appPolicy(
                    packageName = "safe",
                    dailyLimitMs = 60_000L
                )
            ),
            allAppsExcludedPackages = setOf("safe")
        )

        assertEquals(setOf("safe"), result.effectiveBlockedPackages)
        assertEquals("APP_DAILY_CAP", result.primaryReasonByPackage["safe"])
    }

    @Test
    fun allowlist_groupMembershipStillBlocks() {
        val result = evaluate(
            usageInputs = UsageInputs(
                usedTodayMs = mapOf("safe" to 120_000L),
                usedHourMs = emptyMap(),
                opensToday = emptyMap()
            ),
            groupPolicies = listOf(
                groupPolicy(
                    groupId = "g1",
                    members = setOf("safe"),
                    dailyLimitMs = 60_000L
                )
            ),
            allAppsExcludedPackages = setOf("safe")
        )

        assertEquals(setOf("safe"), result.effectiveBlockedPackages)
        assertEquals("GROUP_DAILY_CAP", result.primaryReasonByPackage["safe"])
    }

    @Test
    fun hidden_appsAreExcludedFromDirectAndGroupPolicies() {
        val result = evaluate(
            usageInputs = UsageInputs(
                usedTodayMs = mapOf("hidden.app" to 120_000L),
                usedHourMs = mapOf("hidden.app" to 120_000L),
                opensToday = mapOf("hidden.app" to 5)
            ),
            groupPolicies = listOf(
                groupPolicy(
                    groupId = "g1",
                    members = setOf("hidden.app"),
                    dailyLimitMs = 1L,
                    scheduleBlocked = true
                )
            ),
            appPolicies = listOf(
                appPolicy(
                    packageName = "hidden.app",
                    dailyLimitMs = 1L
                )
            ),
            fullyExemptPackages = setOf("hidden.app"),
            allAppsExcludedPackages = setOf("hidden.app")
        )

        assertTrue(result.effectiveBlockedPackages.isEmpty())
        assertFalse(result.primaryReasonByPackage.containsKey("hidden.app"))
    }

    @Test
    fun allAppsSchedule_excludesAllowlistAndHiddenButTargetsOthers() {
        val result = evaluate(
            groupPolicies = listOf(
                groupPolicy(
                    groupId = "strict-all",
                    members = setOf("manual.member"),
                    strictEnabled = true,
                    targetMode = GroupTargetMode.ALL_APPS,
                    scheduleBlocked = true
                )
            ),
            installedTargetablePackages = setOf("chat.app", "allow.app", "hidden.app", "protected.app"),
            hardProtectedPackages = setOf("protected.app"),
            fullyExemptPackages = setOf("hidden.app"),
            allAppsExcludedPackages = setOf("allow.app", "hidden.app", "protected.app")
        )

        assertEquals(setOf("chat.app"), result.scheduledBlockedPackages)
        assertEquals(setOf("chat.app"), result.effectiveBlockedPackages)
        assertEquals(setOf("strict-all"), result.activeAllAppsGroupIds)
    }

    @Test
    fun allAppsSchedule_doesNotRemoveBudgetReasonsForExplicitMembers() {
        val result = evaluate(
            usageInputs = UsageInputs(
                usedTodayMs = mapOf("budget.app" to 120_000L),
                usedHourMs = mapOf("budget.app" to 120_000L),
                opensToday = emptyMap()
            ),
            groupPolicies = listOf(
                groupPolicy(
                    groupId = "strict-all",
                    members = setOf("budget.app"),
                    strictEnabled = true,
                    targetMode = GroupTargetMode.ALL_APPS,
                    dailyLimitMs = 60_000L
                )
            ),
            installedTargetablePackages = setOf("budget.app", "other.app")
        )

        assertEquals(setOf("budget.app"), result.usageBlockedPackages)
        assertEquals(setOf("budget.app"), result.effectiveBlockedPackages)
        assertEquals("GROUP_DAILY_CAP", result.primaryReasonByPackage["budget.app"])
    }

    @Test
    fun overlappingScheduleAndAppReasonsKeepPackageBlocked() {
        val result = evaluate(
            usageInputs = UsageInputs(
                usedTodayMs = mapOf("shared.app" to 120_000L),
                usedHourMs = mapOf("shared.app" to 10_000L),
                opensToday = emptyMap()
            ),
            groupPolicies = listOf(
                groupPolicy(
                    groupId = "strict-all",
                    members = emptySet(),
                    strictEnabled = true,
                    targetMode = GroupTargetMode.ALL_APPS,
                    scheduleBlocked = true
                )
            ),
            appPolicies = listOf(
                appPolicy(
                    packageName = "shared.app",
                    dailyLimitMs = 60_000L
                )
            ),
            installedTargetablePackages = setOf("shared.app")
        )

        assertEquals(setOf("shared.app"), result.effectiveBlockedPackages)
        assertEquals(
            setOf(
                "GROUP:strict-all:SCHEDULED_BLOCK_ALL_APPS",
                "APP:DAILY_CAP"
            ),
            result.blockReasonsByPackage["shared.app"]
        )
        assertEquals("GROUP_SCHEDULED_BLOCK", result.primaryReasonByPackage["shared.app"])
    }

    private fun evaluate(
        nowMs: Long = 5_000L,
        usageInputs: UsageInputs = UsageInputs(emptyMap(), emptyMap(), emptyMap()),
        groupPolicies: List<GroupPolicyInput> = emptyList(),
        appPolicies: List<AppPolicyInput> = emptyList(),
        emergencyStates: List<EmergencyStateInput> = emptyList(),
        strictInstallBlockedPackages: Set<String> = emptySet(),
        fullyExemptPackages: Set<String> = emptySet(),
        allAppsExcludedPackages: Set<String> = emptySet(),
        installedTargetablePackages: Set<String> = emptySet(),
        hardProtectedPackages: Set<String> = emptySet()
    ) = EffectivePolicyEvaluator.evaluate(
        nowMs = nowMs,
        usageInputs = usageInputs,
        groupPolicies = groupPolicies,
        appPolicies = appPolicies,
        emergencyStates = emergencyStates,
        strictInstallBlockedPackages = strictInstallBlockedPackages,
        fullyExemptPackages = fullyExemptPackages,
        allAppsExcludedPackages = allAppsExcludedPackages,
        installedTargetablePackages = installedTargetablePackages,
        hardProtectedPackages = hardProtectedPackages
    )

    private fun groupPolicy(
        groupId: String,
        members: Set<String>,
        strictEnabled: Boolean = false,
        targetMode: GroupTargetMode = GroupTargetMode.SELECTED_APPS,
        dailyLimitMs: Long = 0L,
        hourlyLimitMs: Long = 0L,
        opensPerDay: Int = 0,
        scheduleBlocked: Boolean = false,
        emergencyEnabled: Boolean = false,
        unlocksPerDay: Int = 0,
        minutesPerUnlock: Int = 0
    ) = GroupPolicyInput(
        groupId = groupId,
        priorityIndex = 0,
        strictEnabled = strictEnabled,
        targetMode = targetMode,
        dailyLimitMs = dailyLimitMs,
        hourlyLimitMs = hourlyLimitMs,
        opensPerDay = opensPerDay,
        members = members,
        emergencyConfig = EmergencyConfigInput(
            enabled = emergencyEnabled,
            unlocksPerDay = unlocksPerDay,
            minutesPerUnlock = minutesPerUnlock
        ),
        scheduleBlocked = scheduleBlocked
    )

    private fun appPolicy(
        packageName: String,
        dailyLimitMs: Long = 0L,
        hourlyLimitMs: Long = 0L,
        opensPerDay: Int = 0,
        scheduleBlocked: Boolean = false
    ) = AppPolicyInput(
        packageName = packageName,
        dailyLimitMs = dailyLimitMs,
        hourlyLimitMs = hourlyLimitMs,
        opensPerDay = opensPerDay,
        emergencyConfig = EmergencyConfigInput(false, 0, 0),
        scheduleBlocked = scheduleBlocked
    )
}
