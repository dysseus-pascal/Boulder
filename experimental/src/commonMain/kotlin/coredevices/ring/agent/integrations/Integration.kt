package coredevices.ring.agent.integrations

import coredevices.util.integrations.Integration
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Manages reminders for one provider. Like [NoteIntegration], implementations are singletons that
 * manage reminders on a platform/service — not objects representing a single reminder. Obtain the
 * active provider's instance from `ReminderIntegrationFactory`.
 */
interface ReminderIntegration : Integration {
    /**
     * Schedules a reminder and returns its id (throws on failure). [listId] targets a list found
     * via [searchForList]; [notifyBefore] requests an extra early heads-up notification before
     * [deadline]. Integrations that can't honour either simply ignore them.
     */
    suspend fun createReminder(
        title: String,
        deadline: Instant?,
        listId: String? = null,
        notifyBefore: Duration? = null,
    ): String

    suspend fun searchForList(listName: String): List<ReminderListEntry>
}

data class ReminderListEntry(
    val id: String,
    val title: String
)

interface NoteIntegration : Integration {
    suspend fun createNote(content: String): String?
}