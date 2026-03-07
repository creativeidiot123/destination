package com.ankit.destination

import com.ankit.destination.data.ScheduleBlock
import com.ankit.destination.data.ScheduleBlockKind
import com.ankit.destination.schedule.ScheduleEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class ScheduleEvaluatorTest {
    private val utc = ZoneId.of("UTC")
    private val ny = ZoneId.of("America/New_York")
    private val mon = bit(DayOfWeek.MONDAY)
    private val tue = bit(DayOfWeek.TUESDAY)
    private val wed = bit(DayOfWeek.WEDNESDAY)
    private val thu = bit(DayOfWeek.THURSDAY)
    private val fri = bit(DayOfWeek.FRIDAY)
    private val sat = bit(DayOfWeek.SATURDAY)
    private val sun = bit(DayOfWeek.SUNDAY)
    private val weekdays = mon or tue or wed or thu or fri

    @Test fun `01 no schedules means unlocked`() {
        val decision = ScheduleEvaluator.evaluate(zdt(2026, 1, 5, 10, 0), emptyList())
        assertTrue(decision.activeBlockIds.isEmpty())
    }

    @Test fun `02 same-day active inside range`() {
        val decision = ScheduleEvaluator.evaluate(zdt(2026, 1, 5, 10, 0), listOf(block(days = mon, start = 540, end = 780)))
        assertTrue(decision.activeBlockIds.isNotEmpty())
    }

    @Test fun `03 same-day start boundary is active`() {
        val decision = ScheduleEvaluator.evaluate(zdt(2026, 1, 5, 9, 0), listOf(block(days = mon, start = 540, end = 780)))
        assertTrue(decision.activeBlockIds.isNotEmpty())
    }

    @Test fun `04 same-day end boundary is inactive`() {
        val decision = ScheduleEvaluator.evaluate(zdt(2026, 1, 5, 13, 0), listOf(block(days = mon, start = 540, end = 780)))
        assertTrue(decision.activeBlockIds.isEmpty())
    }

    @Test fun `05 same-day before start inactive`() {
        val decision = ScheduleEvaluator.evaluate(zdt(2026, 1, 5, 8, 59), listOf(block(days = mon, start = 540, end = 780)))
        assertTrue(decision.activeBlockIds.isEmpty())
    }

    @Test fun `06 same-day after end inactive`() {
        val decision = ScheduleEvaluator.evaluate(zdt(2026, 1, 5, 14, 0), listOf(block(days = mon, start = 540, end = 780)))
        assertTrue(decision.activeBlockIds.isEmpty())
    }

    @Test fun `07 disabled block ignored`() {
        val decision = ScheduleEvaluator.evaluate(zdt(2026, 1, 5, 10, 0), listOf(block(days = mon, start = 540, end = 780, enabled = false)))
        assertTrue(decision.activeBlockIds.isEmpty())
    }

    @Test fun `08 cross-midnight active before midnight`() {
        val decision = ScheduleEvaluator.evaluate(zdt(2026, 1, 5, 23, 0), listOf(block(days = mon, start = 1320, end = 360)))
        assertTrue(decision.activeBlockIds.isNotEmpty())
    }

    @Test fun `09 cross-midnight active after midnight via previous day`() {
        val decision = ScheduleEvaluator.evaluate(zdt(2026, 1, 6, 2, 0), listOf(block(days = mon, start = 1320, end = 360)))
        assertTrue(decision.activeBlockIds.isNotEmpty())
    }

    @Test fun `10 cross-midnight inactive midday`() {
        val decision = ScheduleEvaluator.evaluate(zdt(2026, 1, 6, 12, 0), listOf(block(days = mon, start = 1320, end = 360)))
        assertTrue(decision.activeBlockIds.isEmpty())
    }

    @Test fun `11 cross-midnight end boundary inactive`() {
        val decision = ScheduleEvaluator.evaluate(zdt(2026, 1, 6, 6, 0), listOf(block(days = mon, start = 1320, end = 360)))
        assertTrue(decision.activeBlockIds.isEmpty())
    }

    @Test fun `12 multiple blocks union active`() {
        val blocks = listOf(block(days = mon, start = 540, end = 660), block(days = mon, start = 1080, end = 1380))
        val decision = ScheduleEvaluator.evaluate(zdt(2026, 1, 5, 18, 30), blocks)
        assertTrue(decision.activeBlockIds.isNotEmpty())
    }

    @Test fun `13 overlapping blocks still active`() {
        val blocks = listOf(block(days = mon, start = 540, end = 720), block(days = mon, start = 600, end = 780))
        val decision = ScheduleEvaluator.evaluate(zdt(2026, 1, 5, 11, 0), blocks)
        assertTrue(decision.activeBlockIds.isNotEmpty())
    }

    @Test fun `14 day mask active only on configured day`() {
        val monday = ScheduleEvaluator.evaluate(zdt(2026, 1, 5, 10, 0), listOf(block(days = mon, start = 540, end = 780)))
        val tuesday = ScheduleEvaluator.evaluate(zdt(2026, 1, 6, 10, 0), listOf(block(days = mon, start = 540, end = 780)))
        assertTrue(monday.activeBlockIds.isNotEmpty())
        assertTrue(tuesday.activeBlockIds.isEmpty())
    }

    @Test fun `15 next transition is start when before block`() {
        val now = zdt(2026, 1, 5, 8, 0)
        val next = ScheduleEvaluator.computeNextTransition(now, listOf(block(days = mon, start = 540, end = 780)))
        assertEquals(zdt(2026, 1, 5, 9, 0), next)
    }

    @Test fun `16 next transition is end when inside block`() {
        val now = zdt(2026, 1, 5, 10, 0)
        val next = ScheduleEvaluator.computeNextTransition(now, listOf(block(days = mon, start = 540, end = 780)))
        assertEquals(zdt(2026, 1, 5, 13, 0), next)
    }

    @Test fun `17 next transition cross-midnight start`() {
        val now = zdt(2026, 1, 5, 21, 0)
        val next = ScheduleEvaluator.computeNextTransition(now, listOf(block(days = mon, start = 1320, end = 360)))
        assertEquals(zdt(2026, 1, 5, 22, 0), next)
    }

    @Test fun `18 next transition cross-midnight end on next day`() {
        val now = zdt(2026, 1, 5, 23, 0)
        val next = ScheduleEvaluator.computeNextTransition(now, listOf(block(days = mon, start = 1320, end = 360)))
        assertEquals(zdt(2026, 1, 6, 6, 0), next)
    }

    @Test fun `19 next transition picks earliest across blocks`() {
        val now = zdt(2026, 1, 5, 7, 0)
        val blocks = listOf(block(days = mon, start = 540, end = 780), block(days = mon, start = 480, end = 510))
        val next = ScheduleEvaluator.computeNextTransition(now, blocks)
        assertEquals(zdt(2026, 1, 5, 8, 0), next)
    }

    @Test fun `20 next transition null for no enabled blocks`() {
        val now = zdt(2026, 1, 5, 7, 0)
        val next = ScheduleEvaluator.computeNextTransition(now, listOf(block(days = mon, start = 540, end = 780, enabled = false)))
        assertEquals(null, next)
    }

    @Test fun `21 dayToBit monday is bit0`() {
        assertEquals(1, ScheduleEvaluator.dayToBit(DayOfWeek.MONDAY))
    }

    @Test fun `22 dayToBit sunday is bit6`() {
        assertEquals(1 shl 6, ScheduleEvaluator.dayToBit(DayOfWeek.SUNDAY))
    }

    @Test fun `23 reason includes active block name`() {
        val decision = ScheduleEvaluator.evaluate(
            zdt(2026, 1, 5, 10, 0),
            listOf(block(id = 7, name = "Morning", days = mon, start = 540, end = 780))
        )
        assertTrue(decision.reason.contains("Morning"))
    }

    @Test fun `24 reason includes multiple names`() {
        val decision = ScheduleEvaluator.evaluate(
            zdt(2026, 1, 5, 10, 30),
            listOf(
                block(id = 1, name = "A", days = mon, start = 540, end = 780),
                block(id = 2, name = "B", days = mon, start = 600, end = 700)
            )
        )
        assertTrue(decision.reason.contains("A"))
        assertTrue(decision.reason.contains("B"))
    }

    @Test fun `25 activeBlockIds populated`() {
        val decision = ScheduleEvaluator.evaluate(
            zdt(2026, 1, 5, 10, 30),
            listOf(block(id = 5, name = "A", days = mon, start = 540, end = 780))
        )
        assertTrue(decision.activeBlockIds.contains(5))
    }

    @Test fun `25b strictActive true when active block is strict`() {
        val decision = ScheduleEvaluator.evaluate(
            zdt(2026, 1, 5, 10, 30),
            listOf(
                block(
                    id = 5,
                    name = "A",
                    days = mon,
                    start = 540,
                    end = 780,
                    strict = true,
                    kind = ScheduleBlockKind.GROUPS.name
                )
            )
        )
        assertTrue(decision.strictActive)
    }

    @Test fun `25c groups schedule emits blocked group ids and does not force lock`() {
        val block = block(
            id = 5,
            name = "Social time",
            days = mon,
            start = 540,
            end = 780,
            kind = ScheduleBlockKind.GROUPS.name
        )
        val decision = ScheduleEvaluator.evaluate(
            zdt(2026, 1, 5, 10, 30),
            listOf(block),
            blockGroups = mapOf(5L to setOf("social", "short_video"))
        )
        assertFalse(decision.shouldLock)
        assertEquals(setOf("social", "short_video"), decision.blockedGroupIds)
    }

    @Test fun `26 weekly block works for friday`() {
        val decision = ScheduleEvaluator.evaluate(zdt(2026, 1, 9, 10, 0), listOf(block(days = weekdays, start = 540, end = 780)))
        assertTrue(decision.activeBlockIds.isNotEmpty())
    }

    @Test fun `27 weekly block skips saturday`() {
        val decision = ScheduleEvaluator.evaluate(zdt(2026, 1, 10, 10, 0), listOf(block(days = weekdays, start = 540, end = 780)))
        assertTrue(decision.activeBlockIds.isEmpty())
    }

    @Test fun `28 cross-midnight monday does not activate sunday night`() {
        val decision = ScheduleEvaluator.evaluate(zdt(2026, 1, 4, 23, 0), listOf(block(days = mon, start = 1320, end = 360)))
        assertTrue(decision.activeBlockIds.isEmpty())
    }

    @Test fun `29 cross-midnight monday activates early tuesday`() {
        val decision = ScheduleEvaluator.evaluate(zdt(2026, 1, 6, 1, 0), listOf(block(days = mon, start = 1320, end = 360)))
        assertTrue(decision.activeBlockIds.isNotEmpty())
    }

    @Test fun `30 spring-forward dst keeps next transition after now`() {
        val now = ZonedDateTime.of(2026, 3, 8, 1, 55, 0, 0, ny)
        val blocks = listOf(block(days = sun, start = 120, end = 240)) // 02:00-04:00 (02:00 gap day)
        val next = ScheduleEvaluator.computeNextTransition(now, blocks)
        assertNotNull(next)
        assertTrue(next!!.isAfter(now))
    }

    @Test fun `31 fall-back dst keeps next transition after now`() {
        val now = ZonedDateTime.of(2026, 11, 1, 0, 30, 0, 0, ny)
        val blocks = listOf(block(days = sun, start = 60, end = 180)) // 01:00-03:00 overlap day
        val next = ScheduleEvaluator.computeNextTransition(now, blocks)
        assertNotNull(next)
        assertTrue(next!!.isAfter(now))
    }

    @Test fun `32 day mask saturday block inactive on monday`() {
        val decision = ScheduleEvaluator.evaluate(zdt(2026, 1, 5, 10, 0), listOf(block(days = sat, start = 540, end = 780)))
        assertTrue(decision.activeBlockIds.isEmpty())
    }

    @Test fun `33 zero length block is ignored for activity and transitions`() {
        val block = block(days = mon, start = 540, end = 540)

        val decision = ScheduleEvaluator.evaluate(zdt(2026, 1, 5, 9, 0), listOf(block))
        val next = ScheduleEvaluator.computeNextTransition(zdt(2026, 1, 5, 8, 0), listOf(block))

        assertTrue(decision.activeBlockIds.isEmpty())
        assertEquals(null, next)
    }

    @Test fun `34 invalid minute block is ignored for activity and transitions`() {
        val block = block(days = mon, start = -5, end = 60)

        val decision = ScheduleEvaluator.evaluate(zdt(2026, 1, 5, 0, 30), listOf(block))
        val next = ScheduleEvaluator.computeNextTransition(zdt(2026, 1, 5, 0, 0), listOf(block))

        assertTrue(decision.activeBlockIds.isEmpty())
        assertEquals(null, next)
    }

    @Test fun `35 strict group schedule reason remains active when mappings are missing`() {
        val decision = ScheduleEvaluator.evaluate(
            zdt(2026, 1, 5, 10, 30),
            listOf(
                block(
                    id = 5,
                    name = "Strict social",
                    days = mon,
                    start = 540,
                    end = 780,
                    strict = true,
                    kind = ScheduleBlockKind.GROUPS.name
                )
            ),
            blockGroups = emptyMap()
        )

        assertTrue(decision.strictActive)
        assertTrue(decision.reason.contains("Strict group schedule active"))
    }

    @Test fun `36 fall-back dst overlap chooses second-occurrence transition when now is in second hour`() {
        val now = ZonedDateTime.ofLocal(
            LocalDateTime.of(2026, 11, 1, 1, 20),
            ny,
            ZoneOffset.ofHours(-5)
        )
        val blocks = listOf(block(days = sun, start = 90, end = 105))
        val next = ScheduleEvaluator.computeNextTransition(now, blocks)
        val expected = ZonedDateTime.ofLocal(
            LocalDateTime.of(2026, 11, 1, 1, 30),
            ny,
            ZoneOffset.ofHours(-5)
        )
        assertEquals(expected, next)
    }

    private fun block(
        id: Long = 1L,
        name: String = "Block",
        days: Int,
        start: Int,
        end: Int,
        enabled: Boolean = true,
        strict: Boolean = false,
        kind: String = ScheduleBlockKind.GROUPS.name
    ): ScheduleBlock {
        return ScheduleBlock(
            id = id,
            name = name,
            daysMask = days,
            startMinute = start,
            endMinute = end,
            enabled = enabled,
            kind = kind,
            strict = strict,
            timezoneMode = "DEVICE_LOCAL"
        )
    }

    private fun zdt(year: Int, month: Int, day: Int, hour: Int, minute: Int): ZonedDateTime {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, utc)
    }

    private fun bit(day: DayOfWeek): Int = ScheduleEvaluator.dayToBit(day)
}
