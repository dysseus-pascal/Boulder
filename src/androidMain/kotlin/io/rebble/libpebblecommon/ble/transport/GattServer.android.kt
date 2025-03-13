package io.rebble.libpebblecommon.ble.transport

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY
import android.bluetooth.BluetoothManager
import android.content.Context
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.ble.pebble.AppContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import java.util.UUID

private fun getService(appContext: AppContext): BluetoothManager? =
    appContext.context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

actual fun openGattServer(appContext: AppContext): GattServer? {
    return try {
        val callback = GattServerCallback()
        getService(appContext)?.openGattServer(appContext.context, callback)?.let {
            io.rebble.libpebblecommon.ble.transport.GattServer(it, callback)
        }
    } catch (e: SecurityException) {
        Logger.d("error opening gatt server", e)
        null
    }
}

class GattServerCallback : BluetoothGattServerCallback() {
    private val _connectionState = MutableStateFlow<ServerConnectionstateChanged?>(null)
    val connectionState = _connectionState.asSharedFlow()

    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
        Logger.d("onConnectionStateChange: ${device.address} = $newState")
        _connectionState.tryEmit(
            ServerConnectionstateChanged(
                deviceId = device.address,
                connectionState = newState,
            )
        )
    }

    // TODO shouldn't really be a stateFlow, but I can't get SharedFlow to work. Just use a channel?
    private val _serviceAdded = MutableStateFlow<ServerServiceAdded?>(null)
    val serviceAdded = _serviceAdded.asSharedFlow()

    override fun onServiceAdded(status: Int, service: BluetoothGattService) {
        Logger.d("onServiceAdded: ${service.uuid}")
        val res = _serviceAdded.tryEmit(ServerServiceAdded(service.uuid.toString()))
    }

    private val _characteristicReadRequest = MutableStateFlow<RawCharacteristicReadRequest?>(null)
    val characteristicReadRequest = _characteristicReadRequest.asSharedFlow()

    data class RawCharacteristicReadRequest(
        val device: BluetoothDevice,
        val requestId: Int,
        val offset: Int,
        val characteristic: BluetoothGattCharacteristic,
    )

    override fun onCharacteristicReadRequest(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        characteristic: BluetoothGattCharacteristic,
    ) {
        Logger.d("onCharacteristicReadRequest: ${characteristic.uuid}")
        _characteristicReadRequest.tryEmit(
            RawCharacteristicReadRequest(device, requestId, offset, characteristic)
        )
    }

    private val _characteristicWriteRequest =
        MutableStateFlow<ServerCharacteristicWriteRequest?>(null)
    val characteristicWriteRequest = _characteristicWriteRequest.asSharedFlow()

    override fun onCharacteristicWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray,
    ) {
        Logger.d("onCharacteristicWriteRequest: ${device.address} / ${characteristic.uuid}")
        _characteristicWriteRequest.tryEmit(
            ServerCharacteristicWriteRequest(
                deviceId = device.address,
                uuid = characteristic.uuid.toString(),
                value = value,
            )
        )
    }

    private val _descriptorWriteRequest = MutableStateFlow<ServerDescriptorWriteRequest?>(null)
    val descriptorWriteRequest = _descriptorWriteRequest.asSharedFlow()

    override fun onDescriptorWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?,
    ) {
        Logger.d("onDescriptorWriteRequest: ${device.address} / ${descriptor.characteristic.uuid}")
        _descriptorWriteRequest.tryEmit(
            ServerDescriptorWriteRequest(
                deviceId = device.address,
                characteristicUuid = descriptor.characteristic.uuid.toString(),
                descriptorUuid = descriptor.uuid.toString(),
            )
        )
    }
}

actual class GattServer(
    val server: BluetoothGattServer,
    val callback: GattServerCallback,
) : BluetoothGattServerCallback() {
    actual val characteristicReadRequest = callback.characteristicReadRequest.filterNotNull().map {
        ServerCharacteristicReadRequest(
            deviceId = it.device.address,
            uuid = it.characteristic.uuid.toString(),
            respond = { bytes ->
                try {
                    server.sendResponse(
                        it.device,
                        it.requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        it.offset,
                        bytes
                    )
                } catch (e: SecurityException) {
                    Logger.d("error sending read response", e)
                    false
                }
            },
        )
    }
    actual val characteristicWriteRequest = callback.characteristicWriteRequest.filterNotNull()
    actual val descriptorWriteRequest = callback.descriptorWriteRequest.filterNotNull()
    actual val connectionState = callback.connectionState.filterNotNull()

    actual suspend fun closeServer() {
        try {
            server.clearServices()
        } catch (e: SecurityException) {
            Logger.d("error clearing gatt services", e)
        }
        try {
            server.close()
        } catch (e: SecurityException) {
            Logger.d("error closing gatt server", e)
        }
    }

    actual suspend fun addServices(services: List<GattService>) {
        Logger.d("addServices: $services")
        services.forEach { addService(it) }
        Logger.d("/addServices")
    }

    private suspend fun addService(service: GattService) {
        Logger.d("addService: ${service.uuid}")
        try {
            callback.serviceAdded.onSubscription {
                server.addService(service.asAndroidService())
            }.first {
                val equals = service.uuid.equals(it?.uuid, ignoreCase = true)
                Logger.d("// first = '${it?.uuid}'/'${service.uuid}' : equals = $equals")
                equals
            }
        } catch (e: SecurityException) {
            Logger.d("error adding gatt service ${service.uuid}", e)
        }
    }
}

private fun GattService.asAndroidService(): BluetoothGattService {
    val service = BluetoothGattService(UUID.fromString(uuid), SERVICE_TYPE_PRIMARY)
    characteristics.forEach { c ->
        val characteristic = BluetoothGattCharacteristic(
            /* uuid = */ UUID.fromString(c.uuid),
            /* properties = */ c.properties,
            /* permissions = */ c.permissions,
        )
        c.descriptors.forEach { d ->
            val descriptor = BluetoothGattDescriptor(
                /* uuid = */ UUID.fromString(d.uuid),
                /* permissions = */ d.permissions,
            )
            characteristic.addDescriptor(descriptor)
        }
        service.addCharacteristic(characteristic)
    }
    return service
}

private fun List<Int>.or(): Int = reduceOrNull { a, b -> a or b } ?: 0
