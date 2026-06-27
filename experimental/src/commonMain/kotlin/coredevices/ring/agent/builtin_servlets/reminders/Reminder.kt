package coredevices.ring.agent.builtin_servlets.reminders

import kotlin.time.Duration
import kotlin.time.Instant

interface Reminder {
    val time: Instant?
    val message: String
    val notifyBefore: Duration?
    suspend fun schedule(): String
    suspend fun cancel()

    /**
     * Cancels just the early "heads-up" notification (if one was scheduled), leaving the main
     * reminder intact. No-op for integrations that don't schedule a separate early notification.
     */
    suspend fun cancelExtraNotification() {}
}

interface ListAssignableReminder : Reminder {
    suspend fun scheduleToList(listName: String): String
    val listTitle: String?
}

class ListNotFoundException(listName: String) : Exception("List with name '$listName' not found")