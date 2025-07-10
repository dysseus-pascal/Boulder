package io.rebble.libpebblecommon.connection.endpointmanager

import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket

class DebugPebbleProtocolSender(
    private val pebbleProtocolHandler: PebbleProtocolHandler,
) : ConnectedPebble.Messages {
    override suspend fun sendPPMessage(bytes: ByteArray) {
        pebbleProtocolHandler.send(bytes)
    }

    override suspend fun sendPPMessage(ppMessage: PebblePacket) {
        pebbleProtocolHandler.send(ppMessage)
    }

    override val inboundMessages = pebbleProtocolHandler.inboundMessages
    override val rawInboundMessages = pebbleProtocolHandler.rawInboundMessages
}