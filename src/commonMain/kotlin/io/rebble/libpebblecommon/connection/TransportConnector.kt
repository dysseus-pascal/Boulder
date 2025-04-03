package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import com.oldguy.common.getUShortAt
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.peek
import io.ktor.utils.io.readByteArray
import io.rebble.libpebblecommon.PacketPriority
import io.rebble.libpebblecommon.connection.ConnectingPebbleState.Connected
import io.rebble.libpebblecommon.connection.ConnectingPebbleState.Connecting
import io.rebble.libpebblecommon.connection.ConnectingPebbleState.Failed
import io.rebble.libpebblecommon.connection.ConnectingPebbleState.Inactive
import io.rebble.libpebblecommon.connection.ConnectingPebbleState.Negotiating
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import io.rebble.libpebblecommon.services.SystemService
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.services.app.AppRunStateService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlin.time.Duration.Companion.seconds

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

sealed class ConnectingPebbleState {
    abstract val transport: Transport

    data class Inactive(override val transport: Transport) : ConnectingPebbleState()
    data class Connecting(override val transport: Transport) : ConnectingPebbleState()
    data class Failed(override val transport: Transport) : ConnectingPebbleState()
    data class Negotiating(override val transport: Transport) : ConnectingPebbleState()
    data class Connected(
        override val transport: Transport,
        val pebbleProtocol: PebbleProtocolHandler,
        val scope: CoroutineScope,
        val watchInfo: WatchInfo,
        val appRunStateService: AppRunStateService,
        val systemService: SystemService,
    ) : ConnectingPebbleState()
}

class PebbleConnector(
    private val transportConnector: TransportConnector,
    private val database: Database,
    val transport: Transport,
    val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ConnectingPebbleState>(Inactive(transport))
    val state: StateFlow<ConnectingPebbleState> = _state.asStateFlow()
    val disconnected = transportConnector.disconnected

    suspend fun connect() {
        _state.value = Connecting(transport)

        val result = transportConnector.connect()
        when (result) {
            is PebbleConnectionResult.Failed -> {
                Logger.e("failed to connect: $result")
                transportConnector.disconnect()
                _state.value = Failed(transport)
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

                _state.value = Negotiating(transport)

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

                val systemService = SystemService(protocolHandler).apply { init(scope) }
                val appRunStateService = AppRunStateService(protocolHandler).apply { init(scope) }

                Logger.d("RealNegotiatingPebbleDevice negotiate()")
                try {
                    withTimeout(15.seconds) {
                        val appVersionRequest = systemService.appVersionRequest.await()
                        Logger.d("RealNegotiatingPebbleDevice appVersionRequest = $appVersionRequest")
                        systemService.sendPhoneVersionResponse()
                        Logger.d("RealNegotiatingPebbleDevice sent watch version request")
                        val watchInfo = systemService.requestWatchVersion()
                        Logger.d("RealNegotiatingPebbleDevice watchVersionResponse = $watchInfo")
                        val runningApp = appRunStateService.runningApp.first()
                        Logger.d("RealNegotiatingPebbleDevice runningApp = $runningApp")

                        _state.value = Connected(
                            transport = transport,
                            pebbleProtocol = protocolHandler,
                            scope = scope,
                            watchInfo = watchInfo,
                            appRunStateService = appRunStateService,
                            systemService = systemService,
                        )
                    }
                } catch (e: TimeoutCancellationException) {
                    Logger.w("negotiation timed out")
                    transportConnector.disconnect()
                    _state.value = Failed(transport)
                }
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