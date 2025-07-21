package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.core.writeFully
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.send
import io.rebble.libpebblecommon.structmapper.SByte
import io.rebble.libpebblecommon.structmapper.SFixedString
import io.rebble.libpebblecommon.structmapper.SUByte
import io.rebble.libpebblecommon.structmapper.SUInt
import io.rebble.libpebblecommon.structmapper.StructMappable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

internal expect fun getTempPbwPath(): Path

class DevConnectionServer(private val libPebble: LibPebble) {
    companion object {
        private const val PORT = 9000
        private val logger = Logger.withTag("DevConnectionServer")
    }
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val _activeDevice = MutableStateFlow<Transport?>(null)
    val activeDevice: StateFlow<Transport?> = _activeDevice.asStateFlow()
    suspend fun startForDevice(transport: Transport) {
        server?.stopSuspend()
        logger.i { "Starting server for ${transport.name} on port $PORT" }
        _activeDevice.value = transport
        server = embeddedServer(CIO, configure = {
            connectors.add(EngineConnectorBuilder().apply {
                host = "0.0.0.0"
                port = PORT
            })
            connectionGroupSize = 1
            workerGroupSize = 2
            callGroupSize = 2
        }) {
            install(WebSockets)
            routing {
                webSocket("/") {
                    logger.i { "WebSocket connection established for ${transport.name}" }
                    launch {
                        val reason = this@webSocket.closeReason.await()
                        logger.i { "WebSocket connection closed for ${transport.name}: (${reason?.code}) ${reason?.message ?: "No reason provided"}" }
                    }
                    libPebble.watches.forDevice(transport).debounce(500).collectLatest { device ->
                        when (device) {
                            is ConnectedPebble.Messages -> {
                                logger.d { "Device '${device.name}' connected, updating client + forwarding messages" }
                                send(ConnectionStatusUpdateMessage(true))
                                device.rawInboundMessages.onEach {
                                    send(byteArrayOf(ServerMessageType.RelayFromWatch.value) + it)
                                }.launchIn(this)
                                if (device is ConnectedPebble.PKJS) {
                                    device.currentPKJSSession.flatMapLatest {
                                        it?.logMessages ?: emptyFlow()
                                    }.onEach {
                                        send(PhoneAppLogMessage(it))
                                    }.launchIn(this)
                                }
                                delay(10) //XXX: Give the client a moment to set up the connection
                                for (frame in incoming) {
                                    when (frame) {
                                        is Frame.Binary -> {
                                            val data = frame.data
                                            if (data.isEmpty()) {
                                                logger.w { "Received empty binary frame" }
                                                continue
                                            }
                                            val messageType = ClientMessageType.fromValue(data[0])
                                            val payload = data.copyOfRange(1, data.size)
                                            when (messageType) {
                                                ClientMessageType.RelayToWatch -> {
                                                    logger.d { "Relaying message to watch" }
                                                    device.sendPPMessage(payload)
                                                }
                                                ClientMessageType.InstallBundle -> {
                                                    val path = getTempPbwPath()
                                                    logger.d { "Received InstallBundle message with payload size ${payload.size}, saving to $path" }
                                                    try {
                                                        withContext(Dispatchers.IO) {
                                                            SystemFileSystem.sink(path).buffered().use {
                                                                it.writeFully(payload)
                                                            }
                                                        }
                                                        send(InstallStatusMessage(libPebble.sideloadApp(path)))
                                                    } catch (e: Exception) {
                                                        logger.e(e) { "Failed to save/install bundle: ${e.message}" }
                                                        send(InstallStatusMessage(false))
                                                    }
                                                }
                                                // Handle other message types as needed
                                                ClientMessageType.TimelinePin -> {
                                                    logger.d { "Received TimelinePin message with payload size ${payload.size}" }
                                                    val message = "Mobile app currently doesn't support operation."
                                                    send(PhoneAppLogMessage(message))
                                                    close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, message))
                                                }
                                                null -> {
                                                    logger.w { "Received unsupported or unknown message type: ${data[0]}" }
                                                    val message = "Unknown operation."
                                                    send(PhoneAppLogMessage(message))
                                                    close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, message))
                                                }
                                            }
                                        }
                                        else -> {
                                            logger.w { "Received unsupported frame type: ${frame.frameType}" }
                                        }
                                    }
                                }
                            }
                            else -> {
                                logger.d { "Device '${device.name}' disconnected/unsupported, updating client" }
                                send(ConnectionStatusUpdateMessage(false))
                            }
                        }
                    }
                }
            }
        }.startSuspend()
    }

    suspend fun stop() {
        server?.stopSuspend()
        _activeDevice.value = null
        server = null
        logger.i { "Server stopped" }
    }
}

private suspend fun WebSocketSession.send(message: StructMappable) =
    send(message.toBytes().asByteArray())

private class ConnectionStatusUpdateMessage(
    connected: Boolean
): StructMappable() {
    val type = SByte(m, ServerMessageType.ConnectionStatusUpdate.value)
    val connected = SUByte(m, if (connected) 0xFFu else 0u)
}

private class PhoneAppLogMessage(
    message: String
): StructMappable() {
    val type = SByte(m, ServerMessageType.PhoneAppLog.value)
    val message = SFixedString(m, message.length, message)
}

private class InstallStatusMessage(
    success: Boolean
): StructMappable() {
    val type = SByte(m, ServerMessageType.InstallStatus.value)
    val status = SUInt(m, if (success) 0u else 1u)
}

private enum class ClientMessageType(val value: Byte) {
    RelayToWatch(0x01),
    InstallBundle(0x04),
    //PhoneInfo(0x06),
    //ProxyAuthenticationRequest(0x09),
    //PhonesimAppConfig(0x0A),
    //RelayQemu(0x0B),
    TimelinePin(0x0C);

    companion object {
        fun fromValue(value: Byte): ClientMessageType? {
            return entries.find { it.value == value }
        }
    }
}

private enum class ServerMessageType(val value: Byte) {
    RelayFromWatch(0x00),
    RelayToWatch(0x01),
    PhoneAppLog(0x02),
    PhoneServerLog(0x03),
    InstallStatus(0x05),
    PhoneInfo(0x06),
    ConnectionStatusUpdate(0x07),
    ProxyConnectionStatusUpdate(0x08),
    ProxyAuthenticationResponse(0x09),
    PhonesimAppConfigResponse(0x0A),
    TimelinePinResponse(0x0C),
}