package com.ankit.destination.schedule

import com.ankit.destination.data.ScheduleBlock
import com.ankit.destination.data.ScheduleBlockKind
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
        val enabled = blocks.filter { it.enabled }
        if (enabled.isEmpty()) {
            return ScheduleDecision(false, false, emptySet(), "No schedules", emptySet(), null)
        }

        val active = enabled.filter { isActive(it, now) }
        val activeNuclear = active.filter { it.kind == ScheduleBlockKind.NUCLEAR.name }
        val activeGroups = active.filter { it.kind == ScheduleBlockKind.GROUPS.name }
        val shouldLock = activeNuclear.isNotEmpty()
        val strictActive = activeGroups.any { it.strict }
        val blockedGroupIds = activeGroups.flatMap { blockGroups[it.id].orEmpty() }.toSet()
        val reason = if (shouldLock) {
            val names = activeNuclear.joinToString(", ") { it.name }
            "Locked by schedule: $names"
        } else if (blockedGroupIds.isNotEmpty()) {
            val names = activeGroups.joinToString(", ") { it.name }
            "Group schedule active: $names"
        } else {
            "Outside scheduled blocks"
        }
        val next = computeNextTransition(now, enabled)
        return ScheduleDecision(
            shouldLock = shouldLock,
            strictActive = strictActive,
            blockedGroupIds = blockedGroupIds,
            reason = reason,
            activeBlockIds = active.map { it.id }.toSet(),
            nextTransitionAt = next
        )
    }

    fun isActive(block: ScheduleBlock, now: ZonedDateTime): Boolean {
        if (!block.enabled) return false
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
        val enabled = blocks.filter { it.enabled }
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
}
