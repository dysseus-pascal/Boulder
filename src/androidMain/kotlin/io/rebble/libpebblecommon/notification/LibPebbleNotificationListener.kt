package io.rebble.libpebblecommon.notification

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.LibPebbleNotificationListenerConnection
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.NotificationHandler
import io.rebble.libpebblecommon.notification.processor.BasicNotificationProcessor
import kotlin.uuid.Uuid

class LibPebbleNotificationListener: NotificationListenerService() {
    val notificationHandler = NotificationHandler(
        setOf(BasicNotificationProcessor())
    )
    companion object {
        private val logger = Logger.withTag(LibPebbleNotificationListener::class.simpleName!!)
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): LibPebbleNotificationListener = this@LibPebbleNotificationListener
    }

    fun cancelNotification(itemId: Uuid) {
        val sbn = notificationHandler.getNotification(itemId) ?: return
        cancelNotification(sbn.key)
    }

    override fun onBind(intent: Intent?): IBinder? {
        logger.d { "onBind()" }
        return if (intent?.action == LibPebbleNotificationListenerConnection.ACTION_BIND_LOCAL) {
            binder
        } else {
            super.onBind(intent)
        }
    }

    override fun onListenerConnected() {
        logger.d { "onListenerConnected()" }
        notificationHandler.setActiveNotifications(getActiveNotifications().toList())
    }

    override fun onListenerDisconnected() {
        logger.d { "onListenerDisconnected()" }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        logger.d { "onNotificationPosted(${sbn.packageName})" }
        notificationHandler.handleNotificationPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        logger.d { "onNotificationRemoved(${sbn.packageName})" }
        notificationHandler.handleNotificationRemoved(sbn)
    }
}