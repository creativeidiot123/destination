package com.ankit.destination

import android.app.usage.UsageEvents
import com.ankit.destination.usage.ForegroundSlice
import com.ankit.destination.usage.UsageEventRecord
import com.ankit.destination.usage.UsageAggregator
import com.ankit.destination.usage.UsageReader
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageReaderLogicTest {

    @Test
    fun parseRecords_doesNotDuplicatePreWindowSlice_whenPauseAndBackgroundBothArrive() {
        val result = UsageReader.parseRecords(
            records = listOf(
                UsageEventRecord(
                    packageName = "com.example.app",
                    eventType = UsageEvents.Event.ACTIVITY_PAUSED,
                    timestampMs = 20_000L
                ),
                UsageEventRecord(
                    packageName = "com.example.app",
                    eventType = UsageEvents.Event.MOVE_TO_BACKGROUND,
                    timestampMs = 21_000L
                )
            ),
            fromMs = 10_000L,
            toMs = 30_000L
        )

        assertEquals(
            listOf(
                ForegroundSlice(
                    packageName = "com.example.app",
                    startMs = 10_000L,
                    endMs = 20_000L
                )
            ),
            result.slices
        )
        assertEquals(emptyList<String>(), result.openPkgs)
    }

    @Test
    fun parseRecordsWithHistory_clipsCarryOverSessionToFixedHourWindow() {
        val result = UsageReader.parseRecordsWithHistory(
            records = listOf(
                UsageEventRecord(
                    packageName = "com.example.app",
                    eventType = UsageEvents.Event.MOVE_TO_FOREGROUND,
                    timestampMs = 3_300_000L
                ),
                UsageEventRecord(
                    packageName = "com.example.app",
                    eventType = UsageEvents.Event.MOVE_TO_BACKGROUND,
                    timestampMs = 3_900_000L
                )
            ),
            fromMs = 3_600_000L,
            toMs = 7_200_000L
        )

        assertEquals(
            listOf(
                ForegroundSlice(
                    packageName = "com.example.app",
                    startMs = 3_600_000L,
                    endMs = 3_900_000L
                )
            ),
            result.slices
        )
        assertEquals(emptyList<String>(), result.openPkgs)
    }

    @Test
    fun parseRecordsWithHistory_neverExceedsFixedHourWindowTotal() {
        val result = UsageReader.parseRecordsWithHistory(
            records = listOf(
                UsageEventRecord(
                    packageName = "com.example.app",
                    eventType = UsageEvents.Event.MOVE_TO_FOREGROUND,
                    timestampMs = 0L
                ),
                UsageEventRecord(
                    packageName = "com.example.app",
                    eventType = UsageEvents.Event.MOVE_TO_BACKGROUND,
                    timestampMs = 3_900_000L
                ),
                UsageEventRecord(
                    packageName = "com.example.app",
                    eventType = UsageEvents.Event.MOVE_TO_FOREGROUND,
                    timestampMs = 4_200_000L
                ),
                UsageEventRecord(
                    packageName = "com.example.app",
                    eventType = UsageEvents.Event.MOVE_TO_BACKGROUND,
                    timestampMs = 6_900_000L
                )
            ),
            fromMs = 3_600_000L,
            toMs = 7_200_000L
        )

        val totals = UsageAggregator.aggregate(result.slices, emptyList()).timeMsByPkg
        assertEquals(3_000_000L, totals["com.example.app"])
    }
}
