package io.rebble.libpebblecommon.calendar

import io.rebble.libpebblecommon.database.entity.CalendarEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

interface SystemCalendar {
    suspend fun getCalendars(): List<CalendarEntity>
    suspend fun getCalendarEvents(calendar: CalendarEntity, startDate: Instant, endDate: Instant): List<CalendarEvent>
    fun registerForCalendarChanges(): Flow<Unit>
}
