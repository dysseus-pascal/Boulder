package io.rebble.libpebblecommon.connection

import androidx.compose.runtime.Stable
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebbleLeScanRecord
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdate
import io.rebble.libpebblecommon.connection.endpointmanager.musiccontrol.MusicTrack
import io.rebble.libpebblecommon.js.PKJSApp
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.music.MusicAction
import io.rebble.libpebblecommon.music.PlaybackState
import io.rebble.libpebblecommon.music.RepeatType
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import io.rebble.libpebblecommon.services.appmessage.AppMessageResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.Instant
import kotlinx.io.files.Path
import kotlin.uuid.Uuid


interface ActiveDevice {
    fun disconnect()
}

// <T : Transport> ?
@Stable
sealed interface PebbleDevice {
    val transport: Transport
    val name: String get() = transport.name

    fun connect(uiContext: UIContext?)
}

interface DiscoveredPebbleDevice : PebbleDevice

// We know a few more things about these, after a BLE scan but before connection
interface BleDiscoveredPebbleDevice : DiscoveredPebbleDevice {
    val pebbleScanRecord: PebbleLeScanRecord
    val rssi: Int
}

// e.g. we have previously connected to it, and got all it's info (stored in the db)
interface KnownPebbleDevice : PebbleDevice {
    val runningFwVersion: String
    val serial: String
    val lastConnected: Instant
    val watchType: WatchHardwarePlatform
    fun forget()
}

interface DisconnectingPebbleDevice : PebbleDevice
interface DisconnectingKnownPebbleDevice : DisconnectingPebbleDevice, KnownPebbleDevice

interface ConnectingPebbleDevice : PebbleDevice, ActiveDevice {
    val negotiating: Boolean
}
interface ConnectingKnownPebbleDevice : ConnectingPebbleDevice, KnownPebbleDevice

interface ConnectedWatchInfo {
    val watchInfo: WatchInfo
    val firmwareUpdateAvailable: FirmwareUpdateCheckResult?
}

interface ConnectedPebbleDeviceInRecovery :
    KnownPebbleDevice,
    ActiveDevice,
    ConnectedPebble.Firmware,
    ConnectedWatchInfo,
    ConnectedPebble.Logs,
    ConnectedPebble.CoreDump

/**
 * Put all specific functionality here, rather than directly in [ConnectedPebbleDevice].
 *
 * Eventually, implementations of these interfaces should all be what we're currently calling
 * "Endpoint Managers". For now, "Services" are OK.
 */
object ConnectedPebble {
    interface AppMessages {
        val inboundAppMessages: Flow<AppMessageData>
        val transactionSequence: Iterator<Byte>
        suspend fun sendAppMessage(appMessageData: AppMessageData): AppMessageResult
        suspend fun sendAppMessageResult(appMessageResult: AppMessageResult)
    }

    interface Debug {
        suspend fun sendPing(cookie: UInt): UInt
        suspend fun resetIntoPrf()
    }

    interface Logs {
        suspend fun gatherLogs(): Path?
    }

    interface Messages {
        suspend fun sendPPMessage(bytes: ByteArray)
        suspend fun sendPPMessage(ppMessage: PebblePacket)
        val inboundMessages: Flow<PebblePacket>
    }

    interface Firmware {
        fun updateFirmware(path: Path): Flow<FirmwareUpdate.FirmwareUpdateStatus>
        fun updateFirmware(url: String): Flow<FirmwareUpdate.FirmwareUpdateStatus>
        fun checkforFirmwareUpdate()
    }

    interface AppRunState {
        suspend fun launchApp(uuid: Uuid)
        val runningApp: StateFlow<Uuid?>
    }

    interface PKJS {
        val currentPKJSSession: StateFlow<PKJSApp?>
    }

    interface Time {
        suspend fun updateTime()
    }

    interface CoreDump {
        suspend fun getCoreDump(unread: Boolean): Path?
    }

    interface Music {
        suspend fun updateTrack(track: MusicTrack)
        suspend fun updatePlaybackState(
            state: PlaybackState,
            trackPosMs: UInt,
            playbackRatePct: UInt,
            shuffle: Boolean,
            repeatType: RepeatType
        )
        suspend fun updatePlayerInfo(packageId: String, name: String)
        suspend fun updateVolumeInfo(volumePercent: UByte)
        val musicActions: Flow<MusicAction>
        val updateRequestTrigger: Flow<Unit>
    }

    class Services(
        val debug: ConnectedPebble.Debug,
        val appRunState: ConnectedPebble.AppRunState,
        val firmware: ConnectedPebble.Firmware,
        val messages: Messages,
        val time: Time,
        val appMessages: AppMessages,
        val logs: Logs,
        val coreDump: CoreDump,
        val music: Music,
        val pkjs: PKJS,
    )

    class PrfServices(
        val firmware: ConnectedPebble.Firmware,
        val logs: Logs,
        val coreDump: CoreDump,
    )
}

sealed interface ConnectedPebbleDevice :
    KnownPebbleDevice,
    ActiveDevice,
    ConnectedPebble.Debug,
    ConnectedPebble.Messages,
    ConnectedPebble.Firmware,
    ConnectedPebble.AppRunState,
    ConnectedWatchInfo,
    ConnectedPebble.Time,
    ConnectedPebble.AppMessages,
    ConnectedPebble.Logs,
    ConnectedPebble.CoreDump,
    ConnectedPebble.Music,
    ConnectedPebble.PKJS
