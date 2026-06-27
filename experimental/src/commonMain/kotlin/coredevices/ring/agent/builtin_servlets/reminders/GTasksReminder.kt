package coredevices.ring.agent.builtin_servlets.reminders

import coredevices.ring.agent.integrations.GTasksIntegration
import kotlin.time.Duration
import kotlin.time.Instant
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class GTasksReminder(
    override val time: Instant?,
    override val message: String,
    // The Google Tasks API exposes only a due date — there is no per-task notification lead time —
    // so [notifyBefore] is accepted for interface parity but cannot be honoured.
    override val notifyBefore: Duration? = null,
) : ListAssignableReminder, KoinComponent {
    private val gTasksIntegration: GTasksIntegration by inject()
    private var taskId: String? = null
    private var _listTitle: String? = null
    override val listTitle: String?
        get() = _listTitle

    override suspend fun schedule(): String {
        return gTasksIntegration.createReminder(message, time, null)
            ?: throw Exception("Failed to create reminder in Google Tasks")
    }

    override suspend fun cancel() {

    }

    override suspend fun scheduleToList(listName: String): String {
        val lists = gTasksIntegration.searchForList(listName)
        if (lists.isEmpty()) {
            throw ListNotFoundException(listName)
        }
        val list = lists.first()
        val id = gTasksIntegration.createReminder(message, time, list.id)
            ?: throw Exception("Failed to create reminder in Google Tasks")
        taskId = id
        _listTitle = list.title
        return id
    }
}
