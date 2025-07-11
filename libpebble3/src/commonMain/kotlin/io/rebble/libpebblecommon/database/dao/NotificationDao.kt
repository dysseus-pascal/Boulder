package io.rebble.libpebblecommon.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.rebble.libpebblecommon.database.entity.NotificationEntity
import kotlinx.coroutines.flow.Flow

data class ChannelAndCount(
    val channelId: String,
    val count: Int,
)

@Dao
interface NotificationDao {
    @Insert
    suspend fun insert(notificationEntity: NotificationEntity)

    @Query("DELETE FROM NotificationEntity WHERE timestamp < :beforeMs")
    suspend fun deleteOldNotifications(beforeMs: Long)

    @Query("SELECT channelId, COUNT(*) as count FROM NotificationEntity" +
            " WHERE pkg = :pkg GROUP BY channelId")
    fun channelNotificationCounts(pkg: String): Flow<List<ChannelAndCount>>
}
