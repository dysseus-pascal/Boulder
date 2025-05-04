package io.rebble.libpebblecommon.database.entity

enum class BlobDBItemSyncStatus {
    PendingWrite,
    PendingDelete,
    SyncedToWatch
}