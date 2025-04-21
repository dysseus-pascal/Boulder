package io.rebble.libpebblecommon.connection.endpointmanager.blobdb

import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.Transport
import io.rebble.libpebblecommon.database.dao.BlobDBDao
import io.rebble.libpebblecommon.packets.blobdb.AppMetadata
import io.rebble.libpebblecommon.packets.blobdb.BlobCommand
import io.rebble.libpebblecommon.services.blobdb.BlobDBService
import kotlinx.coroutines.CoroutineScope
import kotlin.uuid.Uuid

class AppBlobDB(watchScope: CoroutineScope, blobDBService: BlobDBService, blobDBDao: BlobDBDao, transport: Transport)
    : BlobDB(watchScope, blobDBService, WATCH_DB, blobDBDao, transport), ConnectedPebble.Locker
{
    companion object {
        private val WATCH_DB = BlobCommand.BlobDatabase.App
    }

    /**
     * Write an app entry to the watch by queuing it to be written to the BlobDB.
     */
    override suspend fun insertLockerEntry(entry: AppMetadata) {
        blobDBDao.insertOrReplace(entry.toBlobDBItem(watchIdentifier, WATCH_DB))
    }

    /**
     * Delete an app from the watch by marking it as pending deletion in the BlobDB.
     */
    override suspend fun deleteLockerEntry(uuid: Uuid) {
        blobDBDao.markPendingDelete(uuid)
    }

    suspend fun get(appId: Uuid): ByteArray? {
        return blobDBDao.get(appId, watchIdentifier)?.data
    }

    override suspend fun offloadApp(uuid: Uuid) {
        blobDBDao.markPendingWrite(uuid, watchIdentifier)
    }

    override suspend fun isLockerEntryNew(entry: AppMetadata): Boolean {
        val existing = blobDBDao.get(entry.uuid.get(), watchIdentifier)?.data
        val nw = entry.toBytes().asByteArray()
        return existing?.contentEquals(nw) != true
    }
}