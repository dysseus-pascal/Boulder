package coredevices.ring.agent.builtin_servlets.reminders

import coredevices.ring.data.entity.room.reminders.LocalReminderData
import kotlin.time.Duration
import kotlin.time.Instant

actual fun createBuiltInReminder(time: Instant?, message: String, notifyBefore: Duration?): ListAssignableReminder {
    return AndroidBuiltInReminder(time, message, notifyBefore)
}

actual fun builtInReminderFromData(data: LocalReminderData): ListAssignableReminder {
    return AndroidBuiltInReminder.fromData(data)
}

actual fun createRemindersAppReminder(time: Instant?, message: String, notifyBefore: Duration?): ListAssignableReminder {
    throw NotImplementedError() // iOS only
}
