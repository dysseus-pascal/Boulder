package io.rebble.libpebblecommon.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.rebble.libpebblecommon.connection.KnownPebbleDevice
import io.rebble.libpebblecommon.connection.Transport
import io.rebble.libpebblecommon.database.entity.KnownWatchItem
import kotlinx.coroutines.flow.Flow

@Dao
interface KnownWatchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(watch: KnownWatchItem)

    @Query("SELECT * FROM KnownWatchItem")
    suspend fun knownWatches(): List<KnownWatchItem>

    @Query("DELETE FROM KnownWatchItem WHERE transportIdentifier = :transportIdentifier")
    suspend fun remove(transportIdentifier: String)

    suspend fun remove(transport: Transport) {
        remove(transport.identifier.asString)
    }
}