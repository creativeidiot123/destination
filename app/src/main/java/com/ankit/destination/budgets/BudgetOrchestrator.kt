package com.ankit.destination.budgets

import android.app.usage.UsageStatsManager
import android.content.Context
import com.ankit.destination.data.GroupEmergencyState
import com.ankit.destination.data.FocusDatabase
import com.ankit.destination.policy.FocusEventId
import com.ankit.destination.policy.FocusLog
import com.ankit.destination.usage.AppGroupRule
import com.ankit.destination.usage.AppLimitRule
import com.ankit.destination.usage.GroupLimitRule
import com.ankit.destination.usage.UsageAccess
import com.ankit.destination.usage.UsageBudgetEvaluator
import com.ankit.destination.usage.UsageReader
import com.ankit.destination.usage.UsageWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.time.ZonedDateTime

data class BudgetEnforceResult(
    val blockedPackages: Set<String>,
    val blockedGroupIds: Set<String>,
    val reason: String,
    val reasons: List<String>,
    val usageAccessGranted: Boolean,
    val nextCheckAtMs: Long?,
    val emergencyDailyBoostMsByGroup: Map<String, Long>,
    val emergencyActiveUntilByGroup: Map<String, Long>
)

data class EmergencyUnlockResult(
    val success: Boolean,
    val message: String,
    val state: GroupEmergencyState?
)

class BudgetOrchestrator(context: Context) {
    private val appContext = context.applicationContext
    private val db by lazy { FocusDatabase.get(appContext) }
    private val usageReader by lazy { UsageReader(appContext) }
    private val usageStatsManager by lazy { appContext.getSystemService(UsageStatsManager::class.java) }

    suspend fun evaluateNow(
        now: ZonedDateTime = ZonedDateTime.now(),
        emergencyAllowlist: Set<String>
    ): BudgetEnforceResult = withContext(Dispatchers.IO) {
        val nowMs = now.toInstant().toEpochMilli()
        val dayKey = UsageWindow.dayKey(now)
        val boundaryCheckAtMs = minOf(
            UsageWindow.nextHourStartMs(now),
            UsageWindow.nextDayStartMs(now)
        )
        if (!UsageAccess.hasUsageAccess(appContext)) {
            FocusLog.w(FocusEventId.BUDGET_EVAL, "Usage access missing; budget evaluation skipped")
            return@withContext BudgetEnforceResult(
                blockedPackages = emptySet(),
                blockedGroupIds = emptySet(),
                reason = "Usage access not granted",
                reasons = listOf("Usage access not granted"),
                usageAccessGranted = false,
                nextCheckAtMs = boundaryCheckAtMs,
                emergencyDailyBoostMsByGroup = emptyMap(),
                emergencyActiveUntilByGroup = emptyMap()
            )
        }
        if (usageStatsManager == null) {
            FocusLog.w(FocusEventId.BUDGET_EVAL, "UsageStatsManager unavailable")
            return@withContext BudgetEnforceResult(
                blockedPackages = emptySet(),
                blockedGroupIds = emptySet(),
                reason = "Usage stats unavailable",
                reasons = listOf("Usage stats unavailable"),
                usageAccessGranted = false,
                nextCheckAtMs = boundaryCheckAtMs,
                emergencyDailyBoostMsByGroup = emptyMap(),
                emergencyActiveUntilByGroup = emptyMap()
            )
        }

        val budgetDao = db.budgetDao()
        budgetDao.clearEmergencyStateBefore(dayKey)
        val appLimits = budgetDao.getEnabledAppLimits()
        val groupLimits = budgetDao.getEnabledGroupLimits()
        val mappings = budgetDao.getAllMappings()
        val emergencyConfigs = budgetDao.getAllGroupEmergencyConfigs().associateBy { it.groupId }
        val emergencyStates = budgetDao.getGroupEmergencyStatesForDay(dayKey).associateBy { it.groupId }

        val emergencyBoostMsByGroup = mutableMapOf<String, Long>()
        val emergencyUntilByGroup = mutableMapOf<String, Long>()
        emergencyConfigs.forEach { (groupId, config) ->
            if (!config.enabled) return@forEach
            val activeUntil = emergencyStates[groupId]?.activeUntilEpochMs ?: return@forEach
            val remainingMs = (activeUntil - nowMs).coerceAtLeast(0L)
            if (remainingMs > 0L) {
                emergencyBoostMsByGroup[groupId] = remainingMs
                emergencyUntilByGroup[groupId] = activeUntil
            }
        }

        if (appLimits.isEmpty() && groupLimits.isEmpty()) {
            return@withContext BudgetEnforceResult(
                blockedPackages = emptySet(),
                blockedGroupIds = emptySet(),
                reason = "No budget limits configured",
                reasons = emptyList(),
                usageAccessGranted = true,
                nextCheckAtMs = boundaryCheckAtMs,
                emergencyDailyBoostMsByGroup = emergencyBoostMsByGroup,
                emergencyActiveUntilByGroup = emergencyUntilByGroup
            )
        }

        val dayStartMs = UsageWindow.startOfDayMs(now)
        val hourStartMs = UsageWindow.startOfHourMs(now)
        val dayUsageMs = queryAggregateUsageMs(dayStartMs, nowMs)
        val hourUsageMs = queryAggregateUsageMs(hourStartMs, nowMs)
        val opensByPkg = usageReader.readOpens(dayStartMs, nowMs)
            .groupingBy { it }
            .eachCount()

        val decision = UsageBudgetEvaluator.evaluate(
            usedTodayMs = dayUsageMs,
            usedHourMs = hourUsageMs,
            opensToday = opensByPkg,
            appLimits = appLimits.map { AppLimitRule(it.packageName, it.dailyLimitMs) },
            groupLimits = groupLimits.map {
                GroupLimitRule(
                    groupId = it.groupId,
                    dailyLimitMs = it.dailyLimitMs,
                    hourlyLimitMs = it.hourlyLimitMs,
                    opensPerDay = it.opensPerDay
                )
            },
            appGroups = mappings.map { AppGroupRule(packageName = it.packageName, groupId = it.groupId) },
            emergencyDailyBoostMsByGroup = emergencyBoostMsByGroup
        )

        val blocked = decision.blockedPackages - emergencyAllowlist
        val earliestEmergencyExpiry = emergencyUntilByGroup.values.minOrNull()
        val nextCheckAtMs = listOfNotNull(
            boundaryCheckAtMs,
            nowMs + POLL_INTERVAL_MS,
            earliestEmergencyExpiry
        ).minOrNull()
        FocusLog.i(
            FocusEventId.BUDGET_EVAL,
            "Budget evaluated blocked=${blocked.size} reason=${decision.reason}"
        )
        BudgetEnforceResult(
            blockedPackages = blocked,
            blockedGroupIds = decision.blockedGroupIds,
            reason = decision.reason,
            reasons = decision.reasons,
            usageAccessGranted = true,
            nextCheckAtMs = nextCheckAtMs,
            emergencyDailyBoostMsByGroup = emergencyBoostMsByGroup,
            emergencyActiveUntilByGroup = emergencyUntilByGroup
        )
    }

    suspend fun activateEmergencyUnlock(
        groupId: String,
        now: ZonedDateTime = ZonedDateTime.now()
    ): EmergencyUnlockResult = withContext(Dispatchers.IO) {
        val budgetDao = db.budgetDao()
        val config = budgetDao.getGroupEmergencyConfig(groupId)
            ?: return@withContext EmergencyUnlockResult(
                success = false,
                message = "Emergency usage is not configured for $groupId",
                state = null
            )
        if (!config.enabled || config.unlocksPerDay <= 0 || config.minutesPerUnlock <= 0) {
            return@withContext EmergencyUnlockResult(
                success = false,
                message = "Emergency usage is disabled for $groupId",
                state = null
            )
        }

        val dayKey = UsageWindow.dayKey(now)
        budgetDao.clearEmergencyStateBefore(dayKey)
        val updated = budgetDao.consumeGroupEmergencyUnlock(dayKey, groupId, now.toInstant().toEpochMilli())
        if (updated == null) {
            val current = budgetDao.getGroupEmergencyState(dayKey, groupId)
            val used = current?.unlocksUsedToday ?: 0
            val remaining = (config.unlocksPerDay - used).coerceAtLeast(0)
            return@withContext EmergencyUnlockResult(
                success = false,
                message = if (remaining <= 0) {
                    "No emergency unlocks remaining today for $groupId"
                } else {
                    "Unable to start emergency unlock for $groupId"
                },
                state = null
            )
        }

        val untilText = updated.activeUntilEpochMs?.let {
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))
        } ?: "unknown"
        EmergencyUnlockResult(
            success = true,
            message = "Emergency unlock active for $groupId until $untilText",
            state = updated
        )
    }

    private fun queryAggregateUsageMs(fromMs: Long, toMs: Long): Map<String, Long> {
        val usageStats = usageStatsManager?.queryAndAggregateUsageStats(fromMs, toMs).orEmpty()
        return usageStats
            .asSequence()
            .filter { (_, stats) -> stats.totalTimeInForeground > 0L }
            .associate { (pkg, stats) -> pkg to stats.totalTimeInForeground }
    }

    private companion object {
        private const val POLL_INTERVAL_MS = 2 * 60_000L
    }
}
