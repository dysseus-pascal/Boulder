package io.rebble.libpebblecommon.services

import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.packets.AudioStream

class AudioStreamService(private val protocolHandler: PebbleProtocolHandler) : ProtocolService {
    suspend fun send(packet: AudioStream) {
        protocolHandler.send(packet)
    }
}