package io.rebble.libpebblecommon.connection

import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebbleLeScanRecord
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdate
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.CustomTimelineActionHandler
import io.rebble.libpebblecommon.packets.blobdb.AppMetadata
import io.rebble.libpebblecommon.packets.blobdb.TimelineAction
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.services.blobdb.TimelineActionResult
import io.rebble.libpebblecommon.services.blobdb.TimelineService
import kotlinx.coroutines.flow.Flow
import kotlinx.io.files.Path
import kotlin.uuid.Uuid


interface ActiveDevice {
    suspend fun disconnect()
}

// <T : Transport> ?
sealed interface PebbleDevice {
    val transport: Transport
    val name: String get() = transport.name

    suspend fun connect()
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
    suspend fun forget()
}

interface ConnectingPebbleDevice : PebbleDevice, ActiveDevice

interface NegotiatingPebbleDevice : ConnectingPebbleDevice, ActiveDevice

interface ConnectedWatchInfo {
    val watchInfo: WatchInfo
}

interface ConnectedPebbleDeviceInRecovery : PebbleDevice, ActiveDevice, ConnectedPebble.Firmware,
    ConnectedWatchInfo

/**
 * Put all specific functionality here, rather than directly in [ConnectedPebbleDevice].
 *
 * Eventually, implementations of these interfaces should all be what we're currently calling
 * "Endpoint Managers". For now, "Services" are OK.
 */
object ConnectedPebble {
    interface Notifications {
        suspend fun sendNotification(
            notification: TimelineItem,
            actionHandlers: Map<UByte, CustomTimelineActionHandler> = emptyMap()
        )
    }

    interface Debug {
        suspend fun sendPing(cookie: UInt): UInt
    }

    interface Messages {
        suspend fun sendPPMessage(bytes: ByteArray)
        suspend fun sendPPMessage(ppMessage: PebblePacket)
        val inboundMessages: Flow<PebblePacket>
    }

    interface Firmware {
        fun sideloadFirmware(path: Path): Flow<FirmwareUpdate.FirmwareUpdateStatus>
    }

    interface Locker {
        suspend fun insertLockerEntry(entry: AppMetadata)
        suspend fun deleteLockerEntry(uuid: Uuid)
        suspend fun isLockerEntryNew(entry: AppMetadata): Boolean
        suspend fun offloadApp(uuid: Uuid)
    }

    interface AppRunState {
        suspend fun launchApp(uuid: Uuid)
    }

    class Services(
        val debug: ConnectedPebble.Debug,
        val appRunState: ConnectedPebble.AppRunState,
        val firmware: ConnectedPebble.Firmware,
        val locker: ConnectedPebble.Locker,
        val notifications: ConnectedPebble.Notifications,
        val messages: Messages,
    )
}

interface ConnectedPebbleDevice : KnownPebbleDevice,
    ActiveDevice, ConnectedPebble.Notifications,
    ConnectedPebble.Debug,
    ConnectedPebble.Messages,
    ConnectedPebble.Firmware,
    ConnectedPebble.Locker,
    ConnectedPebble.AppRunState,
    ConnectedWatchInfo
