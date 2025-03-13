@file:OptIn(ExperimentalUuidApi::class)

package io.rebble.libpebblecommon.ble.transport.impl

import co.touchlab.kermit.Logger
import com.juul.kable.DiscoveredService
import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import io.rebble.libpebblecommon.ble.pebble.ScannedPebbleDevice
import io.rebble.libpebblecommon.ble.transport.ConnectedGattClient
import io.rebble.libpebblecommon.ble.transport.GattCharacteristic
import io.rebble.libpebblecommon.ble.transport.GattConnector
import io.rebble.libpebblecommon.ble.transport.GattDescriptor
import io.rebble.libpebblecommon.ble.transport.GattService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

fun gattConnector(scannedPebbleDevice: ScannedPebbleDevice): GattConnector =
    KableGattConnector(scannedPebbleDevice)

class KableGattConnector(
    val scannedPebbleDevice: ScannedPebbleDevice,
) : GattConnector {
    private val peripheral = Peripheral(scannedPebbleDevice.device)

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
    override suspend fun discoverServices(): List<GattService>? {
        // Kable already discovered upon connect
        return mapServices()
    }

    private fun mapServices() = peripheral.services.value?.map { it.asGattService() }

    override suspend fun subscribeToCharacteristic(
        serviceUuid: String,
        characteristicUuid: String,
    ): Flow<ByteArray> {
        val c = characteristicOf(Uuid.parse(serviceUuid), Uuid.parse(characteristicUuid))
        return peripheral.observe(c)
    }

    override suspend fun isBonded(): Boolean {
        return io.rebble.libpebblecommon.ble.transport.isBonded(scannedPebbleDevice)
    }

    override suspend fun writeCharacteristic(
        serviceUuid: String,
        characteristicUuid: String,
        value: ByteArray
    ): Boolean {
        val c = characteristicOf(Uuid.parse(serviceUuid), Uuid.parse(characteristicUuid))
        peripheral.write(c, value, WriteType.WithResponse)
        return true
    }

    override val services: List<GattService>? = mapServices()
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
