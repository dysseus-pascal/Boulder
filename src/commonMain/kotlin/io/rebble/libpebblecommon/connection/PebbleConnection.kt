package io.rebble.libpebblecommon.connection

import io.rebble.libpebblecommon.protocolhelpers.PebblePacket

//enum class Transport {
//    BtClassic,
//    Ble,
//    //...
//}

// mac address on android, uuid on ios etc
expect class PebbleIdentifier {
    fun asString(): String
}

sealed class Transport {
    class BtClassicTransport(val identifier: PebbleIdentifier)
    class BleTransport(val identifier: PebbleIdentifier)
    // e.g. emulator
    class SocketTransport(val address: String)
}

sealed interface PebbleDevice {
    val name: String
    val transport: Transport

    suspend fun connect() // TODO return anything here, or put e.g. connection errors in `watches` state?
    suspend fun disconnect()
}

// We know a few more things about these, after a BLE scan but before connection
interface BleDiscoveredPebbleDevice : PebbleDevice {
    val fwVersion: String // TODO typed
    val recoveryVersion: String // TODO typed
    val serialNo: String
    val rssi: Int
    // model/color
    // .... lots more
}

// e.g. we have previously connected to it, and got all it's info (stored in the db)
interface KnownPebbleDevice : PebbleDevice {
    val isRunningRecoveryFw: Boolean
    // val connectionGoal: Goal // (e.g. connect, disconnect)
//    val capabilities: PebbleCapabilities
    // etc etc

    suspend fun forget()
}

interface ConnectingPebbleDevice : KnownPebbleDevice

interface ConnectedPebbleDeviceInRecovery : KnownPebbleDevice {
    suspend fun updateFirmware()
}

interface ConnectedPebbleDevice : KnownPebbleDevice {
    // for hackers?
    fun sendPPMessage(bytes: ByteArray)
    fun sendPPMessage(ppMessage: PebblePacket)

    // not for general use
    suspend fun sendNotification()
    suspend fun sendPing()
    // ....
}
