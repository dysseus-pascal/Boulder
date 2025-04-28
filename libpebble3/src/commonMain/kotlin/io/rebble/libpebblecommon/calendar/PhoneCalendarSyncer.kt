package io.rebble.libpebblecommon.calendar

import androidx.annotation.VisibleForTesting
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.SystemAppIDs.CALENDAR_APP_UUID
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.TimeProvider
import io.rebble.libpebblecommon.database.dao.CalendarDao
import io.rebble.libpebblecommon.database.dao.TimelinePinRealDao
import io.rebble.libpebblecommon.database.dao.TimelineReminderRealDao
import io.rebble.libpebblecommon.database.entity.TimelinePin
import io.rebble.libpebblecommon.database.entity.TimelineReminder
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class PhoneCalendarSyncer(
    private val timelinePinDao: TimelinePinRealDao,
    private val calendarDao: CalendarDao,
    private val timeProvider: TimeProvider,
    private val systemCalendar: SystemCalendar,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
    private val timelineReminderDao: TimelineReminderRealDao,
) {
    private val logger = Logger.withTag("PhoneCalendarSyncer")

    fun init() {
        libPebbleCoroutineScope.launch {
            syncDeviceCalendarsToDb()
            systemCalendar.registerForCalendarChanges().debounce(5.seconds).collect {
                syncDeviceCalendarsToDb()
            }
        }
    }

    @VisibleForTesting
    suspend fun syncDeviceCalendarsToDb() {
        logger.d("syncDeviceCalendarsToDb")
        val existingCalendars = calendarDao.getAll()
        val calendars = systemCalendar.getCalendars()
        logger.d("Got ${calendars.size} calendars from device, syncing... (${existingCalendars.size} existing)")
        existingCalendars.forEach { existingCalendar ->
            val matchingCalendar = calendars.find { it.platformId == existingCalendar.platformId }
            if (matchingCalendar != null) {
                val updateCal = existingCalendar.copy(
                    platformId = matchingCalendar.platformId,
                    name = matchingCalendar.name,
                    ownerName = matchingCalendar.ownerName,
                    ownerId = matchingCalendar.ownerId,
                    color = matchingCalendar.color,
                )
                calendarDao.update(updateCal)
            } else {
                calendarDao.delete(existingCalendar)
            }
        }
        calendars.forEach { newCalendar ->
            if (existingCalendars.none { it.platformId == newCalendar.platformId }) {
                calendarDao.insertOrReplace(newCalendar)
            }
        }

        val allCalendars = calendarDao.getAll()
        val existingPins = timelinePinDao.getPinsForWatchapp(CALENDAR_APP_UUID)
        val startDate = timeProvider.now() - 1.days
        val endDate = (startDate + 7.days)
        val newPins = allCalendars.flatMap { calendar ->
            if (!calendar.enabled) {
                return@flatMap emptyList()
            }
            val events = systemCalendar.getCalendarEvents(calendar, startDate, endDate)
            events.map { event ->
                EventAndPin(event, event.toTimelinePin(calendar))
            }
        }
        val remindersToInsert = mutableListOf<TimelineReminder>()
        val remindersToDelete = mutableListOf<Uuid>()
        val toInsert = newPins.mapNotNull { new ->
            val newPin = new.pin
            val existingPin = existingPins.find { it.backingId == newPin.backingId }
            syncReminders(
                event = new.event,
                pinId = existingPin?.itemId ?: newPin.itemId,
                remindersToInsert = remindersToInsert,
                remindersToDelete = remindersToDelete,
            )

            if (existingPin?.recordHashCode() == newPin.recordHashCode()) {
                return@mapNotNull null
            }
            val pin = existingPin?.let {
                newPin.copy(itemId = it.itemId)
            } ?: newPin
            logger.d("New Pin: $newPin (existed: ${existingPin != null})")
            return@mapNotNull pin
        }
        if (toInsert.isNotEmpty()) {
            timelinePinDao.insertOrReplace(toInsert)
        }

        val pinsToDelete = existingPins.filter { pin ->
            if (newPins.none { it.pin.backingId == pin.backingId }) {
                logger.d("Deleting pin ${pin.itemId} (backingId: ${pin.backingId}) as no longer exists in calendar")
                true
            } else {
                false
            }
        }
        if (pinsToDelete.isNotEmpty()) {
            timelinePinDao.markAllForDeletionWithReminders(pinsToDelete.map { it.itemId }, timelineReminderDao)
        }
        if (remindersToInsert.isNotEmpty()) {
            timelineReminderDao.insertOrReplace(remindersToInsert)
        }
        if (remindersToDelete.isNotEmpty()) {
            timelineReminderDao.markAllForDeletion(remindersToDelete)
        }
        logger.d("Synced ${allCalendars.size} calendars to DB")
    }

    private suspend fun syncReminders(
        event: CalendarEvent,
        pinId: Uuid,
        remindersToInsert: MutableList<TimelineReminder>,
        remindersToDelete: MutableList<Uuid>,
    ) {
        val existingReminders = timelineReminderDao.getRemindersForPin(pinId)
        val eventReminderTimestamps = event.reminders.map { event.startTime - it.minutesBefore.minutes }

        remindersToDelete += existingReminders.filter { er ->
            eventReminderTimestamps.none { t ->
                er.content.timestamp.instant == t
            }
        }.map { it.itemId }

        remindersToInsert += eventReminderTimestamps.filter { t ->
            existingReminders.none { er ->
                er.content.timestamp.instant == t
            }
        }.map { event.toTimelineReminder(it, pinId) }
    }
}

data class EventAndPin(val event: CalendarEvent, val pin: TimelinePin)
