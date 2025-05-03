package io.rebble.libpebblecommon.services

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.DataLoggingIncomingPacket
import io.rebble.libpebblecommon.packets.DataLoggingOutgoingPacket
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class DataLoggingService(
    private val protocolHandler: PebbleProtocolHandler,
    private val scope: ConnectionCoroutineScope,
) : ProtocolService {
    var acceptSessions = false
    companion object {
        private val logger = Logger.withTag(DataLoggingService::class.simpleName!!)
    }
    suspend fun send(packet: DataLoggingOutgoingPacket) {
        protocolHandler.send(packet)
    }

    private suspend fun sendAckNack(sessionId: UByte) {
        if (acceptSessions) {
            send(DataLoggingOutgoingPacket.ACK(sessionId))
        } else {
            send(DataLoggingOutgoingPacket.NACK(sessionId))
        }
    }

    fun init() {
        protocolHandler.inboundMessages.onEach {
            when (it) {
                is DataLoggingIncomingPacket.OpenSession -> {
                    val id = it.sessionId.get()
                    logger.d { "Session opened: $id (accepted: $acceptSessions)" }
                    sendAckNack(id)
                }
                is DataLoggingIncomingPacket.SendDataItems -> {
                    val id = it.sessionId.get()
                    //logger.d { "Data items received for session: $id" }
                    sendAckNack(id)
                }
                is DataLoggingIncomingPacket.CloseSession -> {
                    val id = it.sessionId.get()
                    logger.d { "Session closed: $id" }
                    sendAckNack(id)
                }
            }
        }.launchIn(scope)
    }
}