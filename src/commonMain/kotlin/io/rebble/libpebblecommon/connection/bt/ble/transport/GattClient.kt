package io.rebble.libpebblecommon.connection.bt.ble.transport

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.bt.ble.pebble.ScannedPebbleDevice
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.kableGattConnector
import kotlinx.coroutines.flow.Flow

//expect fun libpebbleGattConnector(scannedPebbleDevice: ScannedPebbleDevice, appContext: AppContext): GattConnector

fun gattConnector(scannedPebbleDevice: ScannedPebbleDevice, appContext: AppContext): GattConnector
// = libpebbleGattConnector(scannedPebbleDevice, appContext)
 = kableGattConnector(scannedPebbleDevice)

interface GattConnector {
    suspend fun connect(): ConnectedGattClient?
}

enum class GattWriteType {
    WithResponse,
    NoResponse,
}

interface ConnectedGattClient {
    suspend fun discoverServices(): Boolean
    suspend fun subscribeToCharacteristic(serviceUuid: String, characteristicUuid: String): Flow<ByteArray>?
    suspend fun isBonded(): Boolean // TODO doesn't belong in here
    suspend fun writeCharacteristic(serviceUuid: String, characteristicUuid: String, value: ByteArray, writeType: GattWriteType): Boolean
    val services: List<GattService>?
}
