package io.rebble.libpebblecommon.ble.transport

import kotlinx.coroutines.flow.Flow

//expect fun createGattClient(scannedPebbleDevice: ScannedPebbleDevice): GattClient

interface GattConnector {
    suspend fun connect(): ConnectedGattClient?
}

interface ConnectedGattClient {
    suspend fun discoverServices(): List<GattService>?
    suspend fun subscribeToCharacteristic(serviceUuid: String, characteristicUuid: String): Flow<ByteArray>
    suspend fun isBonded(): Boolean
    suspend fun writeCharacteristic(serviceUuid: String, characteristicUuid: String, value: ByteArray): Boolean
    val services: List<GattService>?
}
