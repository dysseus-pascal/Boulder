package io.rebble.libpebblecommon.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.database.asMillisecond
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.database.entity.NotificationAppItem
import io.rebble.libpebblecommon.database.entity.NotificationAppItemDao
import io.rebble.libpebblecommon.database.entity.NotificationAppItemSyncEntity
import io.rebble.libpebblecommon.database.entity.asNotificationAppItem
import io.rebble.libpebblecommon.packets.blobdb.BlobResponse
import io.rebble.libpebblecommon.services.blobdb.DbWrite
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Clock

@Dao
interface NotificationAppRealDao : NotificationAppItemDao {
    @Query("SELECT * FROM NotificationAppItemEntity WHERE deleted = 0 ORDER BY name ASC")
    suspend fun allApps(): List<NotificationAppItem>

    @Query("SELECT * FROM NotificationAppItemEntity WHERE deleted = 0 ORDER BY name ASC")
    fun allAppsFlow(): Flow<List<NotificationAppItem>>

    @Transaction
    suspend fun updateAppMuteState(packageName: String, muteState: MuteState) {
        val existing = getEntry(packageName)
        if (existing == null) {
            logger.e("updateAppMuteState: no record to update!")
            return
        }
        insertOrReplace(existing.copy(
            muteState = muteState,
            stateUpdated = Clock.System.now().asMillisecond(),
        ))
    }

    @Transaction
    override suspend fun handleWrite(write: DbWrite, transport: String): BlobResponse.BlobStatus {
        val writeItem = write.asNotificationAppItem()
        if (writeItem == null) {
            logger.e { "Couldn't decode app notification item from write: $write" }
            return BlobResponse.BlobStatus.InvalidData
        }
        val existingItem = getEntry(writeItem.packageName)
        logger.d { "existingItem=$existingItem writeItem=$writeItem" }
        if (existingItem == null || writeItem.stateUpdated.instant > existingItem.stateUpdated.instant) {
            insertOrReplace(writeItem)
            markSyncedToWatch(
                NotificationAppItemSyncEntity(
                    recordId = writeItem.packageName,
                    transport = transport,
                    watchSynchHashcode = writeItem.recordHashCode(),
                )
            )
        }
        return BlobResponse.BlobStatus.Success
    }

    companion object {
        private val logger = Logger.withTag("NotificationAppRealDao")
    }
}
