package io.rebble.libpebblecommon.services

import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.packets.LogDump

class LogDumpService(private val protocolHandler: PebbleProtocolHandler) : ProtocolService {
    suspend fun send(packet: LogDump) {
        protocolHandler.send(packet)
    }
}