package io.rebble.libpebblecommon.notification

import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.UserHandle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.annotation.RequiresApi
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.AndroidPebbleNotificationListenerConnection
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.NotificationHandler
import io.rebble.libpebblecommon.notification.processor.BasicNotificationProcessor
import kotlin.uuid.Uuid

class LibPebbleNotificationListener : NotificationListenerService() {
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
        return if (intent?.action == AndroidPebbleNotificationListenerConnection.ACTION_BIND_LOCAL) {
            binder
        } else {
            super.onBind(intent)
        }
    }

    override fun onListenerConnected() {
        logger.d { "onListenerConnected()" }
        notificationHandler.setActiveNotifications(getActiveNotifications().toList())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getChannelsFor("com.google.android.apps.dynamite", Process.myUserHandle())
            getChannelsFor("com.whatsapp", Process.myUserHandle())
            getChannelsFor("com.google.android.apps.messaging", Process.myUserHandle())
        }
    }

    override fun onListenerDisconnected() {
        logger.d { "onListenerDisconnected()" }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun getChannelsFor(pkg: String, user: UserHandle) {
        try {
            val groupsForApp = getNotificationChannelGroups(pkg, user)
            logger.d("groupsForApp ($pkg) = $groupsForApp")
            groupsForApp.forEach { group ->
                logger.d("group ($pkg): id=${group.id} channels=${group.channels} name=${group.name}")
            }
            val channelsForApp = getNotificationChannels(pkg, user)
            channelsForApp.forEach { channel ->
                logger.d("channel ($pkg): id=${channel.id} name=${channel.name} group=${channel.group} description=${channel.description}")
            }
            val icon = applicationContext.packageManager.getApplicationIcon(pkg)
            logger.d("$pkg icon=$icon")
        } catch (e: Exception) {
            logger.w("getChannelsFor", e)
        }
        try {
            val app = applicationContext.packageManager.getApplicationInfo(
                pkg,
                0,
            )
            val name = applicationContext.packageManager.getApplicationLabel(app)
            logger.d("$pkg app label=$name")
        } catch (e: Exception) {
            logger.w("getting name", e)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        logger.d { "onNotificationPosted(${sbn.packageName})" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = sbn.notification.channelId
            logger.d("onNotificationPosted channelId = $channelId")
            getChannelsFor(sbn.packageName, sbn.user)

            // FIXME testing
            getChannelsFor("com.google.android.apps.dynamite", Process.myUserHandle())
            getChannelsFor("com.google.android.apps.dynamite", sbn.user)
        }
        notificationHandler.handleNotificationPosted(sbn)
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap,
        reason: Int
    ) {
        logger.d { "onNotificationRemoved(${sbn.packageName})" }
        notificationHandler.handleNotificationRemoved(sbn)
    }
}