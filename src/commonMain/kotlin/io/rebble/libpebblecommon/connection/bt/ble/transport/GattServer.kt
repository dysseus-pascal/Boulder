package io.rebble.libpebblecommon.connection.bt.ble.transport

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.PebbleBluetoothIdentifier
import io.rebble.libpebblecommon.connection.Transport.BluetoothTransport.BleTransport
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

expect fun openGattServer(appContext: AppContext): GattServer?

expect class GattServer {
    suspend fun addServices()
    suspend fun closeServer()
    val characteristicReadRequest: Flow<ServerCharacteristicReadRequest>
//    val connectionState: Flow<ServerConnectionstateChanged>
    fun registerDevice(transport: BleTransport, sendChannel: SendChannel<ByteArray>)
    fun unregisterDevice(transport: BleTransport)
    suspend fun sendData(transport: BleTransport, serviceUuid: Uuid,
                         characteristicUuid: Uuid, data: ByteArray): Boolean
}

data class ServerServiceAdded(val uuid: Uuid)
data class ServerConnectionstateChanged(val deviceId: PebbleBluetoothIdentifier, val connectionState: Int)
// Watch reading meta characteristic
data class ServerCharacteristicReadRequest(val deviceId: PebbleBluetoothIdentifier, val uuid: Uuid, val respond: (ByteArray) -> Boolean)
data class NotificationSent(val deviceId: PebbleBluetoothIdentifier, val status: Int)

data class GattService(
    val uuid: Uuid,
    val characteristics: List<GattCharacteristic>
)

data class GattCharacteristic(
    val uuid: Uuid,
    val properties: Int,
    val permissions: Int,
    val descriptors: List<GattDescriptor>,
)

data class GattDescriptor(
    val uuid: Uuid,
    val permissions: Int,
)