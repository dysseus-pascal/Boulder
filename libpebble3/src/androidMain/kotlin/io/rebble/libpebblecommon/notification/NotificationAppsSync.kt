package io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.TimeProvider
import io.rebble.libpebblecommon.database.asMillisecond
import io.rebble.libpebblecommon.database.dao.NotificationAppRealDao
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.database.entity.NotificationAppItem
import io.rebble.libpebblecommon.notification.NotificationAppsSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant

class AndroidNotificationAppsSync(
    private val context: AppContext,
    private val notificationAppDao: NotificationAppRealDao,
    private val timeProvider: TimeProvider,
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
            // null = this is a system app
            pm.getLaunchIntentForPackage(osApp.packageName) ?: return@onEach
            val existing = existingApps.remove(osApp.packageName)
            val channels = notificationListenerConnection.getChannelsForApp(osApp.packageName)
            val name = pm.getApplicationLabel(osApp).toString()
            if (existing == null) {
                logger.d("adding ${osApp.packageName}")
                notificationAppDao.insertOrReplace(
                    NotificationAppItem(
                        packageName = osApp.packageName,
                        name = name,
                        muteState = MuteState.Never,
                        channelGroups = channels,
                        stateUpdated = timeProvider.now().asMillisecond(),
                        lastNotified = Instant.DISTANT_PAST.asMillisecond(),
                    )
                )
            } else if (existing.name != name || existing.channelGroups != channels) {
                // TODO will over-write channel mute state, until more logic
//                notificationAppDao.insertOrReplace(
//                    existing.copy(
//                        name = name,
//                        channelGroups = channels,
//                    )
//                )
            }
        }
        existingApps.values.forEach { app ->
            logger.d("deleting $app")
            notificationAppDao.markForDeletion(app.packageName)
        }
        logger.d("/syncAppsFromOS")
    }
}