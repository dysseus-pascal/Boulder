package io.rebble.libpebblecommon.blobdb

import io.rebble.libpebblecommon.packets.blobdb.BlobCommand
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.services.blobdb.BlobDBService
import kotlin.uuid.ExperimentalUuidApi

class NotificationBlobDB(blobDBService: BlobDBService, watchIdentifier: String)
    : BlobDB(blobDBService, BlobCommand.BlobDatabase.Notification, watchIdentifier)
{
    @OptIn(ExperimentalUuidApi::class)
    suspend fun insert(notification: TimelineItem) {
        insert(notification.itemId.get(), notification.toBytes())
    }
}