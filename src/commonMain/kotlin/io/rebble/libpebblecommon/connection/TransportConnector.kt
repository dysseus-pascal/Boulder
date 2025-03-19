package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import com.oldguy.common.getUShortAt
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.peek
import io.ktor.utils.io.readByteArray
import io.rebble.libpebblecommon.PacketPriority
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

sealed class PebbleConnectionResult {
    class Success(
        val inboundPPBytes: ByteReadChannel,
        // Using ByteArray here because I'm not 100% sure how the watch handles multiple PP messages
        // within a single PPoG packet (we could make this [Byte] (or use source/sink) if that
        // works OK (for all knowns LE watches)).
        val outboundPPBytes: Channel<ByteArray>,
    ) : PebbleConnectionResult()

    class Failed(reason: String) : PebbleConnectionResult()
}

interface TransportConnector {
    suspend fun connect(pebbleDevice: PebbleDevice, scope: CoroutineScope): PebbleConnectionResult
}

interface PebbleProtocolHandler {
    val inboundMessages: SharedFlow<PebblePacket>
    suspend fun send(message: PebblePacket, priority: PacketPriority = PacketPriority.NORMAL)
}

class PebbleConnector(
    // TODO this handles selecting which transport to use
    private val bleConnector: TransportConnector,
) {
    fun connect(pebbleDevice: PebbleDevice, scope: CoroutineScope, publishState: (PebbleDevice) -> Unit) {
        val connectingDevice = RealConnectingPebbleDevice(pebbleDevice)
        publishState(connectingDevice)

        scope.async {

            val result = bleConnector.connect(pebbleDevice, scope)
            when (result) {
                is PebbleConnectionResult.Failed -> {
                    Logger.e("failed to connect: $result")
                    publishState(pebbleDevice)
                }

                is PebbleConnectionResult.Success -> {
                    Logger.d("PebbleConnector: $result")
                    val inboundMessagesFlow = MutableSharedFlow<PebblePacket>()
                    val protocolHandler = object : PebbleProtocolHandler {
                        override val inboundMessages: SharedFlow<PebblePacket> = inboundMessagesFlow

                        override suspend fun send(message: PebblePacket, priority: PacketPriority) {
                            Logger.d("sending $message")
                            result.outboundPPBytes.send(message.serialize().asByteArray())
                        }
                    }

                    val negotiatingDevice =
                        RealNegotiatingPebbleDevice(pebbleDevice, protocolHandler, scope)
                    publishState(negotiatingDevice)

                    scope.async {
                        try {
                            while (true) {
                                val sizeBytes = result.inboundPPBytes.peek(2)
                                val sizeArray = sizeBytes?.toByteArray()?.toUByteArray()
                                    ?: throw IllegalStateException("couldn't read size")
                                val payloadSize = sizeArray.getUShortAt(0, littleEndian = false)
                                val packetSize = payloadSize + PP_HEADER_SIZE
                                val packetBytes =
                                    result.inboundPPBytes.readByteArray(packetSize.toInt())
                                        .toUByteArray()
                                try {
                                    val packet = PebblePacket.deserialize(packetBytes)
                                    Logger.d("packet: $packet")
                                    inboundMessagesFlow.emit(packet)
                                } catch (e: Exception) {
                                    Logger.e("error decoding PP packet", e)
                                }
                            }
                        } catch (e: Exception) {
                            Logger.e("error decoding PP", e)
                        }
                    }

                    val handshakeResult = negotiatingDevice.negotiate()
                    Logger.d("handshakeResult = $handshakeResult")

                    val knownDevice = RealKnownPebbleDevice(
                        pebbleDevice = pebbleDevice,
                        isRunningRecoveryFw = handshakeResult.watchVersion.running.isRecovery.get(),
                    )

                    val connectedDevice = RealConnectedPebbleDevice(
                        pebbleDevice = knownDevice,
                        appRunStateService = negotiatingDevice.appRunStateService,
                        systemService = negotiatingDevice.systemService,
                    )
                    publishState(connectedDevice)
                }
            }
        }
    }

    private val PP_HEADER_SIZE: UShort = 4u
}