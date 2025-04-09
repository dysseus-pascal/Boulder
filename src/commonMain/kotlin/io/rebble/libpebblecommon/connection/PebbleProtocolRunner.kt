package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import com.oldguy.common.getUShortAt
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.peek
import io.ktor.utils.io.readByteArray
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.io.IOException

class PebbleProtocolRunner {
    suspend fun run(
        inboundPPBytes: ByteReadChannel,
        inboundMessagesFlow: MutableSharedFlow<PebblePacket>,
    ) {
        try {
            while (true) {
                val sizeBytes = inboundPPBytes.peek(2)
                val sizeArray = sizeBytes?.toByteArray()?.toUByteArray()
                    ?: throw IOException("couldn't read size")
                val payloadSize = sizeArray.getUShortAt(0, littleEndian = false)
                val packetSize = payloadSize + PP_HEADER_SIZE
                val packetBytes =
                    inboundPPBytes.readByteArray(packetSize.toInt())
                        .toUByteArray()
                val packet = try {
                    PebblePacket.deserialize(packetBytes)
                } catch (e: Exception) {
//                                Logger.w("error deserializing packet: $packetBytes", e)
                    null
                }
                Logger.d("inbound pebble protocol packet: $packet")
                if (packet != null) {
                    inboundMessagesFlow.emit(packet)
                }
            }
        } catch (e: IOException) {
            Logger.e("error decoding PP", e)
        }
    }

    companion object {
        private val PP_HEADER_SIZE: UShort = 4u
    }
}