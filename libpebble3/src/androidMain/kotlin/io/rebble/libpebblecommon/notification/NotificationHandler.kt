package io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification

import android.os.Build
import android.service.notification.StatusBarNotification
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.database.dao.NotificationAppRealDao
import io.rebble.libpebblecommon.database.entity.ChannelItem
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.database.entity.NotificationAppItem
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class NotificationHandler(
    private val notificationProcessors: Set<NotificationProcessor>,
    private val notificationAppDao: NotificationAppRealDao,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
) {
    companion object {
        private val logger = Logger.withTag(NotificationHandler::class.simpleName!!)
    }

    //TODO: Datastore
    private val verboseLogging: Boolean = true
    private val inflightNotifications = mutableMapOf<String, LibPebbleNotification>()
    val notificationSendQueue = Channel<LibPebbleNotification>(Channel.BUFFERED)
    val notificationDeleteQueue = Channel<Uuid>(Channel.BUFFERED)

    fun getNotificationAction(itemId: Uuid, actionId: UByte): LibPebbleNotificationAction? {
        val notification = getNotification(itemId)
        return notification?.actions?.get(actionId.toInt())
    }

    fun getNotification(itemId: Uuid): LibPebbleNotification? {
        return inflightNotifications.values.firstOrNull { it.uuid == itemId }
    }

    private fun verboseLog(message: () -> String) {
        if (verboseLogging) {
            logger.v { message() }
        }
    }

    private fun NotificationAppItem.getChannelFor(sbn: StatusBarNotification): ChannelItem? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val channelId = sbn.notification.channelId ?: return null
        return channelGroups.flatMap { it.channels }.find { it.id == channelId }
    }

    private suspend fun processNotification(sbn: StatusBarNotification): LibPebbleNotification? {
        if (sbn.isOngoing) {
            verboseLog { "Ignoring ongoing notification from ${sbn.packageName}" }
            return null
        }
        val appEntry = notificationAppDao.getEntry(sbn.packageName)
        if (appEntry == null) {
            verboseLog { "Ignoring system app notification from ${sbn.packageName}" }
            return null
        }
        if (appEntry.muteState == MuteState.Always) {
            verboseLog { "Ignoring muted app notification from ${sbn.packageName}" }
            return null
        }
        val channel = appEntry.getChannelFor(sbn)
        if (channel != null && channel.muteState == MuteState.Always) {
            verboseLog { "Ignoring muted app channel (${channel.name}) from ${sbn.packageName}" }
            return null
        }
        logger.d { "Processing notification from ${sbn.packageName}" }
        for (processor in notificationProcessors) {
            try {
                when (val result = processor.processNotification(sbn, appEntry, channel)) {
                    is NotificationResult.Processed -> {
                        if (verboseLogging) {
                            logger.v { "Notification from ${sbn.packageName} processed by ${processor::class.simpleName}" }
                        }
                        return result.notification
                    }

                    is NotificationResult.Ignored -> {
                        if (verboseLogging) {
                            logger.v { "Ignoring notification from ${sbn.packageName}" }
                        }
                        break
                    }

                    is NotificationResult.NotProcessed -> { /* Continue */
                    }
                }
            } catch (e: Exception) {
                logger.e(e) { "Error processing notification from ${sbn.packageName}" }
            }
        }
        return null
    }

    fun setActiveNotifications(notifications: List<StatusBarNotification>) =
        libPebbleCoroutineScope.launch {

            val newNotifs = notifications.mapNotNull { sbn ->
                if (inflightNotifications.any { it.key == sbn.key }) {
                    return@mapNotNull null
                }
                val notification = processNotification(sbn) ?: return@mapNotNull null
                // Check if the notification is already in the list
                if (inflightNotifications.values.any { it.displayDataEquals(notification) }) {
                    return@mapNotNull null
                }
                notification
            }
            newNotifs.forEach {
                sendNotification(it)
            }
        }

    private fun sendNotification(notification: LibPebbleNotification) {
        inflightNotifications[notification.key] = notification
        notificationSendQueue.trySend(notification)
    }

    fun handleNotificationPosted(sbn: StatusBarNotification) = libPebbleCoroutineScope.launch {
        val notification = processNotification(sbn) ?: return@launch
        if (inflightNotifications.values.any { it.displayDataEquals(notification) }) {
            logger.d { "Notification ${sbn.key} already in inflight" }
            return@launch
        }
        sendNotification(notification)
    }

    fun handleNotificationRemoved(sbn: StatusBarNotification) {
        val inflight = inflightNotifications[sbn.key]
        if (inflight != null) {
            inflightNotifications.remove(sbn.key)
            notificationDeleteQueue.trySend(inflight.uuid)
        } else {
            logger.d { "Failed to remove notification: ${sbn.key} not found in inflight" }
        }
    }
}