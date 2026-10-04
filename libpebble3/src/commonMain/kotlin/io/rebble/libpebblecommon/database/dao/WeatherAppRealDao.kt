package io.rebble.libpebblecommon.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import io.rebble.libpebblecommon.database.entity.WeatherAppEntry
import io.rebble.libpebblecommon.database.entity.WeatherAppEntryDao
import kotlin.uuid.Uuid

@Dao
interface WeatherAppRealDao : WeatherAppEntryDao {
    @Query("SELECT * FROM WeatherAppEntryEntity WHERE deleted = 0")
    suspend fun getAll(): List<WeatherAppEntry>

    // Boulder: Wegnehmen und Einfuegen am Stueck - dazwischen saehe die Uhr einen halben Stand.
    @Transaction
    suspend fun ersetzeOrte(behalten: Set<Uuid>, neue: List<WeatherAppEntry>) {
        getAll().filter { it.key !in behalten }.forEach { markForDeletion(it.key) }
        insertOrReplace(neue)
    }
}