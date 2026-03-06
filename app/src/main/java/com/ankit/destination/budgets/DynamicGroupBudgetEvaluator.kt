package com.ankit.destination.budgets

data class GroupLimits(
    val groupId: String,
    val dailyLimitMs: Long,
    val hourlyLimitMs: Long,
    val opensPerDay: Int
)

data class GroupState(
    val limits: GroupLimits,
    val members: Set<String>
)

data class BudgetInputs(
    val usedTodayMs: Map<String, Long>,
    val usedHourMs: Map<String, Long>,
    val opensToday: Map<String, Int>
)

data class GroupBudgetResult(
    val blockedPackages: Set<String>,
    val reasons: List<String>,
    val blockedGroupIds: Set<String>
)

object DynamicGroupBudgetEvaluator {
    fun evaluate(
        groups: List<GroupState>,
        inputs: BudgetInputs,
        emergencyDailyBoostMsByGroup: Map<String, Long> = emptyMap()
    ): GroupBudgetResult {
        val blocked = linkedSetOf<String>()
        val reasons = mutableListOf<String>()
        val blockedGroups = linkedSetOf<String>()

        groups.forEach { group ->
            val members = group.members
            if (members.isEmpty()) return@forEach

            if (group.limits.dailyLimitMs <= 0L) {
                reasons += "Group ${group.limits.groupId}: invalid daily limit (${group.limits.dailyLimitMs})"
                return@forEach
            }
            if (group.limits.hourlyLimitMs <= 0L) {
                reasons += "Group ${group.limits.groupId}: invalid hourly limit (${group.limits.hourlyLimitMs})"
                return@forEach
            }
            if (group.limits.opensPerDay <= 0) {
                reasons += "Group ${group.limits.groupId}: invalid opens/day limit (${group.limits.opensPerDay})"
                return@forEach
            }

            val usedTodayGroup = members.sumOf { inputs.usedTodayMs[it] ?: 0L }
            val usedHourGroup = members.sumOf { inputs.usedHourMs[it] ?: 0L }
            val opensGroup = members.sumOf { inputs.opensToday[it] ?: 0 }
            val emergencyBoostMs = emergencyDailyBoostMsByGroup[group.limits.groupId] ?: 0L
            val effectiveDailyLimit = group.limits.dailyLimitMs + emergencyBoostMs

            if (usedTodayGroup >= effectiveDailyLimit) {
                blocked += members
                blockedGroups += group.limits.groupId
                val base = group.limits.dailyLimitMs / 60_000
                val boost = emergencyBoostMs / 60_000
                val effective = effectiveDailyLimit / 60_000
                reasons += "Group ${group.limits.groupId}: daily limit exceeded (used ${usedTodayGroup / 60_000}m / ${effective}m, base=${base}m, emergency=${boost}m)"
                return@forEach
            }
            if (usedHourGroup >= group.limits.hourlyLimitMs) {
                blocked += members
                blockedGroups += group.limits.groupId
                reasons += "Group ${group.limits.groupId}: hourly limit exceeded (used ${usedHourGroup / 60_000}m / ${group.limits.hourlyLimitMs / 60_000}m)"
                return@forEach
            }
            if (opensGroup >= group.limits.opensPerDay) {
                blocked += members
                blockedGroups += group.limits.groupId
                reasons += "Group ${group.limits.groupId}: opens limit exceeded (used $opensGroup / ${group.limits.opensPerDay})"
                return@forEach
            }
        }

        return GroupBudgetResult(
            blockedPackages = blocked,
            reasons = reasons,
            blockedGroupIds = blockedGroups
        )
    }
}
