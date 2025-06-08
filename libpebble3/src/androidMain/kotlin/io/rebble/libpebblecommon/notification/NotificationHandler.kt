package io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification

import android.app.Notification
import android.app.RemoteInput
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.NotificationConfigFlow
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.TimeProvider
import io.rebble.libpebblecommon.database.asMillisecond
import io.rebble.libpebblecommon.database.dao.NotificationAppRealDao
import io.rebble.libpebblecommon.database.entity.ChannelItem
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.database.entity.NotificationAppItem
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.util.PrivateLogger
import io.rebble.libpebblecommon.util.obfuscate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class NotificationHandler(
    private val notificationProcessors: Set<NotificationProcessor>,
    private val notificationAppDao: NotificationAppRealDao,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
    private val timeProvider: TimeProvider,
    private val notificationConfig: NotificationConfigFlow,
    private val privateLogger: PrivateLogger,
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
        if (notificationConfig.value.dumpNotificationContent) {
            sbn.dump()
        }
        if (sbn.isOngoing) {
            verboseLog { "Ignoring ongoing notification from ${sbn.packageName.obfuscate(privateLogger)}" }
            return null
        }
        if (sbn.notification.isLocalOnly() && !notificationConfig.value.sendLocalOnlyNotifications) {
            verboseLog { "Ignoring local-only notification from ${sbn.packageName.obfuscate(privateLogger)}" }
            return null
        }
        val appEntry = notificationAppDao.getEntry(sbn.packageName)
        if (appEntry == null) {
            verboseLog { "Ignoring system app notification from ${sbn.packageName.obfuscate(privateLogger)}" }
            return null
        }
        notificationAppDao.insertOrReplace(appEntry.copy(lastNotified = timeProvider.now().asMillisecond()))
        if (appEntry.muteState == MuteState.Always) {
            verboseLog { "Ignoring muted app notification from ${sbn.packageName.obfuscate(privateLogger)}" }
            return null
        }
        val channel = appEntry.getChannelFor(sbn)
        if (channel != null && channel.muteState == MuteState.Always) {
            verboseLog { "Ignoring muted app channel (${channel.name.obfuscate(privateLogger)}) from ${sbn.packageName.obfuscate(privateLogger)}" }
            return null
        }
        logger.d { "Processing notification from ${sbn.packageName}" }
        for (processor in notificationProcessors) {
            try {
                when (val result = processor.processNotification(sbn, appEntry, channel)) {
                    is NotificationResult.Processed -> {
                        if (verboseLogging) {
                            logger.v { "Notification from ${sbn.packageName.obfuscate(privateLogger)} processed by ${processor::class.simpleName}" }
                        }
                        return result.notification
                    }

                    is NotificationResult.Ignored -> {
                        if (verboseLogging) {
                            logger.v { "Ignoring notification from ${sbn.packageName.obfuscate(privateLogger)}" }
                        }
                        break
                    }

                    is NotificationResult.NotProcessed -> { /* Continue */
                    }
                }
            } catch (e: Exception) {
                logger.e(e) { "Error processing notification from ${sbn.packageName.obfuscate(privateLogger)}" }
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

    private fun StatusBarNotification.dump() {
        logger.v { """
New notification:
    id = $id
    key = ${key.obfuscate(privateLogger)}
    groupKey = ${groupKey.obfuscate(privateLogger)}
    postTime = $postTime
    tag = ${tag.obfuscate(privateLogger)}
    pkg = ${packageName.obfuscate(privateLogger)}
    ongoing = $isOngoing
    when = ${notification.`when`}
    number = ${notification.number}
    tickerText = ${notification.tickerText.obfuscate(privateLogger)}
    color = ${notification.color}
    visibility = ${notification.visibility}
    category = ${notification.category}
    groupKey(n) = ${notification.group.obfuscate(privateLogger)}
    flags = ${notification.flags}
    isGroupSummary = ${notification.isGroupSummary()}
    isLocalOnly = ${notification.isLocalOnly()}
    channelId = ${notification.dumpChannel().obfuscate(privateLogger)}
    groupAlertBehavior = ${notification.dumpGroupAlertBehaviour()}
    extras: ${notification.extras.dump(8)}
    actions = ${notification.dumpActions()}
        """.trimIndent() }
    }

    private fun Notification.dumpChannel(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return "<before channels>"
        return channelId
    }

    private fun Notification.dumpGroupAlertBehaviour(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return when (groupAlertBehavior) {
            Notification.GROUP_ALERT_ALL -> "GROUP_ALERT_ALL"
            Notification.GROUP_ALERT_CHILDREN -> "GROUP_ALERT_CHILDREN"
            Notification.GROUP_ALERT_SUMMARY -> "GROUP_ALERT_SUMMARY"
            else -> "Unknown"
        }
    }

    private fun Bundle.dump(indent: Int): String {
        val newlineIndent = "\n${" ".repeat(indent)}"
        return keySet().joinToString(prefix = newlineIndent, separator = newlineIndent) {
            "$it = ${get(it)}"
        }
    }

    private fun Notification.dumpActions(): String {
        val newlineIndent = "\n${" ".repeat(8)}"
        return actions?.joinToString(prefix = "\n", separator = "\n") { action ->
"""        Action:
            title = ${action.title}
            extras: ${action.extras.dump(12)}
            remoteInputs: ${action.remoteInputs.dump(12)}"""
        } ?: "[]"
    }

    private fun Array<RemoteInput>?.dump(indent: Int): String {
        if (this == null) return "null"
        val newlineIndent = "\n${" ".repeat(indent)}"
        return this.joinToString(prefix = newlineIndent, separator = newlineIndent) { remoteInput ->
            """
        RemoteInput:
                        label = ${remoteInput.label}
                        allowFreeFormInput = ${remoteInput.allowFreeFormInput}
                        isDataOnly = ${remoteInput.dumpDataOnly()}
                """.trimIndent()
        }
    }
}

fun Notification.isGroupSummary(): Boolean = (flags and Notification.FLAG_GROUP_SUMMARY) != 0
fun Notification.isLocalOnly(): Boolean = (flags and Notification.FLAG_LOCAL_ONLY) != 0
fun RemoteInput.dumpDataOnly(): Boolean? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
    return isDataOnly
}