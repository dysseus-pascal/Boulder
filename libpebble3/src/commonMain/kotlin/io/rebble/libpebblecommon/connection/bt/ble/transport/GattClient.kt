package io.rebble.libpebblecommon.connection.bt.ble.transport

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.kableGattConnector
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

//expect fun libpebbleGattConnector(scannedPebbleDevice: ScannedPebbleDevice, appContext: AppContext): GattConnector

fun gattConnector(
    identifier: PebbleBleIdentifier,
    name: String,
    appContext: AppContext,
    scope: ConnectionCoroutineScope,
): GattConnector?
// = libpebbleGattConnector(scannedPebbleDevice, appContext)
        = kableGattConnector(identifier = identifier, scope = scope, name = name)

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
    suspend fun subscribeToCharacteristic(
        serviceUuid: Uuid,
        characteristicUuid: Uuid
    ): Flow<ByteArray>?

    suspend fun isBonded(): Boolean // TODO doesn't belong in here
    suspend fun writeCharacteristic(
        serviceUuid: Uuid,
        characteristicUuid: Uuid,
        value: ByteArray,
        writeType: GattWriteType
    ): Boolean

    suspend fun readCharacteristic(serviceUuid: Uuid, characteristicUuid: Uuid): ByteArray?
    val services: List<GattService>?
    suspend fun requestMtu(mtu: Int): Int
    suspend fun getMtu(): Int
}
