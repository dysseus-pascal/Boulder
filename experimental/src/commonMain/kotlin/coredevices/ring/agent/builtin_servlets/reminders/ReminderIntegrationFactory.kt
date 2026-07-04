package coredevices.ring.agent.builtin_servlets.reminders

import co.touchlab.kermit.Logger
import coredevices.ring.agent.integrations.GTasksIntegration
import coredevices.ring.agent.integrations.ReminderIntegration
import coredevices.ring.database.Preferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

class ReminderIntegrationFactory(
    private val preferences: Preferences,
) : KoinComponent {
    companion object {
        private val logger = Logger.withTag("ReminderIntegrationFactory")
    }

    fun createReminderIntegration(
        provider: ReminderProvider = preferences.reminderProvider.value,
    ): ReminderIntegration {
        logger.i { "Creating reminder integration for provider: $provider" }
        return when (provider) {
            ReminderProvider.BuiltIn -> get<BuiltInReminderIntegration>()
            ReminderProvider.GoogleTasks -> get<GTasksIntegration>()
            ReminderProvider.IOSReminders -> createRemindersAppIntegration()
            ReminderProvider.Tasker -> createTaskerReminderIntegration()
        }
    }
}
