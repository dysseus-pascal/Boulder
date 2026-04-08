package coredevices.ring.database.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import coredevices.ring.data.entity.room.TraceEntryEntity

@Dao
interface TraceEntryDao {
    @Insert
    suspend fun insertTraceEntry(traceEntry: TraceEntryEntity): Long

    @Insert
    suspend fun insertAll(traceEntries: List<TraceEntryEntity>): List<Long>

    @Query("SELECT * FROM TraceEntryEntity WHERE sessionId = :sessionId ORDER BY timeMark ASC")
    suspend fun getEntriesForSession(sessionId: Long): List<TraceEntryEntity>

    @Query("SELECT * FROM TraceEntryEntity WHERE recordingId = :recordingId ORDER BY timeMark ASC")
    suspend fun getEntriesForRecording(recordingId: Long): List<TraceEntryEntity>

    @Query("SELECT * FROM TraceEntryEntity WHERE transferId = :transferId ORDER BY timeMark ASC")
    suspend fun getEntriesForTransfer(transferId: Long): List<TraceEntryEntity>
}