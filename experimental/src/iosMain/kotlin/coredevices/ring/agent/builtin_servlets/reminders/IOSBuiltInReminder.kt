package coredevices.ring.agent.builtin_servlets.reminders

import co.touchlab.kermit.Logger
import coredevices.ring.data.entity.room.reminders.LocalReminderData
import coredevices.ring.database.room.RingDatabase
import coredevices.ring.reminders.ReminderDeepLinkResolver
import kotlinx.datetime.toNSDate
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitSecond
import platform.Foundation.NSCalendarUnitYear
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * iOS counterpart to [AndroidBuiltInReminder]: schedules a local notification via
 * [UNUserNotificationCenter] and records the reminder in [LocalReminderData] so it shows up
 * in the in-app reminders list and can be cancelled.
 */
class IOSBuiltInReminder(
    override val time: Instant?,
    override val message: String,
    override val notifyBefore: Duration? = null,
) : ListAssignableReminder, KoinComponent {
    private val db: RingDatabase by inject()
    private val notificationCenter = UNUserNotificationCenter.currentNotificationCenter()

    private var _reminderId: Int? = null
    val reminderId: Int? get() = _reminderId
    override val listTitle: String? = null

    private constructor(time: Instant?, message: String, notifyBefore: Duration?, reminderId: Int) : this(time, message, notifyBefore) {
        _reminderId = reminderId
    }

    private suspend fun requestAuthorization(): Boolean = suspendCoroutine { continuation ->
        notificationCenter.requestAuthorizationWithOptions(
            UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge
        ) { granted, error ->
            if (error != null) {
                logger.e { "Error requesting notification permission: $error" }
            }
            continuation.resume(granted)
        }
    }

    override suspend fun schedule(): String {
        require(time == null || time > Clock.System.now()) { "Time must be in the future" }
        check(requestAuthorization()) { "Notification permission not granted" }

        val id = db.localReminderDao().insertReminder(
            LocalReminderData(0, time, message, notifyBeforeMillis = notifyBefore?.inWholeMilliseconds)
        ).toInt()
        _reminderId = id

        time?.let { scheduledTime ->
            scheduleNotification(id, scheduledTime, notificationId(id), title = "Reminder")
            // The early heads-up notification is only scheduled when its trigger time is still ahead.
            notifyBefore?.let { lead ->
                val preTime = scheduledTime - lead
                if (preTime > Clock.System.now()) {
                    scheduleNotification(id, preTime, preNotificationId(id), title = "Upcoming reminder")
                }
            }
        }
        return id.toString()
    }

    private suspend fun scheduleNotification(reminderId: Int, triggerTime: Instant, identifier: String, title: String) {
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(message)
            setSound(UNNotificationSound.defaultSound)
            setUserInfo(mapOf<Any?, Any?>(ReminderDeepLinkResolver.USERINFO_REMINDER_ID to reminderId.toString()))
        }
        val components = NSCalendar.currentCalendar.components(
            NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay
                    or NSCalendarUnitHour or NSCalendarUnitMinute or NSCalendarUnitSecond,
            fromDate = triggerTime.toNSDate()
        )
        val trigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(components, repeats = false)
        val request = UNNotificationRequest.requestWithIdentifier(identifier, content, trigger)
        val error = suspendCoroutine { continuation ->
            notificationCenter.addNotificationRequest(request) { error -> continuation.resume(error) }
        }
        check(error == null) { "Failed to schedule reminder notification: ${error?.localizedDescription}" }
    }

    override suspend fun cancel() {
        val reminderId = _reminderId ?: return
        val identifiers = listOf(notificationId(reminderId), preNotificationId(reminderId))
        notificationCenter.removePendingNotificationRequestsWithIdentifiers(identifiers)
        notificationCenter.removeDeliveredNotificationsWithIdentifiers(identifiers)
        db.localReminderDao().deleteReminder(reminderId)
        _reminderId = null
    }

    override suspend fun cancelExtraNotification() {
        val reminderId = _reminderId ?: return
        val identifiers = listOf(preNotificationId(reminderId))
        notificationCenter.removePendingNotificationRequestsWithIdentifiers(identifiers)
        notificationCenter.removeDeliveredNotificationsWithIdentifiers(identifiers)
        db.localReminderDao().clearNotifyBefore(reminderId)
    }

    override suspend fun scheduleToList(listName: String): String {
        return schedule()
    }

    companion object {
        private val logger = Logger.withTag("IOSBuiltInReminder")

        private fun notificationId(reminderId: Int) = "ring-reminder-$reminderId"
        private fun preNotificationId(reminderId: Int) = "ring-reminder-pre-$reminderId"

        fun fromData(data: LocalReminderData): IOSBuiltInReminder {
            return IOSBuiltInReminder(data.time, data.message, data.notifyBeforeMillis?.milliseconds, data.id)
        }
    }
}
