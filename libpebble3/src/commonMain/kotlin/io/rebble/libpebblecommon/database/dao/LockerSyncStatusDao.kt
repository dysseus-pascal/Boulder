package io.rebble.libpebblecommon.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.rebble.libpebblecommon.database.entity.LockerSyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface LockerSyncStatusDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(lockerSyncStatus: LockerSyncStatus)

    @Query("SELECT * FROM LockerSyncStatus WHERE watchIdentifier = :watchIdentifier")
    suspend fun getForWatchIdentifier(watchIdentifier: String): LockerSyncStatus?

    @Query("UPDATE LockerSyncStatus SET lockerDirty = 1")
    suspend fun markAllDirty()

    @Query("UPDATE LockerSyncStatus SET lockerDirty = 0 WHERE watchIdentifier = :watchIdentifier")
    suspend fun markNotDirty(watchIdentifier: String)

    @Query("SELECT * FROM LockerSyncStatus")
    fun getChanges(): Flow<List<LockerSyncStatus>>
}
