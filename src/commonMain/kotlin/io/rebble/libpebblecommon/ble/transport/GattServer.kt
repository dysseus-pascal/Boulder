package io.rebble.libpebblecommon.ble.transport

import io.rebble.libpebblecommon.ble.pebble.AppContext
import kotlinx.coroutines.flow.Flow

expect fun openGattServer(appContext: AppContext): GattServer?

expect class GattServer {
    suspend fun addServices(services: List<GattService>)
    suspend fun closeServer()
    val characteristicReadRequest: Flow<ServerCharacteristicReadRequest>
    val characteristicWriteRequest: Flow<ServerCharacteristicWriteRequest>
    val descriptorWriteRequest: Flow<ServerDescriptorWriteRequest>
    val connectionState: Flow<ServerConnectionstateChanged>
}

data class ServerServiceAdded(val uuid: String)
data class ServerConnectionstateChanged(val deviceId: String, val connectionState: Int)
// Watch reading meta characteristic
data class ServerCharacteristicReadRequest(val deviceId: String, val uuid: String, val respond: (ByteArray) -> Boolean)
// Watch writing to data characteristic
data class ServerCharacteristicWriteRequest(val deviceId: String, val uuid: String, val value: ByteArray)
// Watch subscribed to data characteristic
data class ServerDescriptorWriteRequest(val deviceId: String, val characteristicUuid: String, val descriptorUuid: String)

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