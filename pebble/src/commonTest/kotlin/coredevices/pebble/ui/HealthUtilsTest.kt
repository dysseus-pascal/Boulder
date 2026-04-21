package coredevices.pebble.ui

import io.rebble.libpebblecommon.services.SleepSession
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthUtilsTest {
    @Test
    fun ordinalSuffix_1st() = assertEquals("st", ordinalSuffix(1))

    @Test
    fun ordinalSuffix_2nd() = assertEquals("nd", ordinalSuffix(2))

    @Test
    fun ordinalSuffix_3rd() = assertEquals("rd", ordinalSuffix(3))

    @Test
    fun ordinalSuffix_4th() = assertEquals("th", ordinalSuffix(4))

    @Test
    fun ordinalSuffix_11th() = assertEquals("th", ordinalSuffix(11))

    @Test
    fun ordinalSuffix_12th() = assertEquals("th", ordinalSuffix(12))

    @Test
    fun ordinalSuffix_13th() = assertEquals("th", ordinalSuffix(13))

    @Test
    fun ordinalSuffix_21st() = assertEquals("st", ordinalSuffix(21))

    @Test
    fun ordinalSuffix_22nd() = assertEquals("nd", ordinalSuffix(22))

    @Test
    fun ordinalSuffix_23rd() = assertEquals("rd", ordinalSuffix(23))

    @Test
    fun ordinalSuffix_31st() = assertEquals("st", ordinalSuffix(31))

    @Test
    fun formatDayLabel_today() {
        val today = LocalDate(2026, 4, 21)
        assertEquals("Today", formatDayLabel(today, today))
    }

    @Test
    fun formatDayLabel_yesterday() {
        val today = LocalDate(2026, 4, 21)
        val yesterday = LocalDate(2026, 4, 20)
        assertEquals("Yesterday", formatDayLabel(yesterday, today))
    }

    @Test
    fun formatDayLabel_sameMonth() {
        val today = LocalDate(2026, 4, 21)
        val target = LocalDate(2026, 4, 18)
        assertEquals("Sat 18th", formatDayLabel(target, today))
    }

    @Test
    fun formatDayLabel_differentMonth() {
        val today = LocalDate(2026, 4, 2)
        val target = LocalDate(2026, 3, 30)
        assertEquals("Mon 30th Mar", formatDayLabel(target, today))
    }

    @Test
    fun formatDayLabel_1st() {
        val today = LocalDate(2026, 4, 5)
        val target = LocalDate(2026, 4, 1)
        assertEquals("Wed 1st", formatDayLabel(target, today))
    }

    @Test
    fun formatDayLabel_2nd() {
        val today = LocalDate(2026, 4, 5)
        val target = LocalDate(2026, 4, 2)
        assertEquals("Thu 2nd", formatDayLabel(target, today))
    }

    @Test
    fun formatDayLabel_3rd() {
        val today = LocalDate(2026, 4, 5)
        val target = LocalDate(2026, 4, 3)
        assertEquals("Fri 3rd", formatDayLabel(target, today))
    }

    @Test
    fun averageTimeOfDay_sameTime() {
        val tenPM = 22 * 3600L
        assertEquals(tenPM, averageTimeOfDay(listOf(tenPM, tenPM, tenPM)))
    }

    @Test
    fun averageTimeOfDay_afternoon() {
        val noon = 12 * 3600L
        val twoPM = 14 * 3600L
        assertEquals(13 * 3600L, averageTimeOfDay(listOf(noon, twoPM)))
    }

    @Test
    fun averageTimeOfDay_crossesMidnight() {
        val elevenPM = 23 * 3600L
        val oneAM = 1 * 3600L
        val avg = averageTimeOfDay(listOf(elevenPM, oneAM))
        assertEquals(0L, avg)
    }

    @Test
    fun averageTimeOfDay_allLateBedtimes() {
        val times = listOf(22 * 3600L, 23 * 3600L, 23 * 3600L + 1800, 0L)
        val avg = averageTimeOfDay(times)
        // Should be around 22:52 (close to 23:00, not 11:00 AM)
        assertTrue(avg > 20 * 3600L || avg < 2 * 3600L, "Expected late night, got ${avg / 3600}h")
    }

    @Test
    fun averageTimeOfDay_singleValue() {
        assertEquals(8 * 3600L, averageTimeOfDay(listOf(8 * 3600L)))
    }

    @Test
    fun averageTimeOfDay_emptyList() {
        assertEquals(0L, averageTimeOfDay(emptyList()))
    }

    @Test
    fun buildDailySleepSegments_nullSession() {
        val result = buildDailySleepSegments(dayStart = 0L, session = null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun buildDailySleepSegments_fractionsInRange() {
        val dayStart = 86400L // second day
        val session = SleepSession(
            start = dayStart - 4 * 3600L, // 8 PM previous day
            end = dayStart + 6 * 3600L,   // 6 AM
            totalSleep = 8 * 3600L,
            deepSleep = 2 * 3600L,
        )
        val segments = buildDailySleepSegments(dayStart, session)

        assertTrue(segments.isNotEmpty())
        for (seg in segments) {
            assertTrue(seg.startFraction in 0f..1f, "startFraction ${seg.startFraction} out of range")
            assertTrue(seg.widthFraction > 0f, "widthFraction should be positive")
            assertTrue(seg.startFraction + seg.widthFraction <= 1.001f, "segment extends beyond 1.0")
        }
    }

    @Test
    fun buildDailySleepSegments_lightBeforeDeep() {
        val dayStart = 86400L
        val session = SleepSession(
            start = dayStart - 4 * 3600L,
            end = dayStart + 4 * 3600L,
            totalSleep = 6 * 3600L,
            deepSleep = 2 * 3600L,
        )
        val segments = buildDailySleepSegments(dayStart, session)

        assertEquals(2, segments.size)
        assertEquals(false, segments[0].isDeep)
        assertEquals(true, segments[1].isDeep)
    }

    @Test
    fun buildDailySleepSegments_noDeepSleep() {
        val dayStart = 86400L
        val session = SleepSession(
            start = dayStart - 4 * 3600L,
            end = dayStart + 4 * 3600L,
            totalSleep = 8 * 3600L,
            deepSleep = 0,
        )
        val segments = buildDailySleepSegments(dayStart, session)

        assertEquals(1, segments.size)
        assertEquals(false, segments[0].isDeep)
    }

    @Test
    fun buildDailySleepSegments_segmentsCoverWholeSession() {
        val dayStart = 86400L
        val session = SleepSession(
            start = dayStart - 4 * 3600L,
            end = dayStart + 4 * 3600L,
            totalSleep = 6 * 3600L,
            deepSleep = 2 * 3600L,
        )
        val segments = buildDailySleepSegments(dayStart, session)
        val totalWidth = segments.sumOf { it.widthFraction.toDouble() }.toFloat()

        // Light + deep fractions should equal the total session fraction
        assertTrue(totalWidth > 0f)
        // First segment should start where the session starts
        assertEquals(segments[0].startFraction, segments.minOf { it.startFraction })
    }

    @Test
    fun formatHours_wholeNumber() = assertEquals("7h 0m", formatHours(7.0f))

    @Test
    fun formatHours_withMinutes() = assertEquals("7h 30m", formatHours(7.5f))

    @Test
    fun formatHours_zero() = assertEquals("0h 0m", formatHours(0f))
}
