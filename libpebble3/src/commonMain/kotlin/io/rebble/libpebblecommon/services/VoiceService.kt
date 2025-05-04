package io.rebble.libpebblecommon.services

import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.packets.OutgoingVoicePacket

class VoiceService(private val protocolHandler: PebbleProtocolHandler) : ProtocolService {
    suspend fun send(packet: OutgoingVoicePacket) {
        protocolHandler.send(packet)
    }
}