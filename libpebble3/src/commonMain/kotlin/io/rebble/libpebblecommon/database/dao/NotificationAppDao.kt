package io.rebble.libpebblecommon.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.database.entity.LockerSyncStatus
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.database.entity.NotificationAppEntity
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

@Dao
interface NotificationAppDao {
    @Query("SELECT * FROM NotificationAppEntity")
    suspend fun allApps(): List<NotificationAppEntity>

    @Query("SELECT * FROM NotificationAppEntity ORDER BY name ASC")
    fun allAppsFlow(): Flow<List<NotificationAppEntity>>

    @Query("SELECT * FROM NotificationAppEntity WHERE packageName=:packageName")
    suspend fun getApp(packageName: String): NotificationAppEntity?

    @Query("UPDATE NotificationAppEntity SET muteState=:muteState WHERE packageName=:packageName")
    suspend fun updateAppMuteState(packageName: String, muteState: MuteState)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(item: NotificationAppEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(item: NotificationAppEntity)

    @Delete
    suspend fun delete(item: NotificationAppEntity)

    @Transaction
    suspend fun updateFromWatch(
        packageName: String,
        appName: String,
        mutedState: MuteState,
        lastUpdated: Instant
    ) {
        val app = getApp(packageName)
        if (app == null) {
            insertOrReplace(
                NotificationAppEntity(
                    packageName = packageName,
                    name = appName,
                    muteState = mutedState,
                    channelGroups = emptyList(),
                    stateUpdated = lastUpdated,
                    lastNotified = lastUpdated,
                )
            )
            return
        }
        if (lastUpdated > app.stateUpdated) {
            insertOrReplace(app.copy(muteState = mutedState))
        }
    }
}
