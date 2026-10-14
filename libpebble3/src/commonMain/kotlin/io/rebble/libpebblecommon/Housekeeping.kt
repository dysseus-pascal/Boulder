package io.rebble.libpebblecommon

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.database.BlobDbDatabaseManager
import io.rebble.libpebblecommon.database.dao.HealthDao
import io.rebble.libpebblecommon.database.dao.NotificationDao
import io.rebble.libpebblecommon.database.dao.Spo2Dao
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.util.datenbereich
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class Housekeeping(
    libPebbleCoroutineScope: LibPebbleCoroutineScope,
    private val notificationsDao: NotificationDao,
    private val healthDao: HealthDao,
    private val notificationConfigFlow: NotificationConfigFlow,
    private val clock: Clock,
    private val blodDbDatabaseManager: BlobDbDatabaseManager,
    // Boulder: SpO2 verfaellt nach 90 Tagen wie die Minutendaten; die Naechte bleiben.
    private val spo2Dao: Spo2Dao,
) {
    private val logger = Logger.withTag("Housekeeping")

    // Boulder: ein Datenbankfehler beim Aufraeumen beendete vorher die App.
    private val bereich = libPebbleCoroutineScope.datenbereich("Housekeeping")

    fun init() {
        bereich.launch {
            try {
                blodDbDatabaseManager.deleteSyncRecordsForStaleDevices()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(e) { "Deleting stale sync records failed" }
            }
            while (true) {
                // Boulder: ein Fehler laesst nur diesen Durchgang ausfallen, nicht alle folgenden.
                try {
                    doHousekeeping()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.e(e) { "Housekeeping failed; next run in 6 h" }
                }
                delay(6.hours)
            }
        }
    }

    private suspend fun doHousekeeping() {
        val deleteNotificationsOlderThan = clock.now() - notificationConfigFlow.value.storeNotifiationsForDays.days
        notificationsDao.deleteOldNotifications(deleteNotificationsOlderThan.toEpochMilliseconds())

        val deleteHealthDataOlderThan = clock.now() - 90.days
        val deletedHealthRecords = healthDao.deleteExpiredHealthData(deleteHealthDataOlderThan.epochSeconds)
        val deletedOverlayRecords = healthDao.deleteExpiredOverlayData(deleteHealthDataOlderThan.epochSeconds)
        val deletedSpo2Records = spo2Dao.deleteExpiredSpo2Data(deleteHealthDataOlderThan.epochSeconds)
        if (deletedHealthRecords > 0 || deletedOverlayRecords > 0 || deletedSpo2Records > 0) {
            println("Housekeeping: Deleted $deletedHealthRecords health records, $deletedOverlayRecords overlay records and $deletedSpo2Records SpO2 readings older than 90 days")
        }
    }
}
