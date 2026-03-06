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

class UsageReader(context: Context) {
    private val usm = context.getSystemService(UsageStatsManager::class.java)

    fun read(fromMs: Long, toMs: Long): UsageReadResult {
        val usageStatsManager = usm ?: return UsageReadResult(emptyList(), emptyList())
        if (toMs <= fromMs) return UsageReadResult(emptyList(), emptyList())
        val events = usageStatsManager.queryEvents(fromMs, toMs)
        val slices = mutableListOf<ForegroundSlice>()
        val opens = mutableListOf<String>()
        var currentForegroundPkg: String? = null
        var currentStartMs: Long? = null
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val packageName = event.packageName ?: continue
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    // Count an "open" only on package foreground transitions.
                    if (currentForegroundPkg != packageName) {
                        opens += packageName
                    }
                    // Close previous foreground app when a new app takes foreground.
                    if (currentForegroundPkg != null && currentStartMs != null && currentForegroundPkg != packageName) {
                        slices += ForegroundSlice(
                            packageName = currentForegroundPkg!!,
                            startMs = currentStartMs!!,
                            endMs = event.timeStamp
                        )
                    }
                    currentForegroundPkg = packageName
                    currentStartMs = event.timeStamp.coerceAtLeast(fromMs)
                }

                UsageEvents.Event.MOVE_TO_BACKGROUND,
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    when {
                        currentForegroundPkg == packageName && currentStartMs != null -> {
                            slices += ForegroundSlice(
                                packageName = packageName,
                                startMs = currentStartMs!!,
                                endMs = event.timeStamp
                            )
                            currentForegroundPkg = null
                            currentStartMs = null
                        }
                        currentForegroundPkg == null -> {
                            // App likely entered foreground before `fromMs`; account from window start.
                            slices += ForegroundSlice(
                                packageName = packageName,
                                startMs = fromMs,
                                endMs = event.timeStamp
                            )
                        }
                    }
                }
            }
        }

        // If we still have an active foreground app, close at `toMs`.
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

    private companion object {
        private const val OPENS_DEBOUNCE_MS = 10_000L
    }
}
