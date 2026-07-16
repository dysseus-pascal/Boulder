package coredevices.ring.agent.integrations

import coredevices.ring.agent.builtin_servlets.reminders.ReminderProvider
import coredevices.ring.api.TickTickApi
import coredevices.ring.data.IntegrationDefinition
import coredevices.util.integrations.IntegrationAuthException
import coredevices.util.integrations.IntegrationTokenStorage
import coredevices.util.integrations.OAuthIntegration
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Instant

class TickTickIntegration(
    private val tickTickApi: TickTickApi,
    tokenStorage: IntegrationTokenStorage,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : ReminderIntegration, OAuthIntegration(tickTickApi, tokenStorage, TOKEN_STORAGE_KEY) {
    override val oauthPathSegment: String = "ticktick"

    companion object {
        private const val TOKEN_STORAGE_KEY = "ticktick"
        val DEFINITION = IntegrationDefinition(
            title = "TickTick",
            reminder = ReminderProvider.TickTick,
            notes = null
        )
    }

    // Task links need the project the task landed in (the Inbox id is only known from the
    // create response), so remember it per created task. Creates are serialized by the
    // recording-processing queue, so a plain map is fine.
    private val createdTaskProjects = mutableMapOf<String, String>()

    override suspend fun createReminder(
        title: String,
        deadline: Instant?,
        listId: String?,
        notifyBefore: Duration?,
        source: ItemSource?,
    ): String {
        if (!isAuthorized()) throw IntegrationAuthException("TickTick not authorized")
        val task = tickTickApi.createTask(
            token = requireToken(),
            title = title,
            dueDate = deadline,
            timeZone = timeZone,
            projectId = listId,
            reminders = tickTickTriggers(deadline, notifyBefore),
        )
        val id = task.id ?: throw Exception("Failed to create reminder in TickTick")
        task.projectId?.let { createdTaskProjects[id] = it }
        return id
    }

    override fun reminderUrl(id: String, listId: String?): String? {
        val projectId = createdTaskProjects[id] ?: listId ?: return null
        return tickTickTaskUrl(projectId, id)
    }

    override suspend fun searchForList(listName: String): List<ReminderListEntry> {
        if (!isAuthorized()) throw IntegrationAuthException("TickTick not authorized")
        return tickTickApi.getProjects(requireToken())
            .filter { it.closed != true && it.name?.contains(listName, ignoreCase = true) == true }
            .mapNotNull { project ->
                val id = project.id ?: return@mapNotNull null
                val name = project.name ?: return@mapNotNull null
                ReminderListEntry(id, name)
            }
    }
}

/** Opens the task in the TickTick web app; the TickTick mobile app claims this link on Android. */
internal fun tickTickTaskUrl(projectId: String, taskId: String): String =
    "https://ticktick.com/webapp/#p/$projectId/tasks/$taskId"

/**
 * iCal VALARM triggers: "TRIGGER:PT0S" notifies at the due time, a negative duration notifies
 * that long before it. Without a deadline there is nothing to trigger from.
 */
internal fun tickTickTriggers(deadline: Instant?, notifyBefore: Duration?): List<String> {
    if (deadline == null) return emptyList()
    return buildList {
        add("TRIGGER:PT0S")
        if (notifyBefore != null && notifyBefore > Duration.ZERO) {
            add("TRIGGER:-${notifyBefore.toIsoString()}")
        }
    }
}
