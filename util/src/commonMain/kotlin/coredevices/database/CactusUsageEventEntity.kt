package coredevices.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Entity(tableName = "cactus_pebble_usage")
@Serializable
data class CactusUsageEventEntity(
    @PrimaryKey @SerialName("client_event_id") val clientEventId: String,
    @SerialName("install_id") val installId: String,
    @SerialName("device_type") val deviceType: String?,
    @SerialName("device_id") val deviceId: String?,
    @SerialName("event_type") val eventType: String,
    @SerialName("warmup") val warmup: Boolean,
    @SerialName("model_name") val modelName: String,
    @SerialName("success") val success: Boolean,
    @SerialName("failure_reason") val failureReason: String?,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("app_platform") val appPlatform: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("client_event_at") val clientEventAt: String,
)

@Dao
interface CactusUsageEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: CactusUsageEventEntity)

    @Query("SELECT * FROM cactus_pebble_usage ORDER BY clientEventAt ASC LIMIT :limit")
    suspend fun getBatch(limit: Int): List<CactusUsageEventEntity>

    @Query("DELETE FROM cactus_pebble_usage WHERE clientEventId IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM cactus_pebble_usage")
    suspend fun count(): Long

    @Query("DELETE FROM cactus_pebble_usage WHERE clientEventId IN (SELECT clientEventId FROM cactus_pebble_usage ORDER BY clientEventAt ASC LIMIT :count)")
    suspend fun deleteOldest(count: Long)
}
