package io.rebble.libpebblecommon.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.rebble.libpebblecommon.database.entity.BlobDBItem
import io.rebble.libpebblecommon.packets.blobdb.BlobCommand
import kotlinx.coroutines.flow.Flow

@Dao
interface BlobDBDao {
    @Insert
    suspend fun insert(blob: BlobDBItem)

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(blob: BlobDBItem)

    @Query("UPDATE BlobDBItem SET syncStatus = 'SyncedToWatch' WHERE id = :id AND watchIdentifier = :watchIdentifier")
    suspend fun markSynced(id: String, watchIdentifier: String)

    @Query("UPDATE BlobDBItem SET syncStatus = 'PendingWrite' WHERE id = :id AND watchIdentifier = :watchIdentifier")
    suspend fun markPendingWrite(id: String, watchIdentifier: String)

    @Query("UPDATE BlobDBItem SET syncStatus = 'PendingDelete' WHERE id = :id")
    suspend fun markPendingDelete(id: String)

    @Query("SELECT * FROM BlobDBItem WHERE watchDatabase = :watchDatabase AND watchIdentifier = :watchIdentifier")
    fun changesFor(watchDatabase: BlobCommand.BlobDatabase, watchIdentifier: String): Flow<List<BlobDBItem>>

    @Query("SELECT * FROM BlobDBItem WHERE watchDatabase = :watchDatabase AND syncStatus IN ('PendingWrite', 'PendingDelete') AND watchIdentifier = :watchIdentifier")
    suspend fun getAllPendingFor(watchDatabase: BlobCommand.BlobDatabase, watchIdentifier: String): List<BlobDBItem>

    @Query("SELECT * FROM BlobDBItem WHERE id = :id AND watchIdentifier = :watchIdentifier")
    suspend fun get(id: String, watchIdentifier: String): BlobDBItem?
}