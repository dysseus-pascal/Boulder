package io.rebble.libpebblecommon.database

import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.BlobDbDaos

interface BlobDbDatabaseManager {
    suspend fun deleteSyncRecordsForStaleDevices()
}

class RealBlobDbDatabaseManager(
    private val blobDatabases: BlobDbDaos,
) : BlobDbDatabaseManager {
    override suspend fun deleteSyncRecordsForStaleDevices() {
        blobDatabases.get().forEach { db ->
            db.deleteSyncRecordsForDevicesWhichDontExist()
        }
    }
}