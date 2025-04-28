package io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification

import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
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

    override suspend fun syncAppsFromOS() {
        logger.d("syncAppsFromOS")
        val pm = context.context.packageManager
        val existingApps = notificationAppDao.allApps().associateBy { it.packageName }.toMutableMap()
        val osApps = withContext(Dispatchers.IO) { pm.getInstalledApplications(0) }
        osApps.onEach { osApp ->
            if (existingApps.remove(osApp.packageName) == null) {
                logger.d("adding ${osApp.packageName}")
                notificationAppDao.insertOrIgnore(NotificationAppEntity(
                    packageName = osApp.packageName,
                    name = pm.getApplicationLabel(osApp).toString(),
                    muteState = MuteState.Never,
                    channelGroups = emptyList(),
                    stateUpdated = clock.now(),
                    lastNotified = Instant.DISTANT_PAST,
                ))
            }
        }
        existingApps.values.forEach { app ->
            logger.d("deleting $app")
            notificationAppDao.delete(app)
        }

        // TODO sync channels from notificationListenerConnection

        logger.d("/syncAppsFromOS")
    }
}