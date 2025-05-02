package io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.database.dao.NotificationAppDao
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.database.entity.NotificationAppEntity
import io.rebble.libpebblecommon.notification.NotificationAppsSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

class AndroidNotificationAppsSync(
    private val context: AppContext,
    private val notificationAppDao: NotificationAppDao,
    private val clock: Clock,
    private val notificationListenerConnection: AndroidPebbleNotificationListenerConnection,
) : NotificationAppsSync {
    private val logger = Logger.withTag("NotificationAppsSync")

    override suspend fun syncAppsFromOS() = withContext(Dispatchers.IO) {
        logger.d("syncAppsFromOS")
        val pm = context.context.packageManager
        val existingApps =
            notificationAppDao.allApps().associateBy { it.packageName }.toMutableMap()
        val osApps = pm.getInstalledApplications(0)
        osApps.onEach { osApp ->
            val existing = existingApps.remove(osApp.packageName)
            val channels = notificationListenerConnection.getChannelsForApp(osApp.packageName)
            val name = pm.getApplicationLabel(osApp).toString()
            if (existing == null) {
                logger.d("adding ${osApp.packageName}")
                notificationAppDao.insertOrIgnore(
                    NotificationAppEntity(
                        packageName = osApp.packageName,
                        name = name,
                        muteState = MuteState.Never,
                        channelGroups = channels,
                        stateUpdated = clock.now(),
                        lastNotified = Instant.DISTANT_PAST,
                    )
                )
            } else if (existing.name != name || existing.channelGroups != channels) {
                notificationAppDao.insertOrReplace(
                    existing.copy(
                        name = name,
                        channelGroups = channels,
                    )
                )
            }
        }
        existingApps.values.forEach { app ->
            logger.d("deleting $app")
            notificationAppDao.delete(app)
        }
        logger.d("/syncAppsFromOS")
    }
}