package io.rebble.libpebblecommon.database

import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.BlobDbDaos

class BlobDbDatabaseManager(
    private val blobDatabases: BlobDbDaos,
) {
    suspend fun deleteSyncRecordsForStaleDevices() {
        blobDatabases.get().forEach { db ->
            db.deleteSyncRecordsForDevicesWhichDontExist()
        }
    }
}