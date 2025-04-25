package io.rebble.libpebblecommon.services

import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.packets.MusicControl

class MusicService(private val protocolHandler: PebbleProtocolHandler) : ProtocolService {
    suspend fun send(packet: MusicControl) {
        protocolHandler.send(packet)
    }
}