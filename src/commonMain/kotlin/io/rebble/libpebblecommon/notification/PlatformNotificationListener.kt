package io.rebble.libpebblecommon.notification

import androidx.compose.ui.graphics.ImageBitmap
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.NotificationApps
import io.rebble.libpebblecommon.database.dao.NotificationAppDao
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.database.entity.NotificationAppEntity
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

interface NotificationListenerConnection {
    fun init(libPebble: LibPebble)
}

interface NotificationAppsSync {
    suspend fun syncAppsFromOS()
}

data class NotificationAppWithIcon(
    val app: NotificationAppEntity,
    val icon: ImageBitmap?,
)

class NotificationApi(
    private val notificationAppsSync: NotificationAppsSync,
    private val notificationAppDao: NotificationAppDao,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
    private val appContext: AppContext,
) : NotificationApps {
    override val notificationApps: Flow<List<NotificationAppWithIcon>> =
        notificationAppDao.allAppsFlow()
            .map { it.map { NotificationAppWithIcon(it, iconFor(it.packageName, appContext)) } }

    override fun updateNotificationAppMuteState(packageName: String, muteState: MuteState) {
        libPebbleCoroutineScope.launch {
            notificationAppDao.updateAppMuteState(packageName, muteState)
        }
    }

    override fun syncAppsFromOS() {
        libPebbleCoroutineScope.launch {
            notificationAppsSync.syncAppsFromOS()
        }
    }
}

expect fun iconFor(packageName: String, appContext: AppContext): ImageBitmap?
