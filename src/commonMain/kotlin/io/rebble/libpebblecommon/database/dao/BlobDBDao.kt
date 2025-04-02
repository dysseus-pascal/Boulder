package io.rebble.libpebblecommon.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.rebble.libpebblecommon.database.entity.BlobDBItem
import io.rebble.libpebblecommon.packets.blobdb.BlobCommand
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

@Dao
interface BlobDBDao {
    @Insert
    suspend fun insert(blob: BlobDBItem)

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(blob: BlobDBItem)

    @Query("UPDATE BlobDBItem SET syncStatus = 'SyncedToWatch' WHERE id = :id")
    suspend fun markSynced(id: Uuid)

    @Query("UPDATE BlobDBItem SET syncStatus = 'PendingDelete' WHERE id = :id")
    suspend fun markPendingDelete(id: Uuid)

    @Query("SELECT * FROM BlobDBItem WHERE watchDatabase = :watchDatabase AND watchIdentifier = :watchIdentifier")
    fun changesFor(watchDatabase: BlobCommand.BlobDatabase, watchIdentifier: String): Flow<List<BlobDBItem>>

    @Query("SELECT * FROM BlobDBItem WHERE watchDatabase = :watchDatabase AND syncStatus IN ('PendingWrite', 'PendingDelete') AND watchIdentifier = :watchIdentifier")
    suspend fun getAllPendingFor(watchDatabase: BlobCommand.BlobDatabase, watchIdentifier: String): List<BlobDBItem>
}