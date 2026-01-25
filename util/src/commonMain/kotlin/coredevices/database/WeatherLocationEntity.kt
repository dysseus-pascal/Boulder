package coredevices.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Entity
@Serializable
data class WeatherLocationEntity(
    @PrimaryKey val key: Uuid,
    val orderIndex: Int,
    val name: String,
    val latitude: Double?,
    val longitude: Double?,
    val currentLocation: Boolean,
)

@Dao
interface WeatherLocationDao {
    @Upsert
    suspend fun upsert(location: WeatherLocationEntity)

    @Delete
    suspend fun delete(location: WeatherLocationEntity)

    @Query("UPDATE WeatherLocationEntity SET orderIndex = :newIndex WHERE `key` = :key")
    suspend fun updateOrder(key: Uuid, newIndex: Int)

    @Query("SELECT * FROM WeatherLocationEntity ORDER BY orderIndex ASC")
    fun getAllLocationsFlow(): Flow<List<WeatherLocationEntity>>

    @Query("SELECT * FROM WeatherLocationEntity ORDER BY orderIndex ASC")
    suspend fun getAllLocations(): List<WeatherLocationEntity>
}