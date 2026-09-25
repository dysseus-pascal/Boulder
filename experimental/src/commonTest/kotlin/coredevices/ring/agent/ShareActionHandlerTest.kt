package coredevices.ring.agent

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ShareActionHandlerTest {

    private val tz = TimeZone.of("Europe/London")

    // Wednesday 2026-06-10, 10:00
    private val fixedNow = LocalDateTime(2026, 6, 10, 10, 0).toInstant(tz)
    private val clock = object : Clock {
        override fun now(): Instant = fixedNow
    }

    private fun parse(text: String) = ShareActionHandler.parseReminderTime(text, clock, tz)

    @Test
    fun extractsDateTimeFromSharedText() {
        assertEquals(
            LocalDateTime(2026, 6, 11, 15, 0).toInstant(tz),
            parse("Dentist appointment tomorrow at 3pm"),
        )
    }

    @Test
    fun extractsRelativeDurationFromSharedText() {
        assertEquals(fixedNow + 30.minutes, parse("Take the bread out of the oven in 30 minutes"))
    }

    @Test
    fun returnsNullWhenTextHasNoTime() {
        assertNull(parse("Buy more milk"))
    }

    @Test
    fun returnsNullWhenParsedTimeIsInThePast() {
        // "today" resolves to 9am today, which has already passed at the fixed 10am clock
        assertNull(parse("Submit the report today"))
    }
}
