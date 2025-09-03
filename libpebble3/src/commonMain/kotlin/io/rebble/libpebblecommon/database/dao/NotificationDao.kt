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

    @Query("SELECT * FROM NotificationEntity WHERE (:pkg is NULL OR pkg = :pkg) AND (:channelId IS NULL OR channelId = :channelId) AND (:contactId IS NULL OR people LIKE '%' || :contactId || '%') ORDER BY timestamp DESC LIMIT :limit")
    fun mostRecentNotificationsFor(pkg: String?, channelId: String?, contactId: String?, limit: Int): Flow<List<NotificationEntity>>
}
