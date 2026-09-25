package io.rebble.libpebblecommon.services

import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.connection.endpointmanager.phonecontrol.CallAction
import io.rebble.libpebblecommon.packets.PhoneControl
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.mapNotNull

class PhoneControlService(private val protocolHandler: PebbleProtocolHandler) : ProtocolService {
    val callActions = protocolHandler.inboundMessages
        .filterIsInstance<PhoneControl>()
        .mapNotNull {
            when (it) {
                is PhoneControl.Answer -> CallAction.Answer
                is PhoneControl.Hangup -> CallAction.Hangup
                else -> null
            }
        }

    suspend fun send(packet: PhoneControl) {
        protocolHandler.send(packet)
    }
}