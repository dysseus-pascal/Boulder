package io.rebble.libpebblecommon.blobdb

import io.rebble.libpebblecommon.database.dao.BlobDBDao
import io.rebble.libpebblecommon.packets.blobdb.BlobCommand
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.services.blobdb.BlobDBService
import kotlinx.coroutines.CoroutineScope
import kotlin.uuid.Uuid

class NotificationBlobDB(watchScope: CoroutineScope, blobDBService: BlobDBService, blobDBDao: BlobDBDao, watchIdentifier: String)
    : BlobDB(watchScope, blobDBService, WATCH_DB, blobDBDao, watchIdentifier)
{
    companion object {
        private val WATCH_DB = BlobCommand.BlobDatabase.Notification
    }

    /**
     * Send a notification to the watch by queuing it to be written to the BlobDB.
     */
    suspend fun insert(notification: TimelineItem) {
        blobDBDao.insert(notification.toBlobDBItem(watchIdentifier, WATCH_DB))
    }

    /**
     * Delete a notification from the watch by marking it as pending deletion in the BlobDB.
     */
    suspend fun delete(notificationId: Uuid) {
        blobDBDao.markPendingDelete(notificationId)
    }
}