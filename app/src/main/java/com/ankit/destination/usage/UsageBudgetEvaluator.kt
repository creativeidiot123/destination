package com.ankit.destination.usage

import com.ankit.destination.budgets.BudgetInputs
import com.ankit.destination.budgets.DynamicGroupBudgetEvaluator
import com.ankit.destination.budgets.GroupLimits
import com.ankit.destination.budgets.GroupState

data class AppLimitRule(
    val packageName: String,
    val dailyLimitMs: Long
)

data class GroupLimitRule(
    val groupId: String,
    val dailyLimitMs: Long,
    val hourlyLimitMs: Long,
    val opensPerDay: Int
)

data class AppGroupRule(
    val packageName: String,
    val groupId: String
)

data class BudgetDecision(
    val blockedPackages: Set<String>,
    val reason: String,
    val reasons: List<String>,
    val blockedGroupIds: Set<String>
)

object UsageBudgetEvaluator {
    fun evaluate(
        usedTodayMs: Map<String, Long>,
        usedHourMs: Map<String, Long>,
        opensToday: Map<String, Int>,
        appLimits: List<AppLimitRule>,
        groupLimits: List<GroupLimitRule>,
        appGroups: List<AppGroupRule>,
        emergencyDailyBoostMsByGroup: Map<String, Long> = emptyMap()
    ): BudgetDecision {
        val blocked = linkedSetOf<String>()
        val reasons = mutableListOf<String>()

        val appLimitMap = linkedMapOf<String, Long>()
        appLimits.forEach { limit ->
            val packageName = limit.packageName.trim()
            if (packageName.isBlank()) return@forEach
            if (limit.dailyLimitMs <= 0L) {
                reasons += "App $packageName: invalid daily limit (${limit.dailyLimitMs})"
                return@forEach
            }
            appLimitMap[packageName] = limit.dailyLimitMs
        }
        usedTodayMs.forEach { (pkg, usedMs) ->
            val limit = appLimitMap[pkg] ?: return@forEach
            if (usedMs >= limit) {
                blocked += pkg
                reasons += "App limit reached: $pkg"
            }
        }

        val canonicalGroupsByPackage = linkedMapOf<String, String>()
        appGroups.forEach { mapping ->
            val packageName = mapping.packageName.trim()
            val groupId = mapping.groupId.trim()
            if (packageName.isBlank() || groupId.isBlank()) return@forEach
            canonicalGroupsByPackage[packageName] = groupId
        }
        val membersByGroup = canonicalGroupsByPackage.entries.groupBy(
            keySelector = { it.value },
            valueTransform = { it.key }
        )
        val groupStates = groupLimits.map { limit ->
            GroupState(
                limits = GroupLimits(
                    groupId = limit.groupId,
                    dailyLimitMs = limit.dailyLimitMs,
                    hourlyLimitMs = limit.hourlyLimitMs,
                    opensPerDay = limit.opensPerDay
                ),
                members = membersByGroup[limit.groupId].orEmpty().toSet()
            )
        }
        val groupResult = DynamicGroupBudgetEvaluator.evaluate(
            groups = groupStates,
            inputs = BudgetInputs(
                usedTodayMs = usedTodayMs,
                usedHourMs = usedHourMs,
                opensToday = opensToday
            ),
            emergencyDailyBoostMsByGroup = emergencyDailyBoostMsByGroup
        )
        blocked += groupResult.blockedPackages
        reasons += groupResult.reasons

        return BudgetDecision(
            blockedPackages = blocked,
            reason = reasons.firstOrNull() ?: "No limits exceeded",
            reasons = reasons,
            blockedGroupIds = groupResult.blockedGroupIds
        )
    }
}
