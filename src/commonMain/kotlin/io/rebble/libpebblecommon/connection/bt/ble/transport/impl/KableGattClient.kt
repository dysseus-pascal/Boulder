@file:OptIn(ExperimentalUuidApi::class)

package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import co.touchlab.kermit.Logger
import com.juul.kable.DiscoveredCharacteristic
import com.juul.kable.DiscoveredService
import com.juul.kable.Identifier
import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import com.juul.kable.toIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.pebble.ScannedPebbleDevice
import io.rebble.libpebblecommon.connection.bt.ble.transport.ConnectedGattClient
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattCharacteristic
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattConnector
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattDescriptor
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattService
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattWriteType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

fun kableGattConnector(scannedPebbleDevice: ScannedPebbleDevice): GattConnector =
    KableGattConnector(scannedPebbleDevice)

expect fun peripheralFromIdentifier(identifier: Identifier): Peripheral

class KableGattConnector(
    val scannedPebbleDevice: ScannedPebbleDevice,
) : GattConnector {
    private val peripheral = peripheralFromIdentifier(scannedPebbleDevice.identifier.toIdentifier())

    override suspend fun connect(): ConnectedGattClient? {
        try {
            val scope = peripheral.connect()
            return KableConnectedGattClient(scannedPebbleDevice, scope, peripheral)
        } catch (e: Exception) {
            Logger.e("error connecting", e)
            return null
        }
    }
}

class KableConnectedGattClient(
    val scannedPebbleDevice: ScannedPebbleDevice,
    val scope: CoroutineScope,
    val peripheral: Peripheral,
) : ConnectedGattClient {
    override suspend fun discoverServices(): Boolean {
        // Kable already discovered upon connect
        return true
    }

    private fun mapServices() = peripheral.services.value?.map { it.asGattService() }

    override suspend fun subscribeToCharacteristic(
        serviceUuid: String,
        characteristicUuid: String,
    ): Flow<ByteArray>? {
        val c = findCharacteristic(serviceUuid, characteristicUuid)
        if (c == null) {
            Logger.e("couldn't find characteristic: $characteristicUuid")
            return null
        }
        return peripheral.observe(c)
    }

    override suspend fun isBonded(): Boolean {
        return io.rebble.libpebblecommon.connection.bt.isBonded(scannedPebbleDevice)
    }

    fun GattWriteType.asKableWriteType() = when (this) {
        GattWriteType.WithResponse -> WriteType.WithResponse
        GattWriteType.NoResponse -> WriteType.WithoutResponse
    }

    override suspend fun writeCharacteristic(
        serviceUuid: String,
        characteristicUuid: String,
        value: ByteArray,
        writeType: GattWriteType,
    ): Boolean {
        val c = findCharacteristic(serviceUuid, characteristicUuid)
        if (c == null) {
            Logger.e("couldn't find characteristic: $characteristicUuid")
            return false
        }
        peripheral.write(c, value, writeType.asKableWriteType())
        return true
    }

    override val services: List<GattService>? = mapServices()

    private fun findCharacteristic(serviceUuid: String, characteristicUuid: String): DiscoveredCharacteristic? {
        return peripheral.services.value
            ?.firstOrNull { it.serviceUuid == Uuid.parse(serviceUuid) }
            ?.characteristics
            ?.firstOrNull { it.characteristicUuid == Uuid.parse(characteristicUuid) }
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun DiscoveredService.asGattService(): GattService = GattService(
    uuid = serviceUuid.toString(),
    characteristics = characteristics.map { c ->
        GattCharacteristic(
            uuid = c.characteristicUuid.toString(),
            properties = c.properties.value,
            permissions = c.properties.value, // TODO right?
            descriptors = c.descriptors.map { d ->
                GattDescriptor(
                    uuid = d.descriptorUuid.toString(),
                    permissions = 0, // not provided by kable
                )
            },
        )
    },
)
