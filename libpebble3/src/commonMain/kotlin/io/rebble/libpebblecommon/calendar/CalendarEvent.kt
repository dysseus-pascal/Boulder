package io.rebble.libpebblecommon.calendar

import io.rebble.libpebblecommon.SystemAppIDs.CALENDAR_APP_UUID
import io.rebble.libpebblecommon.database.entity.CalendarEntity
import io.rebble.libpebblecommon.database.entity.TimelinePin
import io.rebble.libpebblecommon.database.entity.TimelineReminder
import io.rebble.libpebblecommon.database.entity.buildTimelinePin
import io.rebble.libpebblecommon.database.entity.buildTimelineReminder
import io.rebble.libpebblecommon.packets.blobdb.TimelineAttribute
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.util.trimWithEllipsis
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.Month
import kotlin.time.Duration
import kotlin.uuid.Uuid

data class CalendarEvent(
    val id: String,
    val calendarId: String,
    val title: String,
    val description: String,
    val location: String?,
    val startTime: Instant,
    val endTime: Instant,
    val allDay: Boolean,
    val attendees: List<EventAttendee>,
    val recurs: Boolean,
    val reminders: List<EventReminder>,
    val availability: Availability,
    val status: Status,
    val baseEventId: String,
) {
    enum class Availability {
        Free,
        Busy,
        Tentative,
        Unavailable,
    }

    enum class Status {
        None,
        Confirmed,
        Cancelled,
        Tentative,
    }
}

private fun CalendarEvent.generateCompositeBackingId() = "${calendarId}T${id}T${startTime}"

private fun transformDescription(rawDescription: String): String {
    val regex = Regex("<[^>]*>", setOf(RegexOption.MULTILINE))
    return rawDescription.replace(regex, "").trimWithEllipsis(300)
}

fun CalendarEvent.toTimelineReminder(timestamp: Instant, pinUuid: Uuid): TimelineReminder =
    buildTimelineReminder(
        parentId = pinUuid,
        timestamp = timestamp,
    ) {
        attributes {
            title { title }
            if (!location.isNullOrBlank()) {
                location { location }
            }
            tinyIcon { TimelineIcon.NotificationReminder }
            // TODO attendees
        }
        flags {
            if (allDay) {
                isAllDay()
            }
        }
        actions {
            // TODO actions
        }
    }

fun CalendarEvent.toTimelinePin(calendar: CalendarEntity): TimelinePin = buildTimelinePin(
    parentId = CALENDAR_APP_UUID,
    timestamp = startTime,
) {
    backingId = generateCompositeBackingId()
    duration = if (allDay) Duration.ZERO else (endTime - startTime)
    flags {
        if (allDay) {
            isAllDay()
        }
    }
    layout = TimelineItem.Layout.CalendarPin
    attributes {
        val headings = mutableListOf<String>()
        val paragraphs = mutableListOf<String>()

        if (description.isNotBlank()) {
            headings.add("")
            paragraphs.add(transformDescription(description))
        }

        if (attendees.isNotEmpty()) {
            val attendeesString = attendees.mapNotNull { attendee ->
                if (!attendee.name.isNullOrBlank()) {
                    attendee.name
                } else if (!attendee.email.isNullOrBlank()) {
                    attendee.email
                } else {
                    null
                }
            }.joinToString(", ")
            if (attendeesString.isNotBlank()) {
                headings.add("Attendees")
                paragraphs.add(attendeesString)
            }

            val selfAttendee = attendees.find { it.isCurrentUser }
            if (selfAttendee?.attendanceStatus != null) {
                headings.add("Status")
                paragraphs.add(selfAttendee.attendanceStatus.name)
            }
        }

        if (recurs) {
            headings.add("Recurrence")
            paragraphs.add("Recurs")
        }

        headings.add("Calendar")
        paragraphs.add(calendar.name)

        tinyIcon { TimelineIcon.TimelineCalendar }
        title { title }
        if (!location.isNullOrBlank()) {
            location { location }
        }
//        if (recurrenceRule != null) {
//            int(TimelineAttribute.DisplayRecurring) { 1 }
//        }
        stringList(TimelineAttribute.Headings) { headings }
        stringList(TimelineAttribute.Paragraphs) { paragraphs }
    }
    actions {
        // TODO actions
    }
}

data class EventAttendee(
    val name: String?,
    val email: String?,
    val role: Role?,
    val isOrganizer: Boolean = false,
    val isCurrentUser: Boolean = false,
    val attendanceStatus: AttendanceStatus?,
) {
    enum class Role {
        None,
        Required,
        Optional,
        Resource,
    }

    // These are only the android values, other platforms should map specific values to the closest match
    enum class AttendanceStatus {
        None,
        Accepted,
        Declined,
        Invited,
        Tentative,
    }
}

data class EventRecurrenceRule(
    val totalOccurrences: Int?,
    val interval: Int?,
    val endDate: Instant?,
    val recurrenceFrequency: Frequency,
) {
    open class Frequency {
        val name: String
            get() = when (this) {
                is Daily -> "Daily"
                is Weekly -> "Weekly"
                is Monthly -> "Monthly"
                is Yearly -> "Yearly"
                else -> error("Unknown frequency type")
            }

        /**
         * Repeats daily
         */
        object Daily : Frequency()

        /**
         * Repeats weekly
         * @param days Set of days of the week when the event should repeat
         */
        class Weekly(val days: Set<DayOfWeek>) : Frequency()

        /**
         * Repeats monthly
         * @param dayOfMonth Day of the month when the event should repeat
         * @param days Set of days of the week when the event should repeat
         * @param weekOfMonth Week of the month when the event should repeat, if dayOfMonth is null
         */
        class Monthly(val dayOfMonth: Int?, val days: Set<DayOfWeek>?, weekOfMonth: Int?) :
            Frequency()

        /**
         * Repeats yearly
         * @param month Month when the event should repeat
         * @param dayOfMonth Day of the month when the event should repeat
         * @param days Set of days of the week when the event should repeat
         * @param weekOfMonth Week of the month when the event should repeat, if dayOfMonth is null
         */
        class Yearly(
            val month: Month?,
            val dayOfMonth: Int?,
            val days: Set<DayOfWeek>?,
            weekOfMonth: Int?
        ) : Frequency()
    }
}

data class EventReminder(
    /**
     * Minutes before the event when the reminder should trigger
     */
    val minutesBefore: Int,
)
