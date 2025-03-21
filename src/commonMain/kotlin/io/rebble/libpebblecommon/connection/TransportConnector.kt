package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import com.oldguy.common.getUShortAt
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.peek
import io.ktor.utils.io.readByteArray
import io.rebble.libpebblecommon.PacketPriority
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.io.IOException

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
    suspend fun connect(): PebbleConnectionResult
    suspend fun disconnect()
    val disconnected: Flow<Unit>
}

interface PebbleProtocolHandler {
    val inboundMessages: SharedFlow<PebblePacket>
    suspend fun send(message: PebblePacket, priority: PacketPriority = PacketPriority.NORMAL)
}

class PebbleConnector(
    private val transportConnector: TransportConnector,
    val pebbleDevice: PebbleDevice,
    val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(pebbleDevice)
    val state: StateFlow<PebbleDevice> = _state
    val disconnected = transportConnector.disconnected

    suspend fun connect() {
        val connectingDevice = RealConnectingPebbleDevice(pebbleDevice)
        _state.value = connectingDevice

        val result = transportConnector.connect()
        when (result) {
            is PebbleConnectionResult.Failed -> {
                Logger.e("failed to connect: $result")
                _state.value = pebbleDevice
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
                _state.value = negotiatingDevice

                scope.launch {
                    try {
                        while (true) {
                            val sizeBytes = result.inboundPPBytes.peek(2)
                            val sizeArray = sizeBytes?.toByteArray()?.toUByteArray()
                                ?: throw IOException("couldn't read size")
                            val payloadSize = sizeArray.getUShortAt(0, littleEndian = false)
                            val packetSize = payloadSize + PP_HEADER_SIZE
                            val packetBytes =
                                result.inboundPPBytes.readByteArray(packetSize.toInt())
                                    .toUByteArray()
                            val packet = try {
                                PebblePacket.deserialize(packetBytes)
                            } catch (e: Exception) {
                                Logger.w("error deserializing packet: $packetBytes", e)
                                null
                            }

                            Logger.d("packet: $packet")
                            if (packet != null) {
                                inboundMessagesFlow.emit(packet)
                            }
                        }
                    } catch (e: IOException) {
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
                    pebbleProtocol = protocolHandler,
                    scope = scope,
                    appRunStateService = negotiatingDevice.appRunStateService,
                    systemService = negotiatingDevice.systemService,
                )
                _state.value = connectedDevice
            }
        }
    }

    fun disconnect() {
        scope.launch {
            transportConnector.disconnect()
        }
    }

    private val PP_HEADER_SIZE: UShort = 4u
}