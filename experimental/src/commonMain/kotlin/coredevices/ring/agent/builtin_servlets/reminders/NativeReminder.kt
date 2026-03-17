package coredevices.ring.agent.builtin_servlets.reminders

import coredevices.ring.data.entity.room.reminders.LocalReminderData
import kotlin.time.Instant

expect class NativeReminder(time: Instant?, message: String) : ListAssignableReminder {
    override val time: Instant?
    override val message: String
    override suspend fun schedule(): String
    override suspend fun scheduleToList(listName: String): String
    override val listTitle: String?
    override suspend fun cancel()
    companion object {
        fun fromData(data: LocalReminderData): NativeReminder
    }
}
