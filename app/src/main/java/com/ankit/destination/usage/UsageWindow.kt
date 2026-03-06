package com.ankit.destination.usage

import java.time.ZonedDateTime

object UsageWindow {
    // Budgets intentionally use device-local calendar boundaries.
    // Timezone changes therefore shift day/hour windows.
    fun dayKey(now: ZonedDateTime): String = now.toLocalDate().toString()

    fun hourKey(now: ZonedDateTime): String =
        "${now.toLocalDate()}-%02d".format(now.hour)

    fun startOfDayMs(now: ZonedDateTime): Long {
        return now.toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli()
    }

    fun nextDayStartMs(now: ZonedDateTime): Long {
        return now.toLocalDate().plusDays(1).atStartOfDay(now.zone).toInstant().toEpochMilli()
    }

    fun startOfHourMs(now: ZonedDateTime): Long {
        return now.withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli()
    }

    fun nextHourStartMs(now: ZonedDateTime): Long {
        return now.withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant().toEpochMilli()
    }
}
