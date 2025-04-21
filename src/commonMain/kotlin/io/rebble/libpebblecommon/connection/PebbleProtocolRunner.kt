package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import com.oldguy.common.getUShortAt
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.peek
import io.ktor.utils.io.readByteArray
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.io.IOException

class PebbleProtocolStreams(
    val inboundPPBytes: ByteChannel = ByteChannel(),
    // Using ByteArray here because I'm not 100% sure how the watch handles multiple PP messages
    // within a single PPoG packet (we could make this [Byte] (or use source/sink) if that
    // works OK (for all knowns LE watches)).
    val outboundPPBytes: Channel<ByteArray> = Channel(capacity = 100),
    val inboundMessagesFlow: MutableSharedFlow<PebblePacket> = MutableSharedFlow(),
)

class PebbleProtocolRunner(
    private val pebbleProtocolStreams: PebbleProtocolStreams,
    private val transport: Transport,
) {
    private val logger = Logger.withTag("PebbleProtocolRunner-$transport")

    suspend fun run() {
        try {
            while (true) {
                val sizeBytes = pebbleProtocolStreams.inboundPPBytes.peek(2)
                val sizeArray = sizeBytes?.toByteArray()?.toUByteArray()
                    ?: throw IOException("couldn't read size")
                val payloadSize = sizeArray.getUShortAt(0, littleEndian = false)
                val packetSize = payloadSize + PP_HEADER_SIZE
                val packetBytes =
                    pebbleProtocolStreams.inboundPPBytes.readByteArray(packetSize.toInt())
                        .toUByteArray()
                val packet = try {
                    PebblePacket.deserialize(packetBytes)
                } catch (e: Exception) {
//                                Logger.w("error deserializing packet: $packetBytes", e)
                    null
                }
                logger.d("inbound pebble protocol packet: $packet")
                if (packet != null) {
                    pebbleProtocolStreams.inboundMessagesFlow.emit(packet)
                }
            }
        } catch (e: IOException) {
            logger.e("error decoding PP", e)
        }
    }

    companion object {
        private val PP_HEADER_SIZE: UShort = 4u
    }
}