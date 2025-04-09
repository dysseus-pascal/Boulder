@file:OptIn(ExperimentalTime::class)

package io.rebble.libpebblecommon.services

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.PacketPriority
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.packets.PhoneAppVersion
import io.rebble.libpebblecommon.packets.PingPong
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import io.rebble.libpebblecommon.packets.SystemMessage
import io.rebble.libpebblecommon.packets.SystemPacket
import io.rebble.libpebblecommon.packets.WatchFactoryData
import io.rebble.libpebblecommon.packets.WatchFirmwareVersion
import io.rebble.libpebblecommon.packets.WatchVersion
import io.rebble.libpebblecommon.packets.WatchVersion.WatchVersionResponse
import io.rebble.libpebblecommon.structmapper.SInt
import io.rebble.libpebblecommon.structmapper.StructMapper
import io.rebble.libpebblecommon.util.DataBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Singleton to handle sending notifications cleanly, as well as TODO: receiving/acting on action events
 */
class SystemService(private val protocolHandler: PebbleProtocolHandler) : ProtocolService, ConnectedPebble.Debug {
    //    val receivedMessages = Channel<SystemPacket>(Channel.BUFFERED)
    private val _appVersionRequest = CompletableDeferred<PhoneAppVersion.AppVersionRequest>()
    val appVersionRequest: Deferred<PhoneAppVersion.AppVersionRequest> = _appVersionRequest

    private var watchVersionCallback: CompletableDeferred<WatchVersionResponse>? = null
    private var watchModelCallback: CompletableDeferred<UByteArray>? = null
    private var firmwareUpdateStartResponseCallback: CompletableDeferred<SystemMessage.FirmwareUpdateStartResponse>? =
        null
    private var pongCallback: CompletableDeferred<PingPong.Pong>? = null

    /**
     * Send an AppMessage
     */
    suspend fun send(packet: SystemPacket, priority: PacketPriority = PacketPriority.NORMAL) {
        protocolHandler.send(packet, priority)
    }

    suspend fun requestWatchVersion(): WatchInfo {
        val callback = CompletableDeferred<WatchVersionResponse>()
        watchVersionCallback = callback

        send(WatchVersion.WatchVersionRequest())

        return callback.await().also {
            val fwVersion = it.running.firmwareVersion()
            Logger.d("fwVersion = $fwVersion")
        }.watchInfo()
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
        send(
            PhoneAppVersion.AppVersionResponse(
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

    suspend fun sendFirmwareUpdateStart(bytesAlreadyTransferred: UInt, bytesToSend: UInt): SystemMessage.FirmwareUpdateStartStatus {
        val callback = CompletableDeferred<SystemMessage.FirmwareUpdateStartResponse>()
        firmwareUpdateStartResponseCallback = callback
        send(SystemMessage.FirmwareUpdateStart(bytesAlreadyTransferred, bytesToSend))
        val response = callback.await()
        return SystemMessage.FirmwareUpdateStartStatus.fromValue(response.response.get())
    }

    suspend fun sendFirmwareUpdateComplete() {
        send(SystemMessage.FirmwareUpdateComplete())
    }

    override suspend fun sendPing(cookie: UInt): UInt {
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
                    is WatchVersionResponse -> {
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

private val FIRMWARE_VERSION_REGEX = Regex("v?([0-9]+)\\.([0-9]+)\\.([0-9]+)(?:-(.*))?")

data class FirmwareVersion(
    val stringVersion: String,
    val timestamp: Instant,
    val major: Int,
    val minor: Int,
    val patch: Int,
    val suffix: String?,
    val gitHash: String,
    val isRecovery: Boolean,
//    val hardwarePlatform: ?
//    val metadataVersion: ?
)

fun WatchFirmwareVersion.firmwareVersion(): FirmwareVersion? {
    val tag = versionTag.get()
    val match = FIRMWARE_VERSION_REGEX.find(tag)
    if (match == null) {
        Logger.w("Couldn't recode fw version: '$tag'")
        return null
    }
    val major = match.groupValues.get(1).toInt()
    val minor = match.groupValues.get(2).toInt()
    val patch = match.groupValues.get(3).toInt()
    val suffix = match.groupValues.get(4) // TODO empty or null-and-crash?

    return FirmwareVersion(
        stringVersion = tag,
        timestamp = Instant.fromEpochSeconds(timestamp.get().toLong()),
        major = major,
        minor = minor,
        patch = patch,
        suffix = suffix,
        gitHash = gitHash.get(),
        isRecovery = isRecovery.get(),
    )
}

data class WatchInfo(
    val runningFwVersion: FirmwareVersion,
    val recoveryFwVersion: FirmwareVersion?,
    val platform: WatchHardwarePlatform?,
    val bootloaderTimestamp: Instant,
    val board: String,
    val serial: String,
    val btAddress: String,
    val resourceCrc: Long,
    val resourceTimestamp: Instant,
    val language: String,
    val languageVersion: Int,
    val capabilities: Set<ProtocolCapsFlag>,
    val isUnfaithful: Boolean?,
    val healthInsightsVersion: Int?,
    val javascriptVersion: Int?,
)

fun WatchVersionResponse.watchInfo(): WatchInfo {
    val runningFwVersion = running.firmwareVersion()
    checkNotNull(runningFwVersion)
    val recoveryFwVersion = recovery.firmwareVersion()
    return WatchInfo(
        runningFwVersion = runningFwVersion,
        recoveryFwVersion = recoveryFwVersion,
        platform = WatchHardwarePlatform.fromProtocolNumber(running.hardwarePlatform.get()),
        bootloaderTimestamp = Instant.fromEpochSeconds(bootloaderTimestamp.get().toLong()),
        board = board.get(),
        serial = serial.get(),
        btAddress = btAddress.get().toByteArray().toMacAddressString(),
        resourceCrc = resourceCrc.get().toLong(),
        resourceTimestamp = Instant.fromEpochSeconds(resourceTimestamp.get().toLong()),
        language = language.get(),
        languageVersion = languageVersion.get().toInt(),
        capabilities = ProtocolCapsFlag.fromFlags(capabilities.get()),
        isUnfaithful = isUnfaithful.get(),
        healthInsightsVersion = healthInsightsVersion.get()?.toInt(),
        javascriptVersion = javascriptVersion.get()?.toInt(),
    )
}

fun ByteArray.toMacAddressString(): String {
    require(size == 6) { "MAC address must be 6 bytes long" }
    return joinToString(":") { byte ->
        val intRepresentation = byte.toInt() and 0xFF
        intRepresentation.toString(16).padStart(2, '0').uppercase()
    }
}
