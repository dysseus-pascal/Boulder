package io.rebble.libpebblecommon.blobdb

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.packets.blobdb.BlobCommand
import io.rebble.libpebblecommon.packets.blobdb.BlobResponse
import io.rebble.libpebblecommon.services.blobdb.BlobDBService
import io.rebble.libpebblecommon.structmapper.SUUID
import io.rebble.libpebblecommon.structmapper.StructMapper
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
sealed class BlobDB(
    private val blobDBService: BlobDBService,
    private val watchDatabase: BlobCommand.BlobDatabase,
    private val watchIdentifier: String
) {
    companion object {
        private val BLOBDB_RESPONSE_TIMEOUT = 5000L
    }
    private val logger = Logger.withTag("BlobDB-$watchIdentifier")
    private val random = Random.Default

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

    //CRUD operations
    suspend fun insert(itemId: Uuid, value: UByteArray) {
        val command = BlobCommand.InsertCommand(
            generateToken(),
            watchDatabase,
            SUUID(StructMapper(), itemId).toBytes(),
            value
        )
        val result = sendWithTimeout(command)
        handleBlobDBResponse(result)
    }

    suspend fun read(itemId: Uuid): UByteArray {
        TODO("Not yet implemented")
    }

    suspend fun delete(itemId: Uuid) {
        val command = BlobCommand.DeleteCommand(
            generateToken(),
            watchDatabase,
            SUUID(StructMapper(), itemId).toBytes()
        )
        val result = sendWithTimeout(command)
        handleBlobDBResponse(result)
    }

    suspend fun clear() {
        val command = BlobCommand.ClearCommand(
            generateToken(),
            watchDatabase
        )
        val result = sendWithTimeout(command)
        handleBlobDBResponse(result)
    }
}