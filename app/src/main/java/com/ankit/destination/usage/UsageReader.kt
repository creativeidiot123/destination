package com.ankit.destination.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

data class ForegroundSlice(
    val packageName: String,
    val startMs: Long,
    val endMs: Long
)

data class UsageReadResult(
    val slices: List<ForegroundSlice>,
    val openPkgs: List<String>
)

internal data class UsageEventRecord(
    val packageName: String,
    val eventType: Int,
    val timestampMs: Long
)

class UsageReader(context: Context) {
    private val usm = context.getSystemService(UsageStatsManager::class.java)

    fun read(fromMs: Long, toMs: Long): UsageReadResult {
        return parseRecords(readRecords(fromMs, toMs), fromMs, toMs)
    }

    fun readStrictWindow(fromMs: Long, toMs: Long): UsageReadResult {
        if (toMs <= fromMs) return UsageReadResult(emptyList(), emptyList())
        val queryFromMs = (fromMs - WINDOW_STATE_LOOKBACK_MS).coerceAtLeast(0L)
        return parseRecordsWithHistory(readRecords(queryFromMs, toMs), fromMs, toMs)
    }

    private fun readRecords(fromMs: Long, toMs: Long): List<UsageEventRecord> {
        val usageStatsManager = usm ?: return emptyList()
        if (toMs <= fromMs) return emptyList()
        val events = usageStatsManager.queryEvents(fromMs, toMs)
        val event = UsageEvents.Event()
        val records = mutableListOf<UsageEventRecord>()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val packageName = event.packageName ?: continue
            records += UsageEventRecord(
                packageName = packageName,
                eventType = event.eventType,
                timestampMs = event.timeStamp
            )
        }
        return records
    }

    internal companion object {
        private const val OPENS_DEBOUNCE_MS = 10_000L
        private const val WINDOW_STATE_LOOKBACK_MS = 24L * 60L * 60L * 1000L

        fun parseRecords(records: List<UsageEventRecord>, fromMs: Long, toMs: Long): UsageReadResult {
            if (toMs <= fromMs) return UsageReadResult(emptyList(), emptyList())
            val slices = mutableListOf<ForegroundSlice>()
            val opens = mutableListOf<String>()
            val seenForegroundPackages = mutableSetOf<String>()
            val synthesizedBackgroundPackages = mutableSetOf<String>()
            var currentForegroundPkg: String? = null
            var currentStartMs: Long? = null

            records.forEach { record ->
                val packageName = record.packageName.trim()
                if (packageName.isBlank()) return@forEach
                when (record.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                        if (currentForegroundPkg != packageName) {
                            opens += packageName
                        }
                        if (currentForegroundPkg != null && currentStartMs != null && currentForegroundPkg != packageName) {
                            slices += ForegroundSlice(
                                packageName = currentForegroundPkg!!,
                                startMs = currentStartMs!!,
                                endMs = record.timestampMs
                            )
                        }
                        currentForegroundPkg = packageName
                        currentStartMs = record.timestampMs.coerceAtLeast(fromMs)
                        seenForegroundPackages += packageName
                    }

                    UsageEvents.Event.MOVE_TO_BACKGROUND,
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        when {
                            currentForegroundPkg == packageName && currentStartMs != null -> {
                                slices += ForegroundSlice(
                                    packageName = packageName,
                                    startMs = currentStartMs!!,
                                    endMs = record.timestampMs
                                )
                                currentForegroundPkg = null
                                currentStartMs = null
                            }
                            currentForegroundPkg == null &&
                                packageName !in seenForegroundPackages &&
                                synthesizedBackgroundPackages.add(packageName) -> {
                                // App likely entered foreground before `fromMs`; account once from window start.
                                slices += ForegroundSlice(
                                    packageName = packageName,
                                    startMs = fromMs,
                                    endMs = record.timestampMs
                                )
                            }
                        }
                    }
                }
            }

            if (currentForegroundPkg != null && currentStartMs != null) {
                slices += ForegroundSlice(
                    packageName = currentForegroundPkg!!,
                    startMs = currentStartMs!!,
                    endMs = toMs
                )
            }

            val normalized = slices
                .map { slice ->
                    val start = slice.startMs.coerceAtLeast(fromMs)
                    val end = slice.endMs.coerceAtMost(toMs)
                    slice.copy(startMs = start, endMs = end)
                }
                .filter { it.endMs > it.startMs }
            return UsageReadResult(slices = normalized, openPkgs = opens)
        }

        fun parseRecordsWithHistory(records: List<UsageEventRecord>, fromMs: Long, toMs: Long): UsageReadResult {
            if (toMs <= fromMs) return UsageReadResult(emptyList(), emptyList())
            val slices = mutableListOf<ForegroundSlice>()
            val opens = mutableListOf<String>()
            val seenForegroundPackages = mutableSetOf<String>()
            val synthesizedBackgroundPackages = mutableSetOf<String>()
            var currentForegroundPkg: String? = null
            var currentStartMs: Long? = null

            records.forEach { record ->
                val packageName = record.packageName.trim()
                if (packageName.isBlank()) return@forEach
                if (record.timestampMs < fromMs) {
                    when (record.eventType) {
                        UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                            currentForegroundPkg = packageName
                            currentStartMs = fromMs
                        }
                        UsageEvents.Event.MOVE_TO_BACKGROUND,
                        UsageEvents.Event.ACTIVITY_PAUSED -> {
                            if (currentForegroundPkg == packageName) {
                                currentForegroundPkg = null
                                currentStartMs = null
                            }
                        }
                    }
                    return@forEach
                }

                when (record.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                        if (currentForegroundPkg != packageName) {
                            opens += packageName
                            if (currentForegroundPkg != null && currentStartMs != null) {
                                slices += ForegroundSlice(
                                    packageName = currentForegroundPkg!!,
                                    startMs = currentStartMs!!,
                                    endMs = record.timestampMs
                                )
                            }
                            currentForegroundPkg = packageName
                            currentStartMs = record.timestampMs.coerceAtLeast(fromMs)
                        } else if (currentStartMs == null) {
                            currentStartMs = record.timestampMs.coerceAtLeast(fromMs)
                        }
                        seenForegroundPackages += packageName
                    }

                    UsageEvents.Event.MOVE_TO_BACKGROUND,
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        when {
                            currentForegroundPkg == packageName && currentStartMs != null -> {
                                slices += ForegroundSlice(
                                    packageName = packageName,
                                    startMs = currentStartMs!!,
                                    endMs = record.timestampMs
                                )
                                currentForegroundPkg = null
                                currentStartMs = null
                            }
                            currentForegroundPkg == null &&
                                packageName !in seenForegroundPackages &&
                                synthesizedBackgroundPackages.add(packageName) -> {
                                // App likely entered foreground before the queried history window.
                                slices += ForegroundSlice(
                                    packageName = packageName,
                                    startMs = fromMs,
                                    endMs = record.timestampMs
                                )
                            }
                        }
                    }
                }
            }

            if (currentForegroundPkg != null && currentStartMs != null) {
                slices += ForegroundSlice(
                    packageName = currentForegroundPkg!!,
                    startMs = currentStartMs!!,
                    endMs = toMs
                )
            }

            val normalized = slices
                .map { slice ->
                    val start = slice.startMs.coerceAtLeast(fromMs)
                    val end = slice.endMs.coerceAtMost(toMs)
                    slice.copy(startMs = start, endMs = end)
                }
                .filter { it.endMs > it.startMs }
            return UsageReadResult(slices = normalized, openPkgs = opens)
        }
    }

    fun readOpens(fromMs: Long, toMs: Long): List<String> {
        val usageStatsManager = usm ?: return emptyList()
        if (toMs <= fromMs) return emptyList()
        val events = usageStatsManager.queryEvents(fromMs, toMs)
        val opens = mutableListOf<String>()
        val event = UsageEvents.Event()
        val lastOpenByPackage = mutableMapOf<String, Long>()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND) continue
            val packageName = event.packageName ?: continue
            val lastOpenAt = lastOpenByPackage[packageName]
            if (lastOpenAt == null || event.timeStamp - lastOpenAt >= OPENS_DEBOUNCE_MS) {
                opens += packageName
                lastOpenByPackage[packageName] = event.timeStamp
            }
        }
        return opens
    }
}
