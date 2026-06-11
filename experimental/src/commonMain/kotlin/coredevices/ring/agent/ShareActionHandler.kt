package coredevices.ring.agent

import co.touchlab.kermit.Logger
import coredevices.indexai.time.HumanDateTimeParser
import coredevices.ring.agent.builtin_servlets.clock.SetTimerTool
import coredevices.ring.agent.builtin_servlets.notes.NoteIntegrationFactory
import coredevices.ring.agent.builtin_servlets.reminders.ReminderFactory
import coredevices.ring.database.room.repository.ItemRepository
import coredevices.ring.service.indexfeed.ItemFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Turns text shared into the app (e.g. via the platform share sheet) directly into an Index
 * note or reminder.
 */
class ShareActionHandler(
    private val noteIntegrationFactory: NoteIntegrationFactory,
    private val reminderFactory: ReminderFactory,
    private val itemFactory: ItemFactory,
    private val itemRepository: ItemRepository,
) {
    enum class Action { Note, Reminder }

    private val scope = CoroutineScope(Dispatchers.Default)

    companion object {
        private val logger = Logger.withTag("ShareActionHandler")

        /** Extracts a future reminder time from free text, or null if none was found. */
        fun parseReminderTime(
            text: String,
            clock: Clock = Clock.System,
            timeZone: TimeZone = TimeZone.currentSystemDefault(),
        ): Instant? {
            val parsed = HumanDateTimeParser(clock, timeZone).parseFromMessage(text) ?: return null
            val now = clock.now()
            return SetTimerTool.interpretedTimeToFireTime(parsed.dateTime, now, timeZone)
                .takeIf { it > now }
        }
    }

    fun handleSharedText(text: String, action: Action) {
        val content = text.trim()
        if (content.isEmpty()) return
        scope.launch {
            try {
                when (action) {
                    Action.Note -> createNote(content)
                    Action.Reminder -> createReminder(content)
                }
            } catch (e: Exception) {
                logger.e(e) { "Failed to create $action from shared text" }
            }
        }
    }

    private suspend fun createNote(text: String) {
        noteIntegrationFactory.createNoteClient().createNote(text)
        itemRepository.setItem(
            itemFactory.simpleUid(),
            itemFactory.noteItem(
                sourceRecordingId = null,
                createdAt = Clock.System.now(),
                title = text,
                listHint = null,
                toolCallId = null,
            ),
        )
    }

    private suspend fun createReminder(text: String) {
        val reminder = reminderFactory.create(time = parseReminderTime(text), message = text)
        reminder.schedule()
        itemRepository.setItem(
            itemFactory.simpleUid(),
            itemFactory.reminderItem(
                sourceRecordingId = null,
                createdAt = Clock.System.now(),
                title = reminder.message,
                dueAt = reminder.time,
                toolCallId = null,
            ),
        )
    }
}
