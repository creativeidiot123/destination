package com.ankit.destination.schedule

import com.ankit.destination.data.ScheduleBlock
import com.ankit.destination.data.ScheduleBlockKind
import com.ankit.destination.policy.FocusEventId
import com.ankit.destination.policy.FocusLog
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

data class ScheduleDecision(
    val shouldLock: Boolean,
    val strictActive: Boolean,
    val blockedGroupIds: Set<String>,
    val reason: String,
    val activeBlockIds: Set<Long>,
    val nextTransitionAt: ZonedDateTime?
)

object ScheduleEvaluator {
    fun evaluate(
        now: ZonedDateTime,
        blocks: List<ScheduleBlock>,
        blockGroups: Map<Long, Set<String>> = emptyMap()
    ): ScheduleDecision {
        val enabled = blocks.filter { it.enabled && it.kind == ScheduleBlockKind.GROUPS.name }
        FocusLog.d(FocusEventId.SCHEDULE_EVAL, "┌── ScheduleEvaluator.evaluate() total=${blocks.size} enabled=${enabled.size}")
        if (enabled.isEmpty()) {
            FocusLog.d(FocusEventId.SCHEDULE_EVAL, "└── No enabled group schedules")
            return ScheduleDecision(false, false, emptySet(), "No schedules", emptySet(), null)
        }

        val active = enabled.filter { isActive(it, now) }
        val strictActive = active.any { it.strict }
        val blockedGroupIds = active.flatMap { blockGroups[it.id].orEmpty() }.toSet()
        val activeNames = active.joinToString(", ") { it.name }
        val reason = if (strictActive) {
            "Strict group schedule active: $activeNames"
        } else if (active.isNotEmpty()) {
            "Group schedule active: $activeNames"
        } else {
            "Outside scheduled blocks"
        }
        val next = computeNextTransition(now, enabled)

        FocusLog.d(FocusEventId.SCHEDULE_EVAL, "│ active=${active.size} strict=$strictActive blockedGroups=${blockedGroupIds.size}")
        if (active.isNotEmpty()) {
            active.forEach { block ->
                val groups = blockGroups[block.id].orEmpty()
                FocusLog.d(FocusEventId.SCHEDULE_EVAL, "│   active block: id=${block.id} name=${block.name} strict=${block.strict} start=${block.startMinute} end=${block.endMinute} groups=${groups.joinToString(",")}")
            }
        }
        if (blockedGroupIds.isNotEmpty()) {
            FocusLog.d(FocusEventId.SCHEDULE_EVAL, "│ blockedGroupIds: ${blockedGroupIds.joinToString(",")}")
        }
        FocusLog.d(FocusEventId.SCHEDULE_EVAL, "└── nextTransition=${next} reason=$reason")

        return ScheduleDecision(
            shouldLock = active.isNotEmpty(),
            strictActive = strictActive,
            blockedGroupIds = blockedGroupIds,
            reason = reason,
            activeBlockIds = active.map { it.id }.toSet(),
            nextTransitionAt = next
        )
    }

    fun isActive(block: ScheduleBlock, now: ZonedDateTime): Boolean {
        if (!block.enabled) return false
        if (!hasValidWindow(block)) return false
        val minute = now.toLocalTime().toSecondOfDay() / 60
        val todayBit = dayToBit(now.dayOfWeek)

        return if (!block.isCrossMidnight()) {
            (block.daysMask and todayBit) != 0 && minute in block.startMinute until block.endMinute
        } else {
            val yesterdayBit = dayToBit(now.minusDays(1).dayOfWeek)
            ((block.daysMask and todayBit) != 0 && minute >= block.startMinute) ||
                ((block.daysMask and yesterdayBit) != 0 && minute < block.endMinute)
        }
    }

    fun computeNextTransition(now: ZonedDateTime, blocks: List<ScheduleBlock>): ZonedDateTime? {
        val enabled = blocks.filter { it.enabled && hasValidWindow(it) }
        if (enabled.isEmpty()) return null

        val zone = now.zone
        val startSearch = now.truncatedTo(ChronoUnit.MINUTES)
        var best: ZonedDateTime? = null

        for (dayOffset in -1..8) {
            val date = startSearch.toLocalDate().plusDays(dayOffset.toLong())
            val dayBit = dayToBit(date.dayOfWeek)

            enabled.forEach { block ->
                if ((block.daysMask and dayBit) == 0) return@forEach

                val startCandidate = resolveZoned(date, block.startMinute, zone)
                val endDate = if (block.isCrossMidnight()) date.plusDays(1) else date
                val endCandidate = resolveZoned(endDate, block.endMinute, zone)

                if (startCandidate.isAfter(startSearch)) {
                    if (best == null || startCandidate.isBefore(best)) best = startCandidate
                }
                if (endCandidate.isAfter(startSearch)) {
                    if (best == null || endCandidate.isBefore(best)) best = endCandidate
                }
            }
        }
        return best
    }

    private fun resolveZoned(date: LocalDate, minuteOfDay: Int, zone: ZoneId): ZonedDateTime {
        val clamped = minuteOfDay.coerceIn(0, 1439)
        val localTime = LocalTime.of(clamped / 60, clamped % 60)
        val ldt = LocalDateTime.of(date, localTime)
        val rules = zone.rules
        val offsets = rules.getValidOffsets(ldt)

        return when {
            offsets.size == 1 -> ZonedDateTime.ofLocal(ldt, zone, offsets.first())
            offsets.isEmpty() -> {
                val transition = rules.getTransition(ldt)
                transition.dateTimeAfter.atZone(zone)
            }
            else -> ZonedDateTime.ofLocal(ldt, zone, offsets.first())
        }
    }

    fun dayToBit(day: DayOfWeek): Int {
        val idx = (day.value + 6) % 7
        return 1 shl idx
    }

    private fun hasValidWindow(block: ScheduleBlock): Boolean {
        return block.daysMask != 0 &&
            block.startMinute in 0..1439 &&
            block.endMinute in 0..1439 &&
            block.startMinute != block.endMinute
    }
}
