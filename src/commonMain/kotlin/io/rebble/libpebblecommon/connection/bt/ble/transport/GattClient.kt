package io.rebble.libpebblecommon.connection.bt.ble.transport

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.Transport
import io.rebble.libpebblecommon.connection.Transport.BluetoothTransport.BleTransport
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.kableGattConnector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow

//expect fun libpebbleGattConnector(scannedPebbleDevice: ScannedPebbleDevice, appContext: AppContext): GattConnector

fun gattConnector(transport: BleTransport, appContext: AppContext): GattConnector
// = libpebbleGattConnector(scannedPebbleDevice, appContext)
 = kableGattConnector(transport)

interface GattConnector : AutoCloseable {
    suspend fun connect(): ConnectedGattClient?
    suspend fun disconnect()
    val disconnected: Flow<Unit>
}

enum class GattWriteType {
    WithResponse,
    NoResponse,
}

interface ConnectedGattClient : AutoCloseable {
    suspend fun discoverServices(): Boolean
    suspend fun subscribeToCharacteristic(serviceUuid: String, characteristicUuid: String): Flow<ByteArray>?
    suspend fun isBonded(): Boolean // TODO doesn't belong in here
    suspend fun writeCharacteristic(serviceUuid: String, characteristicUuid: String, value: ByteArray, writeType: GattWriteType): Boolean
    suspend fun readCharacteristic(serviceUuid: String, characteristicUuid: String): ByteArray?
    val services: List<GattService>?
}
