package io.rebble.libpebblecommon.connection.bt.ble.transport

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.Transport.BluetoothTransport.BleTransport
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.kableGattConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

//expect fun libpebbleGattConnector(scannedPebbleDevice: ScannedPebbleDevice, appContext: AppContext): GattConnector

fun gattConnector(transport: BleTransport, appContext: AppContext, scope: CoroutineScope): GattConnector?
// = libpebbleGattConnector(scannedPebbleDevice, appContext)
 = kableGattConnector(transport, scope)

interface GattConnector : AutoCloseable {
    suspend fun connect(): ConnectedGattClient?
    suspend fun disconnect()
    val disconnected: Deferred<Unit>
}

enum class GattWriteType {
    WithResponse,
    NoResponse,
}

interface ConnectedGattClient : AutoCloseable {
    suspend fun discoverServices(): Boolean
    suspend fun subscribeToCharacteristic(serviceUuid: Uuid, characteristicUuid: Uuid): Flow<ByteArray>?
    suspend fun isBonded(): Boolean // TODO doesn't belong in here
    suspend fun writeCharacteristic(serviceUuid: Uuid, characteristicUuid: Uuid, value: ByteArray, writeType: GattWriteType): Boolean
    suspend fun readCharacteristic(serviceUuid: Uuid, characteristicUuid: Uuid): ByteArray?
    val services: List<GattService>?
    suspend fun requestMtu(mtu: Int): Int
    suspend fun getMtu(): Int
}
