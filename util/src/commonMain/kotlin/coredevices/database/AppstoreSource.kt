package coredevices.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Entity
@Serializable
data class AppstoreSource(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val url: String,
    val title: String
)

@Dao
interface AppstoreSourceDao {
    @Insert
    suspend fun insertSource(source: AppstoreSource): Long

    @Query("SELECT * FROM AppstoreSource")
    fun getAllSources(): Flow<List<AppstoreSource>>

    @Query("DELETE FROM AppstoreSource WHERE id = :sourceId")
    suspend fun deleteSourceById(sourceId: Int)
}