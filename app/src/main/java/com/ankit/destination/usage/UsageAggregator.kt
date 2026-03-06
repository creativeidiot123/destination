package com.ankit.destination.usage

data class UsageTotals(
    val timeMsByPkg: Map<String, Long>,
    val opensByPkg: Map<String, Int>
)

object UsageAggregator {
    fun aggregate(slices: List<ForegroundSlice>, opensEvents: List<String>): UsageTotals {
        val time = mutableMapOf<String, Long>()
        slices.forEach { slice ->
            val duration = (slice.endMs - slice.startMs).coerceAtLeast(0L)
            time[slice.packageName] = (time[slice.packageName] ?: 0L) + duration
        }

        val opens = mutableMapOf<String, Int>()
        opensEvents.forEach { pkg ->
            opens[pkg] = (opens[pkg] ?: 0) + 1
        }
        return UsageTotals(timeMsByPkg = time, opensByPkg = opens)
    }
}
