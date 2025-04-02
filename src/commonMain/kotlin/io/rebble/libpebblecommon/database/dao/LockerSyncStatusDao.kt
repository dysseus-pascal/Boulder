package io.rebble.libpebblecommon.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.rebble.libpebblecommon.database.entity.LockerSyncStatus

@Dao
interface LockerSyncStatusDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(lockerSyncStatus: LockerSyncStatus)

    @Query("SELECT * FROM LockerSyncStatus WHERE watchIdentifier = :watchIdentifier")
    suspend fun getForWatchIdentifier(watchIdentifier: String): LockerSyncStatus?
}
