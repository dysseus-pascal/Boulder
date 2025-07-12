package io.rebble.libpebblecommon.notification

import androidx.compose.ui.graphics.ImageBitmap
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.NotificationApps
import io.rebble.libpebblecommon.database.dao.AppWithCount
import io.rebble.libpebblecommon.database.dao.ChannelAndCount
import io.rebble.libpebblecommon.database.dao.NotificationAppRealDao
import io.rebble.libpebblecommon.database.dao.NotificationDao
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.database.entity.NotificationEntity
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface NotificationListenerConnection {
    fun init(libPebble: LibPebble)
}

interface NotificationAppsSync {
    fun init()
}

class NotificationApi(
    private val notificationAppsSync: NotificationAppsSync,
    private val notificationAppDao: NotificationAppRealDao,
    private val notificationsDao: NotificationDao,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
    private val appContext: AppContext,
) : NotificationApps {
    fun init() {
        notificationAppsSync.init()
    }

    override val notificationApps: Flow<List<AppWithCount>> =
        notificationAppDao.allAppsWithCountsFlow()

    override fun notificationAppChannelCounts(packageName: String): Flow<List<ChannelAndCount>> =
        notificationsDao.channelNotificationCounts(packageName)

    override fun mostRecentNotificationsFor(
        pkg: String,
        channelId: String?,
        limit: Int,
    ): Flow<List<NotificationEntity>> = notificationsDao.mostRecentNotificationsFor(
        pkg = pkg,
        channelId = channelId,
        limit = limit,
    )

    override fun updateNotificationAppMuteState(packageName: String, muteState: MuteState) {
        libPebbleCoroutineScope.launch {
            notificationAppDao.updateAppMuteState(packageName, muteState)
        }
    }

    override fun updateNotificationChannelMuteState(
        packageName: String,
        channelId: String,
        muteState: MuteState,
    ) {
        libPebbleCoroutineScope.launch {
            val appEntry = notificationAppDao.getEntry(packageName) ?: return@launch
            notificationAppDao.insertOrReplace(appEntry.copy(channelGroups = appEntry.channelGroups.map { g ->
                g.copy(channels = g.channels.map { c ->
                    if (c.id == channelId) {
                        c.copy(muteState = muteState)
                    } else {
                        c
                    }
                })
            }))
        }
    }

    override suspend fun getAppIcon(packageName: String): ImageBitmap? {
        return withContext(Dispatchers.IO) {
            iconFor(packageName, appContext)
        }
    }
}

expect fun iconFor(packageName: String, appContext: AppContext): ImageBitmap?
