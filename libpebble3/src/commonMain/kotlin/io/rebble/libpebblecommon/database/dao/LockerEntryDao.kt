package io.rebble.libpebblecommon.database.dao

import androidx.room.Dao
import androidx.room.Query
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.database.entity.LockerEntryDao
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

@Dao
interface LockerEntryRealDao : LockerEntryDao {
    @Query("SELECT * FROM LockerEntryEntity")
    fun getAllFlow(): Flow<List<LockerEntry>>
}
