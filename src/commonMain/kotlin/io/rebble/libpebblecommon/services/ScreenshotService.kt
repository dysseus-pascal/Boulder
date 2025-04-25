package io.rebble.libpebblecommon.services

import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.packets.ScreenshotRequest

class ScreenshotService(private val protocolHandler: PebbleProtocolHandler) : ProtocolService {
    suspend fun send(packet: ScreenshotRequest) {
        protocolHandler.send(packet)
    }
}