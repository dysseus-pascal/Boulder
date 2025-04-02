package io.rebble.libpebblecommon.connection

import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebbleLeScanRecord
import io.rebble.libpebblecommon.packets.blobdb.AppMetadata
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import io.rebble.libpebblecommon.services.WatchInfo
import kotlin.uuid.Uuid

interface PebbleIdentifier {
    val asString: String
}

// mac address on android, uuid on ios etc
expect class PebbleBluetoothIdentifier : PebbleIdentifier {
    override val asString: String
}

expect fun String.asPebbleBluetoothIdentifier(): PebbleBluetoothIdentifier

data class PebbleSocketIdentifier(val address: String) : PebbleIdentifier {
    override val asString: String = address
}

sealed class Transport {
    abstract val identifier: PebbleIdentifier

    sealed class BluetoothTransport : Transport() {
        abstract override val identifier: PebbleBluetoothIdentifier

        data class BtClassicTransport(override val identifier: PebbleBluetoothIdentifier) :
            BluetoothTransport()

        data class BleTransport(override val identifier: PebbleBluetoothIdentifier) :
            BluetoothTransport()
    }

    // e.g. emulator
    data class SocketTransport(override val identifier: PebbleSocketIdentifier) : Transport()
}

interface ActiveDevice

// <T : Transport> ?
sealed interface PebbleDevice {
    val name: String
    val transport: Transport
    val connectGoal: Boolean

    suspend fun connect()
    suspend fun disconnect()
}

interface DiscoveredPebbleDevice : PebbleDevice

// We know a few more things about these, after a BLE scan but before connection
interface BleDiscoveredPebbleDevice : DiscoveredPebbleDevice {
    val pebbleScanRecord: PebbleLeScanRecord
}

// e.g. we have previously connected to it, and got all it's info (stored in the db)
interface KnownPebbleDevice : PebbleDevice {
    val runningFwVersion: String
    val serial: String

    suspend fun forget()
}

interface ConnectingPebbleDevice : PebbleDevice, ActiveDevice

interface NegotiatingPebbleDevice : ConnectingPebbleDevice, ActiveDevice

interface ConnectedPebbleDeviceInRecovery : KnownPebbleDevice, ActiveDevice {
    suspend fun updateFirmware()
}

interface ConnectedPebbleDevice : KnownPebbleDevice, ActiveDevice {
    val watchInfo: WatchInfo

    // for hackers?
    fun sendPPMessage(bytes: ByteArray)
    fun sendPPMessage(ppMessage: PebblePacket)

    // not for general use
    suspend fun sendNotification(notification: TimelineItem)
    suspend fun sendPing(cookie: UInt): UInt
    suspend fun insertLockerEntry(entry: AppMetadata)
    suspend fun deleteLockerEntry(uuid: Uuid)
    suspend fun isLockerEntryNew(entry: AppMetadata): Boolean
    suspend fun launchApp(uuid: Uuid)
    // ....
}
