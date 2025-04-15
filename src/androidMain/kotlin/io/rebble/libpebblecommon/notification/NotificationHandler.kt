package io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification

import android.service.notification.StatusBarNotification
import co.touchlab.kermit.Logger
import kotlinx.coroutines.channels.Channel
import kotlin.uuid.Uuid

class NotificationHandler(private val notificationProcessors: Set<NotificationProcessor>) {
    companion object {
        private val logger = Logger.withTag(NotificationHandler::class.simpleName!!)
    }
    //TODO: Datastore
    private val verboseLogging: Boolean = true
    private val inflightNotifications = mutableMapOf<String, LibPebbleNotification>()
    val notificationSendQueue = Channel<LibPebbleNotification>(Channel.BUFFERED)
    val notificationDeleteQueue = Channel<Uuid>(Channel.BUFFERED)

    private fun StatusBarNotification.isSystemApp(): Boolean {
        //TODO: Check if the app is a system app
        return false
    }

    fun getNotificationAction(itemId: Uuid, actionId: UByte): LibPebbleNotificationAction? {
        val notification = getNotification(itemId)
        return notification?.actions?.get(actionId.toInt())
    }

    fun getNotification(itemId: Uuid): LibPebbleNotification? {
        return inflightNotifications.values.firstOrNull { it.uuid == itemId }
    }

    private fun processNotification(sbn: StatusBarNotification): LibPebbleNotification? {
        when {
            sbn.isOngoing -> {
                if (verboseLogging) {
                    logger.v { "Ignoring ongoing notification from ${sbn.packageName}" }
                }
            }
            sbn.isSystemApp() -> {
                if (verboseLogging) {
                    logger.v { "Ignoring system app notification from ${sbn.packageName}" }
                }
            }
            else -> {
                logger.d { "Processing notification from ${sbn.packageName}" }
                for (processor in notificationProcessors) {
                    try {
                        when (val result = processor.processNotification(sbn)) {
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
                            is NotificationResult.NotProcessed -> { /* Continue */ }
                        }
                    } catch (e: Exception) {
                        logger.e(e) { "Error processing notification from ${sbn.packageName}" }
                    }
                }
            }
        }
        return null
    }

    fun setActiveNotifications(notifications: List<StatusBarNotification>) {
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

    fun handleNotificationPosted(sbn: StatusBarNotification) {
        val notification = processNotification(sbn) ?: return
        if (inflightNotifications.values.any { it.displayDataEquals(notification) }) {
            logger.d { "Notification ${sbn.key} already in inflight" }
            return
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