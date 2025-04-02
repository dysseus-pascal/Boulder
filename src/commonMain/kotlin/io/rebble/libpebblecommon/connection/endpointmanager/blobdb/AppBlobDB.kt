package io.rebble.libpebblecommon.connection.endpointmanager.blobdb

import io.rebble.libpebblecommon.database.dao.BlobDBDao
import io.rebble.libpebblecommon.packets.blobdb.AppMetadata
import io.rebble.libpebblecommon.packets.blobdb.BlobCommand
import io.rebble.libpebblecommon.services.blobdb.BlobDBService
import kotlinx.coroutines.CoroutineScope
import kotlin.uuid.Uuid

class AppBlobDB(watchScope: CoroutineScope, blobDBService: BlobDBService, blobDBDao: BlobDBDao, watchIdentifier: String)
    : BlobDB(watchScope, blobDBService, WATCH_DB, blobDBDao, watchIdentifier)
{
    companion object {
        private val WATCH_DB = BlobCommand.BlobDatabase.App
    }

    /**
     * Write an app entry to the watch by queuing it to be written to the BlobDB.
     */
    suspend fun insertOrReplace(app: AppMetadata) {
        blobDBDao.insertOrReplace(app.toBlobDBItem(watchIdentifier, WATCH_DB))
    }

    /**
     * Delete an app from the watch by marking it as pending deletion in the BlobDB.
     */
    suspend fun delete(appId: Uuid) {
        blobDBDao.markPendingDelete(appId)
    }
}