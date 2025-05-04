package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.PacketPriority
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import kotlinx.coroutines.flow.SharedFlow

interface PebbleProtocolHandler {
    val inboundMessages: SharedFlow<PebblePacket>
    suspend fun send(message: PebblePacket, priority: PacketPriority = PacketPriority.NORMAL)
    suspend fun send(message: ByteArray, priority: PacketPriority = PacketPriority.NORMAL)
}

class RealPebbleProtocolHandler(
    private val pebbleProtocolStreams: PebbleProtocolStreams,
    transport: Transport,
) : PebbleProtocolHandler {
    private val logger = Logger.withTag("PebbleProtocol-${transport.identifier}")
    override val inboundMessages: SharedFlow<PebblePacket> =
        pebbleProtocolStreams.inboundMessagesFlow

    override suspend fun send(message: PebblePacket, priority: PacketPriority) {
        logger.d("sending $message")
        pebbleProtocolStreams.outboundPPBytes.send(message.serialize().asByteArray())
    }

    override suspend fun send(message: ByteArray, priority: PacketPriority) {
        logger.d("sending ${message.joinToString()}")
        pebbleProtocolStreams.outboundPPBytes.send(message)
    }
}
