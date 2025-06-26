package io.rebble.libpebblecommon.notification

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.UserHandle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.database.entity.ChannelGroup
import io.rebble.libpebblecommon.database.entity.ChannelItem
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.AndroidPebbleNotificationListenerConnection
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.NotificationHandler
import org.koin.core.component.KoinComponent
import kotlin.uuid.Uuid

class LibPebbleNotificationListener : NotificationListenerService(), KoinComponent {
    companion object {
        private val logger = Logger.withTag("LibPebbleNotificationListener")
        fun componentName(context: Context) = ComponentName(context, LibPebbleNotificationListener::class.java)
    }

    override fun onCreate() {
        super.onCreate()
        logger.v { "onCreate: ($this)" }
    }

    private val binder = LocalBinder()
    private var notificationHandler: NotificationHandler? = null

    inner class LocalBinder : Binder() {
        fun getService(): LibPebbleNotificationListener = this@LibPebbleNotificationListener
    }

    fun setNotificationHandler(handler: NotificationHandler) {
        notificationHandler = handler
    }

    fun cancelNotification(itemId: Uuid) {
        val sbn = notificationHandler?.getNotification(itemId) ?: return
        cancelNotification(sbn.key)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == AndroidPebbleNotificationListenerConnection.ACTION_BIND_LOCAL) {
            logger.d { "onBind() ACTION_BIND_LOCAL  ($this)" }
            binder
        } else {
            logger.d { "onBind()  ($this)" }
            super.onBind(intent)
        }
    }

    override fun onListenerConnected() {
        logger.d { "onListenerConnected() ($this)" }
        try {
            notificationHandler?.setActiveNotifications(getActiveNotifications().toList())
        } catch (e: SecurityException) {
            logger.e("error getting active notifications", e)
        }
    }

    override fun onListenerDisconnected() {
        logger.d { "onListenerDisconnected() ($this)" }
    }

    override fun onNotificationChannelModified(
        pkg: String,
        user: UserHandle,
        channel: NotificationChannel,
        modificationType: Int,
    ) {
        notificationHandler?.onChannelChanged()
    }

    override fun onNotificationChannelGroupModified(
        pkg: String,
        user: UserHandle,
        group: NotificationChannelGroup,
        modificationType: Int,
    ) {
        notificationHandler?.onChannelChanged()
    }

    private data class MutableGroup(
        val id: String?,
        val name: String?,
        val channels: MutableList<ChannelItem>,
    )

    fun getChannelsForApp(packageName: String): List<ChannelGroup> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()
        try {
            val user = Process.myUserHandle()
            val groups = getNotificationChannelGroups(packageName, user)
                .map { MutableGroup(it.id, it.name.toString(), mutableListOf()) }
                .associateBy { it.id }.toMutableMap()
            val channels = getNotificationChannels(packageName, user)
            channels.forEach { channel ->
                val channelItem = ChannelItem(
                    id = channel.id,
                    name = channel.name.toString(),
                    muteState = MuteState.Never,
                )
                val group = groups[channel.group]
                if (group == null) {
                    // Some channels don't have groups - but we want them all to have groups.
                    groups[channel.group] = MutableGroup(
                        id = channel.group,
                        name = null,
                        channels = mutableListOf(channelItem),
                    )
                } else {
                    group.channels += channelItem
                }
            }
            return groups.values.map {
                ChannelGroup(
                    id = it.id ?: "default",
                    name = it.name,
                    channels = it.channels.toList(),
                )
            }
        } catch (e: Exception) {
            logger.w("getChannelsFor", e)
            return emptyList()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        logger.d { "onNotificationPosted(${sbn.packageName})  ($this)" }
        notificationHandler?.handleNotificationPosted(sbn)
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap,
        reason: Int
    ) {
        logger.d { "onNotificationRemoved(${sbn.packageName})  ($this)" }
        notificationHandler?.handleNotificationRemoved(sbn)
    }
}