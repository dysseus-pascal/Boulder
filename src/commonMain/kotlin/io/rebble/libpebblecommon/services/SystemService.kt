package io.rebble.libpebblecommon.services

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.PacketPriority
import io.rebble.libpebblecommon.ProtocolHandler
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.packets.*
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import io.rebble.libpebblecommon.protocolhelpers.ProtocolEndpoint
import io.rebble.libpebblecommon.structmapper.SInt
import io.rebble.libpebblecommon.structmapper.StructMapper
import io.rebble.libpebblecommon.util.DataBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach

/**
 * Singleton to handle sending notifications cleanly, as well as TODO: receiving/acting on action events
 */
class SystemService(private val protocolHandler: PebbleProtocolHandler) : ProtocolService {
//    val receivedMessages = Channel<SystemPacket>(Channel.BUFFERED)
    private val _appVersionRequest = CompletableDeferred<PhoneAppVersion.AppVersionRequest>()
    val appVersionRequest: Deferred<PhoneAppVersion.AppVersionRequest> = _appVersionRequest

    private var watchVersionCallback: CompletableDeferred<WatchVersion.WatchVersionResponse>? = null
    private var watchModelCallback: CompletableDeferred<UByteArray>? = null
    private var firmwareUpdateStartResponseCallback: CompletableDeferred<SystemMessage.FirmwareUpdateStartResponse>? = null
    private var pongCallback: CompletableDeferred<PingPong.Pong>? = null

    /**
     * Send an AppMessage
     */
    suspend fun send(packet: SystemPacket, priority: PacketPriority = PacketPriority.NORMAL) {
        protocolHandler.send(packet, priority)
    }

    suspend fun requestWatchVersion(): WatchVersion.WatchVersionResponse {
        val callback = CompletableDeferred<WatchVersion.WatchVersionResponse>()
        watchVersionCallback = callback

        send(WatchVersion.WatchVersionRequest())

        return callback.await()
    }

    suspend fun requestWatchModel(): Int {
        val callback = CompletableDeferred<UByteArray>()
        watchModelCallback = callback

        send(WatchFactoryData.WatchFactoryDataRequest("mfg_color"))

        val modelBytes = callback.await()

        return SInt(StructMapper()).also { it.fromBytes(DataBuffer(modelBytes)) }.get()
    }

    suspend fun sendPhoneVersionResponse() {
        // TODO put all this stuff in libpebble config
        send(PhoneAppVersion.AppVersionResponse(
            UInt.MAX_VALUE,
            0u,
            0u,
            2u,
            4u,
            4u,
            2u,
            ProtocolCapsFlag.makeFlags(
                buildList {
                    add(ProtocolCapsFlag.SupportsAppRunStateProtocol)
                    add(ProtocolCapsFlag.SupportsExtendedMusicProtocol)
                    add(ProtocolCapsFlag.SupportsTwoWayDismissal)
                    add(ProtocolCapsFlag.Supports8kAppMessage)
//                    if (platformContext.osType == PhoneAppVersion.OSType.Android) {
//                        add(ProtocolCapsFlag.SupportsAppDictation)
//                    }
                }
            )
        ))
    }

    suspend fun firmwareUpdateStart(bytesAlreadyTransferred: UInt, bytesToSend: UInt): UByte {
        val callback = CompletableDeferred<SystemMessage.FirmwareUpdateStartResponse>()
        firmwareUpdateStartResponseCallback = callback
        send(SystemMessage.FirmwareUpdateStart(bytesAlreadyTransferred, bytesToSend))
        val response = callback.await()
        return response.response.get()
    }

    suspend fun sendPing(cookie: UInt): UInt {
        // TODO can just read the inbound messages directly in these
        val pong = CompletableDeferred<PingPong.Pong>()
        pongCallback = pong
        send(PingPong.Ping(cookie))
        return pong.await().cookie.get()
    }

    fun init(scope: CoroutineScope) {
        scope.async {
            protocolHandler.inboundMessages.collect { packet ->
                when (packet) {
                    is WatchVersion.WatchVersionResponse -> {
                        watchVersionCallback?.complete(packet)
                        watchVersionCallback = null
                    }

                    is WatchFactoryData.WatchFactoryDataResponse -> {
                        watchModelCallback?.complete(packet.model.get())
                        watchModelCallback = null
                    }

                    is WatchFactoryData.WatchFactoryDataError -> {
                        watchModelCallback?.completeExceptionally(Exception("Failed to fetch watch model"))
                        watchModelCallback = null
                    }

                    is PhoneAppVersion.AppVersionRequest -> {
                        _appVersionRequest.complete(packet)
                    }

                    is SystemMessage.FirmwareUpdateStartResponse -> {
                        firmwareUpdateStartResponseCallback?.complete(packet)
                        firmwareUpdateStartResponseCallback = null
                    }

                    is PingPong.Pong -> {
                        pongCallback?.complete(packet)
                        pongCallback = null
                    }

//                    else -> receivedMessages.trySend(packet)
                }
            }
        }
    }

}
