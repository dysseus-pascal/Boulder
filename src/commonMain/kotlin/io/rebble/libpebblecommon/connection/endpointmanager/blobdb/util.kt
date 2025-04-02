package io.rebble.libpebblecommon.connection.endpointmanager.blobdb

import io.rebble.libpebblecommon.database.entity.BlobDBItem
import io.rebble.libpebblecommon.database.entity.BlobDBItemSyncStatus
import io.rebble.libpebblecommon.packets.blobdb.AppMetadata
import io.rebble.libpebblecommon.packets.blobdb.BlobCommand
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem

fun TimelineItem.toBlobDBItem(
    watch: String,
    database: BlobCommand.BlobDatabase = BlobCommand.BlobDatabase.Pin,
    status: BlobDBItemSyncStatus = BlobDBItemSyncStatus.PendingWrite
) = BlobDBItem(
    id = itemId.get(),
    syncStatus = status,
    watchIdentifier = watch,
    watchDatabase = database,
    data = toBytes().asByteArray()
)

fun AppMetadata.toBlobDBItem(
    watch: String,
    database: BlobCommand.BlobDatabase = BlobCommand.BlobDatabase.App,
    status: BlobDBItemSyncStatus = BlobDBItemSyncStatus.PendingWrite
) = BlobDBItem(
    id = uuid.get(),
    syncStatus = status,
    watchIdentifier = watch,
    watchDatabase = database,
    data = toBytes().asByteArray()
)