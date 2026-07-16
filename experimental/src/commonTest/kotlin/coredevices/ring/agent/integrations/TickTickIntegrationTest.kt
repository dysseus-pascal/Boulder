package coredevices.ring.agent.integrations

import coredevices.ring.api.toTickTickDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class TickTickIntegrationTest {

    @Test
    fun dateIsFormattedAsUtcWithExplicitOffset() {
        val la = TimeZone.of("America/Los_Angeles")
        // 7pm PST on Nov 12 is 3am UTC on Nov 13.
        val deadline = LocalDateTime(2019, 11, 12, 19, 0).toInstant(la)
        assertEquals("2019-11-13T03:00:00+0000", deadline.toTickTickDate())
    }

    @Test
    fun singleDigitFieldsArePadded() {
        val deadline = LocalDateTime(2026, 1, 2, 3, 4, 5).toInstant(TimeZone.UTC)
        assertEquals("2026-01-02T03:04:05+0000", deadline.toTickTickDate())
    }

    @Test
    fun noDeadlineHasNoTriggers() {
        assertEquals(emptyList(), tickTickTriggers(deadline = null, notifyBefore = 30.minutes))
    }

    @Test
    fun deadlineTriggersAtDueTime() {
        assertEquals(listOf("TRIGGER:PT0S"), tickTickTriggers(someInstant, notifyBefore = null))
    }

    @Test
    fun notifyBeforeAddsNegativeTrigger() {
        assertEquals(
            listOf("TRIGGER:PT0S", "TRIGGER:-PT30M"),
            tickTickTriggers(someInstant, notifyBefore = 30.minutes)
        )
    }

    @Test
    fun taskUrlPointsAtTheWebApp() {
        assertEquals(
            "https://ticktick.com/webapp/#p/proj-1/tasks/task-1",
            tickTickTaskUrl("proj-1", "task-1")
        )
    }

    private val someInstant: Instant =
        LocalDateTime(2026, 7, 15, 12, 0).toInstant(TimeZone.UTC)
}
