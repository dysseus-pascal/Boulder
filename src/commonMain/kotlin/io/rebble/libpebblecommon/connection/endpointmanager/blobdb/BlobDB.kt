package io.rebble.libpebblecommon.connection.endpointmanager.blobdb

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.Transport
import io.rebble.libpebblecommon.database.dao.BlobDBDao
import io.rebble.libpebblecommon.database.entity.BlobDBItem
import io.rebble.libpebblecommon.database.entity.BlobDBItemSyncStatus
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.blobdb.BlobCommand
import io.rebble.libpebblecommon.packets.blobdb.BlobDB2Response
import io.rebble.libpebblecommon.packets.blobdb.BlobResponse
import io.rebble.libpebblecommon.services.blobdb.BlobDBService
import io.rebble.libpebblecommon.services.blobdb.DbWrite
import io.rebble.libpebblecommon.services.blobdb.WriteType
import io.rebble.libpebblecommon.structmapper.SString
import io.rebble.libpebblecommon.structmapper.SUUID
import io.rebble.libpebblecommon.structmapper.StructMapper
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.uuid.Uuid

sealed class BlobDB(
    watchScope: ConnectionCoroutineScope,
    private val blobDBService: BlobDBService,
    private val watchDatabase: BlobCommand.BlobDatabase,
    protected val blobDBDao: BlobDBDao,
    protected val transport: Transport,
) {
    protected val watchIdentifier: String = transport.identifier.asString

    companion object {
        private val BLOBDB_RESPONSE_TIMEOUT = 5000L
    }
    private val logger = Logger.withTag("BlobDB-$watchIdentifier")
    private val random = Random.Default

    init {
        watchScope.launch {
            blobDBDao.changesFor(watchDatabase, watchIdentifier).collect {
                if (it.isNotEmpty()) {
                    logger.d { "Responding to db change" }
                    syncPhoneToWatch(it)
                }
            }
        }
        watchScope.launch {
            blobDBService.writes.filter { it.database == watchDatabase }.collect {
                val result = handleWrite(it)
                val response = when (it.writeType) {
                    WriteType.Write -> BlobDB2Response.WriteResponse(it.token, result)
                    WriteType.WriteBack -> BlobDB2Response.WriteBackResponse(it.token, result)
                }
                blobDBService.sendResponse(response)
            }
        }
    }

    open suspend fun handleWrite(write: DbWrite): BlobResponse.BlobStatus =
        BlobResponse.BlobStatus.NotSupported

    abstract fun idAsBytes(id: String): UByteArray

    /**
     * Performs any pending synchronization operations stored in the phone database
     */
    private suspend fun syncPhoneToWatch(items: List<BlobDBItem>) {
        items.forEach { item ->
            //TODO: Resilience?
            when (item.syncStatus) {
                BlobDBItemSyncStatus.PendingWrite -> {
                    try {
                        sendInsert(idAsBytes(item.id), item.data.asUByteArray())
                        blobDBDao.markSynced(item.id, watchIdentifier)
                    } catch (e: Exception) {
                        logger.e(e) { "Failed to insert item" }
                    }
                }
                BlobDBItemSyncStatus.PendingDelete -> {
                    try {
                        sendDelete(idAsBytes(item.id))
                        blobDBDao.markSynced(item.id, watchIdentifier)
                    } catch (e: Exception) {
                        logger.e(e) { "Failed to delete item" }
                    }
                }
                BlobDBItemSyncStatus.SyncedToWatch -> {/* No-op */}
            }
        }
    }

    private fun generateToken(): UShort {
        return random.nextInt(0, UShort.MAX_VALUE.toInt()).toUShort()
    }

    private suspend fun sendWithTimeout(command: BlobCommand) = withTimeout(BLOBDB_RESPONSE_TIMEOUT) {
        blobDBService.send(command)
    }

    //TODO: Error handling, backoff, etc
    private fun handleBlobDBResponse(response: BlobResponse) = when (response.responseValue) {
        BlobResponse.BlobStatus.Success -> {
            logger.d { "BlobDB operation successful" }
        }
        else -> {
            //TODO: BlobDBException where fatal
            error("BlobDB operation failed: ${response.responseValue}")
        }
    }

    /**
     * Sends an insert command to the watch
     * It's recommended to not use this directly
     */
    suspend fun sendInsert(itemId: UByteArray, value: UByteArray) {
        val command = BlobCommand.InsertCommand(
            generateToken(),
            watchDatabase,
            itemId,
            value
        )
        val result = sendWithTimeout(command)
        handleBlobDBResponse(result)
    }

    /**
     * Sends a delete command to the watch
     * It's recommended to not use this directly
     */
    suspend fun sendDelete(itemId: UByteArray) {
        val command = BlobCommand.DeleteCommand(
            generateToken(),
            watchDatabase,
            itemId,
        )
        val result = sendWithTimeout(command)
        handleBlobDBResponse(result)
    }

    /**
     * Sends a clear command to the watch, clearing entire database
     * It's recommended to not use this directly
     */
    suspend fun sendClear() {
        val command = BlobCommand.ClearCommand(
            generateToken(),
            watchDatabase
        )
        val result = sendWithTimeout(command)
        handleBlobDBResponse(result)
    }
}