package io.rebble.libpebblecommon.connection

import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebbleLeScanRecord
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import kotlinx.coroutines.CoroutineScope

// mac address on android, uuid on ios etc
expect class PebbleBluetoothIdentifier {
    fun asString(): String
}

sealed class Transport {
    sealed class BluetoothTransport : Transport() {
        abstract val identifier: PebbleBluetoothIdentifier

        data class BtClassicTransport(override val identifier: PebbleBluetoothIdentifier) : BluetoothTransport()
        data class BleTransport(override val identifier: PebbleBluetoothIdentifier) : BluetoothTransport()
    }
    // e.g. emulator
    data class SocketTransport(val address: String) : Transport()
}

interface ActiveDevice

// <T : Transport> ?
sealed interface PebbleDevice {
    val name: String
    val transport: Transport

    suspend fun connect() // TODO return anything here, or put e.g. connection errors in `watches` state?
    suspend fun disconnect()
}

// We know a few more things about these, after a BLE scan but before connection
interface BleDiscoveredPebbleDevice : PebbleDevice {
    val pebbleScanRecord: PebbleLeScanRecord
}

// e.g. we have previously connected to it, and got all it's info (stored in the db)
interface KnownPebbleDevice : PebbleDevice {
    val isRunningRecoveryFw: Boolean
    // val connectionGoal: Goal // (e.g. connect, disconnect)
//    val capabilities: PebbleCapabilities
    // etc etc

    suspend fun forget()
}

interface ConnectingPebbleDevice : PebbleDevice, ActiveDevice

interface NegotiatingPebbleDevice : ConnectingPebbleDevice, ActiveDevice

interface ConnectedPebbleDeviceInRecovery : KnownPebbleDevice, ActiveDevice {
    suspend fun updateFirmware()
}

interface ConnectedPebbleDevice : KnownPebbleDevice, ActiveDevice {
    // for hackers?
    fun sendPPMessage(bytes: ByteArray)
    fun sendPPMessage(ppMessage: PebblePacket)

    // not for general use
    suspend fun sendNotification()
    suspend fun sendPing(cookie: UInt): UInt
    // ....
}
