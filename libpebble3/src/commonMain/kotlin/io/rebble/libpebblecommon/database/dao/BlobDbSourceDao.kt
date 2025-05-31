package io.rebble.libpebblecommon.database.dao

import androidx.room.Upsert
import coredev.BlobDatabase
import io.rebble.libpebblecommon.connection.Transport
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.packets.blobdb.BlobResponse
import io.rebble.libpebblecommon.services.blobdb.DbWrite
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

interface BlobDbDao<T : BlobDbRecord> {
    // Compiler will choke on these methods unless they are overridden in each Dao
    fun dirtyRecordsForWatchInsert(transport: String, timestampMs: Long, insertOnlyAfterMs: Long = -1): Flow<List<T>>
    fun dirtyRecordsForWatchDelete(transport: String, timestampMs: Long): Flow<List<T>>
    suspend fun markSyncedToWatch(
        transport: String,
        item: T,
        hashcode: Int,
    )
    suspend fun markDeletedFromWatch(
        transport: String,
        item: T,
        hashcode: Int,
    )
    fun databaseId(): BlobDatabase
    @Upsert
    suspend fun insertOrReplace(item: T)
    @Upsert
    suspend fun insertOrReplaceAll(items: List<T>)
    suspend fun markAllDeletedFromWatch(transport: String)
    suspend fun handleWrite(write: DbWrite, transport: String): BlobResponse.BlobStatus = BlobResponse.BlobStatus.Success
}

interface BlobDbRecord {
    val recordHashcode: Int
    val deleted: Boolean
    val record: BlobDbItem
}

interface BlobDbItem {
    fun key(): UByteArray
    fun value(platform: WatchType): UByteArray?
    fun recordHashCode(): Int
}
