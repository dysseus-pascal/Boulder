package io.rebble.libpebblecommon.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.database.entity.LockerEntryPlatform
import io.rebble.libpebblecommon.database.entity.LockerEntryWithPlatforms
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

@Dao
abstract class LockerEntryDao {
    @Insert
    abstract suspend fun insert(lockerEntry: LockerEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertOrReplace(lockerEntry: LockerEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertOrReplacePlatforms(platforms: List<LockerEntryPlatform>)

    @Transaction
    open suspend fun insertOrReplaceWithPlatforms(lockerEntry: LockerEntry, platforms: List<LockerEntryPlatform>) {
        insertOrReplace(lockerEntry)
        insertOrReplacePlatforms(platforms)
    }

    @Query("SELECT * FROM LockerEntry LIMIT :limit")
    @Transaction
    abstract suspend fun getAllWithPlatforms(limit: Int): List<LockerEntryWithPlatforms>

    @Query("SELECT * FROM LockerEntry")
    @Transaction
    abstract fun getAllWithPlatformsFlow(): Flow<List<LockerEntryWithPlatforms>>

    @Query("SELECT * FROM LockerEntry WHERE id = :id")
    abstract suspend fun get(id: Uuid): LockerEntry?
}