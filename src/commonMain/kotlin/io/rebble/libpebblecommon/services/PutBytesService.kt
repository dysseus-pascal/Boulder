package io.rebble.libpebblecommon.services

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.ProtocolHandler
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.metadata.pbw.manifest.PbwBlob
import io.rebble.libpebblecommon.packets.*
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import io.rebble.libpebblecommon.protocolhelpers.ProtocolEndpoint
import io.rebble.libpebblecommon.util.Crc32Calculator
import io.rebble.libpebblecommon.util.DataBuffer
import io.rebble.libpebblecommon.util.getPutBytesMaximumDataSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlin.math.log

class PutBytesService(private val protocolHandler: PebbleProtocolHandler) : ProtocolService {
    val receivedMessages = Channel<PutBytesResponse>(Channel.RENDEZVOUS)
    val progressUpdates = Channel<PutBytesProgress>(Channel.BUFFERED)

    private val logger = Logger.withTag("PutBytesService")

    data class PutBytesProgress(
        val count: Int,
        val total: Int,
        val delta: Int,
        val cookie: UInt
    )

    fun init(scope: CoroutineScope) {
        scope.async {
            protocolHandler.inboundMessages.collect {
                if (it is PutBytesResponse) {
                    receivedMessages.trySend(it)
                }
            }
        }
    }

    suspend fun send(packet: PutBytesOutgoingPacket) {
        if (packet is PutBytesAbort) {
            lastCookie = null
        }

        protocolHandler.send(packet)
    }

    var lastCookie: UInt? = null

    class PutBytesException(val cookie: UInt?, message: String, cause: Throwable? = null) : Error(message, cause)

    suspend fun initSession(size: UInt, type: ObjectType, bank: UByte, filename: String): PutBytesResponse {
        send(PutBytesInit(size, type, bank, filename))
        return awaitAck()
    }

    /**
     * Initializes a PutBytes session on the device for transferring 3.x+ app data
     */
    suspend fun initAppSession(appId: UInt, size: UInt, type: ObjectType): PutBytesResponse {
        send(PutBytesAppInit(size, type, appId))
        return awaitAck()
    }

    /**
     * Sends a chunk of data to the watch
     */
    suspend fun sendPut(cookie: UInt, data: UByteArray): PutBytesResponse {
        send(PutBytesPut(cookie, data))
        return awaitAck()
    }

    /**
     * Finalizes transfer, crc will be checked by watch and must be STM compatible e.g. [Crc32Calculator]
     */
    suspend fun sendCommit(cookie: UInt, crc: UInt): PutBytesResponse {
        send(PutBytesCommit(cookie, crc))
        return awaitAck()
    }

    suspend fun sendInstall(cookie: UInt): PutBytesResponse {
        send(PutBytesInstall(cookie))
        return awaitAck()
    }

    private suspend fun getResponse(): PutBytesResponse {
        return withTimeout(20_000) {
            val iterator = receivedMessages.iterator()
            if (!iterator.hasNext()) {
                throw IllegalStateException("Received messages channel is closed")
            }

            iterator.next()
        }
    }

    private suspend fun awaitAck(): PutBytesResponse {
        val response = getResponse()

        if (!response.isAck) {
            throw PutBytesException(lastCookie, "Watch responded with NACK (${response.result.get()}). Aborting transfer")
        }

        return response
    }

}