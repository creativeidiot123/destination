package com.ankit.destination.budgets

import android.app.usage.UsageStatsManager
import android.content.Context
import com.ankit.destination.data.EmergencyState
import com.ankit.destination.data.EmergencyTargetType
import com.ankit.destination.data.FocusDatabase
import com.ankit.destination.policy.EmergencyConfigInput
import com.ankit.destination.policy.FocusEventId
import com.ankit.destination.policy.FocusLog
import com.ankit.destination.policy.UsageSnapshotStatus
import com.ankit.destination.policy.UsageInputs
import com.ankit.destination.usage.UsageAccess
import com.ankit.destination.usage.UsageAggregator
import com.ankit.destination.usage.UsageReader
import com.ankit.destination.usage.UsageWindow
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.ZonedDateTime
import java.util.Date
import kotlin.math.max

data class UsageSnapshotResult(
    val usageInputs: UsageInputs,
    val status: UsageSnapshotStatus,
    val nextCheckAtMs: Long?
)

data class EmergencyUnlockResult(
    val success: Boolean,
    val message: String,
    val state: EmergencyState?
)

internal interface PolicyBudgetClient {
    suspend fun readUsageSnapshot(now: ZonedDateTime = ZonedDateTime.now()): UsageSnapshotResult
    suspend fun getActiveEmergencyStates(now: ZonedDateTime = ZonedDateTime.now()): List<EmergencyState>
}

internal class BudgetOrchestrator(context: Context) : PolicyBudgetClient {
    private val appContext = context.applicationContext
    private val db by lazy { FocusDatabase.get(appContext) }
    private val usageReader by lazy { UsageReader(appContext) }
    private val usageStatsManager by lazy { appContext.getSystemService(UsageStatsManager::class.java) }
    @Volatile
    private var lastSuccessfulUsageSnapshot: CachedUsageSnapshot? = null

    suspend fun readUsageSnapshot(): UsageSnapshotResult = readUsageSnapshot(ZonedDateTime.now())

    override suspend fun readUsageSnapshot(now: ZonedDateTime): UsageSnapshotResult = withContext(Dispatchers.IO) {
        FocusLog.d(FocusEventId.BUDGET_SNAPSHOT, "┌── readUsageSnapshot()")
        val nowMs = now.toInstant().toEpochMilli()
        val boundaryCheckAtMs = minOf(
            UsageWindow.nextHourStartMs(now),
            UsageWindow.nextDayStartMs(now)
        )
        if (!UsageAccess.hasUsageAccess(appContext) || usageStatsManager == null) {
            FocusLog.w(FocusEventId.BUDGET_SNAPSHOT, "└── NO usage access — returning empty")
            return@withContext UsageSnapshotResult(
                usageInputs = UsageInputs(emptyMap(), emptyMap(), emptyMap()),
                status = UsageSnapshotStatus.ACCESS_MISSING,
                nextCheckAtMs = boundaryCheckAtMs
            )
        }

        val dayStartMs = UsageWindow.startOfDayMs(now)
        val hourStartMs = UsageWindow.startOfHourMs(now)
        val dayKey = UsageWindow.dayKey(now)
        val usageInputs = runCatching {
            UsageInputs(
                usedTodayMs = queryAggregateUsageMs(dayStartMs, nowMs),
                usedHourMs = queryStrictUsageMs(hourStartMs, nowMs),
                opensToday = usageReader.readOpens(dayStartMs, nowMs).groupingBy { it }.eachCount()
            )
        }.getOrElse { throwable ->
            FocusLog.e(FocusEventId.BUDGET_EVAL, "Usage ingestion failed", throwable)
            val fallbackUsageInputs = lastSuccessfulUsageSnapshot
                ?.takeIf { canReuseFallbackSnapshot(it, dayKey, hourStartMs) }
                ?.usageInputs
                ?: UsageInputs(emptyMap(), emptyMap(), emptyMap())
            return@withContext UsageSnapshotResult(
                usageInputs = fallbackUsageInputs,
                status = UsageSnapshotStatus.INGESTION_FAILED,
                nextCheckAtMs = minOf(boundaryCheckAtMs, nowMs + INGESTION_FAILURE_RETRY_MS)
            )
        }
        lastSuccessfulUsageSnapshot = CachedUsageSnapshot(
            usageInputs = usageInputs,
            dayKey = dayKey,
            hourStartMs = hourStartMs,
            capturedAtMs = nowMs
        )

        // Log top 5 apps by daily usage
        val topApps = usageInputs.usedTodayMs.entries.sortedByDescending { it.value }.take(5)
        FocusLog.d(FocusEventId.BUDGET_SNAPSHOT, "│ usageToday: ${usageInputs.usedTodayMs.size} apps, usageHour: ${usageInputs.usedHourMs.size} apps, opens: ${usageInputs.opensToday.size} apps")
        topApps.forEach { (pkg, ms) ->
            val opens = usageInputs.opensToday[pkg] ?: 0
            val hourMs = usageInputs.usedHourMs[pkg] ?: 0L
            FocusLog.d(FocusEventId.BUDGET_SNAPSHOT, "│   $pkg: day=${ms / 60_000L}min hour=${hourMs / 60_000L}min opens=$opens")
        }
        FocusLog.d(FocusEventId.BUDGET_SNAPSHOT, "└── nextCheckAt=$boundaryCheckAtMs")

        UsageSnapshotResult(
            usageInputs = usageInputs,
            status = UsageSnapshotStatus.OK,
            nextCheckAtMs = boundaryCheckAtMs
        )
    }

    suspend fun activateEmergencyUnlock(
        targetType: EmergencyTargetType,
        targetId: String,
        now: ZonedDateTime = ZonedDateTime.now()
    ): EmergencyUnlockResult = withContext(Dispatchers.IO) {
        FocusLog.d(FocusEventId.BUDGET_EMERGENCY, "┌── activateEmergencyUnlock() type=$targetType id=$targetId")
        val normalizedTargetId = targetId.trim()
        if (normalizedTargetId.isBlank()) {
            FocusLog.w(FocusEventId.BUDGET_EMERGENCY, "└── REJECTED: blank target")
            return@withContext EmergencyUnlockResult(false, "Emergency target is invalid", null)
        }

        val config = loadEmergencyConfig(targetType, normalizedTargetId)
            ?: run {
                FocusLog.w(FocusEventId.BUDGET_EMERGENCY, "└── REJECTED: no config for $normalizedTargetId")
                return@withContext EmergencyUnlockResult(
                    success = false,
                    message = "Emergency usage is not configured for $normalizedTargetId",
                    state = null
                )
            }
        if (!config.enabled || config.unlocksPerDay <= 0 || config.minutesPerUnlock <= 0) {
            FocusLog.w(FocusEventId.BUDGET_EMERGENCY, "└── REJECTED: disabled (enabled=${config.enabled} unlocks=${config.unlocksPerDay} minutes=${config.minutesPerUnlock})")
            return@withContext EmergencyUnlockResult(
                success = false,
                message = "Emergency usage is disabled for $normalizedTargetId",
                state = null
            )
        }
        FocusLog.d(FocusEventId.BUDGET_EMERGENCY, "│ config: unlocks=${config.unlocksPerDay}/day minutes=${config.minutesPerUnlock}/unlock")

        val dayKey = UsageWindow.dayKey(now)
        val nowMs = now.toInstant().toEpochMilli()
        val updated = db.withTransaction {
            val budgetDao = db.budgetDao()
            budgetDao.clearExpiredEmergencyStateBefore(dayKey, nowMs)
            val current = budgetDao.getEmergencyState(dayKey, targetType.name, normalizedTargetId)
                ?: EmergencyState(
                    dayKey = dayKey,
                    targetType = targetType.name,
                    targetId = normalizedTargetId,
                    unlocksUsedToday = 0,
                    activeUntilEpochMs = null
                )
            FocusLog.d(FocusEventId.BUDGET_EMERGENCY, "│ currentState: used=${current.unlocksUsedToday}/${config.unlocksPerDay} activeUntil=${current.activeUntilEpochMs}")
            if (current.unlocksUsedToday >= config.unlocksPerDay) {
                FocusLog.w(FocusEventId.BUDGET_EMERGENCY, "└── REJECTED: no unlocks remaining")
                return@withTransaction null
            }

            val nextUntil = nowMs + (config.minutesPerUnlock * 60_000L)
            val nextState = current.copy(
                unlocksUsedToday = current.unlocksUsedToday + 1,
                activeUntilEpochMs = max(current.activeUntilEpochMs ?: 0L, nextUntil)
            )
            budgetDao.upsertEmergencyState(nextState)
            nextState
        }
        if (updated == null) {
            return@withContext EmergencyUnlockResult(
                success = false,
                message = "No emergency unlocks remaining today for $normalizedTargetId",
                state = null
            )
        }
        val untilText = updated.activeUntilEpochMs?.let {
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))
        } ?: "unknown"
        FocusLog.i(FocusEventId.BUDGET_EMERGENCY, "└── ✅ ACTIVATED: $normalizedTargetId until=$untilText used=${updated.unlocksUsedToday}/${config.unlocksPerDay}")
        EmergencyUnlockResult(
            success = true,
            message = "Emergency unlock active for $normalizedTargetId until $untilText",
            state = updated
        )
    }

    suspend fun getEmergencyStatesForDay(dayKey: String): List<EmergencyState> = withContext(Dispatchers.IO) {
        val nowMs = System.currentTimeMillis()
        db.budgetDao().clearExpiredEmergencyStateBefore(dayKey, nowMs)
        db.budgetDao().getCurrentOrActiveEmergencyStates(dayKey, nowMs)
    }

    override suspend fun getActiveEmergencyStates(now: ZonedDateTime): List<EmergencyState> = withContext(Dispatchers.IO) {
        val dayKey = UsageWindow.dayKey(now)
        val nowMs = now.toInstant().toEpochMilli()
        db.budgetDao().clearExpiredEmergencyStateBefore(dayKey, nowMs)
        db.budgetDao().getCurrentOrActiveEmergencyStates(dayKey, nowMs)
    }

    private suspend fun loadEmergencyConfig(
        targetType: EmergencyTargetType,
        targetId: String
    ): EmergencyConfigInput? {
        val dao = db.budgetDao()
        return when (targetType) {
            EmergencyTargetType.GROUP -> {
                val config = dao.getGroupEmergencyConfig(targetId) ?: return null
                EmergencyConfigInput(
                    enabled = config.enabled,
                    unlocksPerDay = clampEmergencyUnlocksPerDay(config.unlocksPerDay),
                    minutesPerUnlock = clampEmergencyMinutesPerUnlock(config.minutesPerUnlock)
                )
            }
            EmergencyTargetType.APP -> {
                val policy = dao.getAppPolicy(targetId) ?: return null
                EmergencyConfigInput(
                    enabled = policy.emergencyEnabled,
                    unlocksPerDay = clampEmergencyUnlocksPerDay(policy.unlocksPerDay),
                    minutesPerUnlock = clampEmergencyMinutesPerUnlock(policy.minutesPerUnlock)
                )
            }
        }
    }

    private fun queryAggregateUsageMs(fromMs: Long, toMs: Long): Map<String, Long> {
        if (toMs <= fromMs) return emptyMap()
        val usageStats = usageStatsManager?.queryAndAggregateUsageStats(fromMs, toMs).orEmpty()
        return usageStats
            .asSequence()
            .filter { (_, stats) -> stats.totalTimeInForeground > 0L }
            .associate { (pkg, stats) -> pkg to stats.totalTimeInForeground }
    }

    private fun queryStrictUsageMs(fromMs: Long, toMs: Long): Map<String, Long> {
        if (toMs <= fromMs) return emptyMap()
        return UsageAggregator.aggregate(
            slices = usageReader.readStrictWindow(fromMs, toMs).slices,
            opensEvents = emptyList()
        ).timeMsByPkg
    }

    internal data class CachedUsageSnapshot(
        val usageInputs: UsageInputs,
        val dayKey: String,
        val hourStartMs: Long,
        val capturedAtMs: Long
    )

    companion object {
        private const val INGESTION_FAILURE_RETRY_MS = 5L * 60_000L

        internal fun canReuseFallbackSnapshot(
            cachedSnapshot: CachedUsageSnapshot,
            dayKey: String,
            hourStartMs: Long
        ): Boolean {
            return cachedSnapshot.dayKey == dayKey && cachedSnapshot.hourStartMs == hourStartMs
        }
    }
}
