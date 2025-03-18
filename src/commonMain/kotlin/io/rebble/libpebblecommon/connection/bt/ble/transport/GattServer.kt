package io.rebble.libpebblecommon.connection.bt.ble.transport

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.Transport.BluetoothTransport.BleTransport
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow

expect fun openGattServer(appContext: AppContext): GattServer?

expect class GattServer {
    suspend fun addServices(services: List<GattService>)
    suspend fun closeServer()
    val characteristicReadRequest: Flow<ServerCharacteristicReadRequest>
    val connectionState: Flow<ServerConnectionstateChanged>
    fun registerDevice(transport: BleTransport, sendChannel: SendChannel<ByteArray>)
    fun unregisterDevice(transport: BleTransport)
    suspend fun sendData(transport: BleTransport, serviceUuid: String,
                         characteristicUuid: String, data: ByteArray): Boolean
}

data class ServerServiceAdded(val uuid: String)
data class ServerConnectionstateChanged(val deviceId: String, val connectionState: Int)
// Watch reading meta characteristic
data class ServerCharacteristicReadRequest(val deviceId: String, val uuid: String, val respond: (ByteArray) -> Boolean)
data class NotificationSent(val deviceId: String, val status: Int)

data class GattService(
    val uuid: String,
    val characteristics: List<GattCharacteristic>
)

data class GattCharacteristic(
    val uuid: String,
    val properties: Int,
    val permissions: Int,
    val descriptors: List<GattDescriptor>,
)

data class GattDescriptor(
    val uuid: String,
    val permissions: Int,
)