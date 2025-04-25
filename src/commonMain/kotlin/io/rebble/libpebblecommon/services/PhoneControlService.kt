package io.rebble.libpebblecommon.services

import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.packets.PhoneControl

class PhoneControlService(private val protocolHandler: PebbleProtocolHandler) : ProtocolService {
    suspend fun send(packet: PhoneControl) {
        protocolHandler.send(packet)
    }
}