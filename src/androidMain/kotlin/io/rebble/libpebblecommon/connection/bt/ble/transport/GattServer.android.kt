@file:OptIn(ExperimentalUuidApi::class)

package io.rebble.libpebblecommon.connection.bt.ble.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt.GATT_SUCCESS
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.Transport.BluetoothTransport.BleTransport
import io.rebble.libpebblecommon.connection.asPebbleBluetoothIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.CHARACTERISTIC_CONFIGURATION_DESCRIPTOR
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.FAKE_SERVICE_UUID
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.META_CHARACTERISTIC_SERVER
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.PPOGATT_DEVICE_CHARACTERISTIC_SERVER
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.PPOGATT_DEVICE_SERVICE_UUID_SERVER
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

private fun getService(appContext: AppContext): BluetoothManager? =
    appContext.context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

actual fun openGattServer(appContext: AppContext): GattServer? {
    return try {
        val callback = GattServerCallback()
        getService(appContext)?.openGattServer(appContext.context, callback)?.let {
            callback.server = it
            io.rebble.libpebblecommon.connection.bt.ble.transport.GattServer(it, callback)
        }
    } catch (e: SecurityException) {
        Logger.d("error opening gatt server", e)
        null
    }
}

data class RegisteredDevice(
    val dataChannel: SendChannel<ByteArray>,
    val device: BluetoothDevice,
    val notificationsEnabled: Boolean,
)

class GattServerCallback : BluetoothGattServerCallback() {
    //    private val _connectionState = MutableStateFlow<ServerConnectionstateChanged?>(null)
//    val connectionState = _connectionState.asSharedFlow()
    val registeredDevices: MutableMap<String, RegisteredDevice> = mutableMapOf()
    var server: BluetoothGattServer? = null

    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
        Logger.d("onConnectionStateChange: ${device.address} = $newState")
//        _connectionState.tryEmit(
//            ServerConnectionstateChanged(
//                deviceId = device.address,
//                connectionState = newState,
//            )
//        )
    }

    private val _serviceAdded = MutableSharedFlow<ServerServiceAdded?>()
    val serviceAdded = _serviceAdded.asSharedFlow()

    override fun onServiceAdded(status: Int, service: BluetoothGattService) {
        Logger.d("onServiceAdded: ${service.uuid}")
        runBlocking {
            _serviceAdded.emit(ServerServiceAdded(service.uuid.asUuid()))
        }
    }

    private val _characteristicReadRequest = MutableStateFlow<RawCharacteristicReadRequest?>(null)
    val characteristicReadRequest = _characteristicReadRequest.asSharedFlow()

    data class RawCharacteristicReadRequest(
        val device: BluetoothDevice,
        val requestId: Int,
        val offset: Int,
        val characteristic: BluetoothGattCharacteristic,
        // TODO Hack to make it emit again while using a StateFlow, because MutableSharedFlow is not
        //  working for some reason
        val uuidHack: Uuid = Uuid.random(),
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

//    private val _characteristicWriteRequest =
//        MutableStateFlow<ServerCharacteristicWriteRequest?>(null)
//    val characteristicWriteRequest = _characteristicWriteRequest.asSharedFlow()

    override fun onCharacteristicWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray,
    ) {
//        Logger.d("onCharacteristicWriteRequest: ${device.address} / ${characteristic.uuid}: ${value.joinToString()}")
        val registeredDevice = registeredDevices[device.address]
        if (registeredDevice == null) {
            Logger.e("onCharacteristicWriteRequest couldn't find registered device: ${device.address}")
            return
        }
        val result = registeredDevice.dataChannel.trySend(value)
        if (result.isFailure) {
            Logger.e("onCharacteristicWriteRequest error writing to channel: $result")
        }
    }

    @SuppressLint("MissingPermission")
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
        val registeredDevice = registeredDevices[device.address]
        if (registeredDevice == null) {
            Logger.e("onDescriptorWriteRequest device not registered!")
            return
        }
        val gattServer = server
        if (gattServer == null) {
            Logger.e("onDescriptorWriteRequest no server!!")
            return
        }
        if (!gattServer.sendResponse(device, requestId, GATT_SUCCESS, offset, value)) {
            Logger.e("onDescriptorWriteRequest failed to respond")
            return
        }
        registeredDevices[device.address] = registeredDevice.copy(notificationsEnabled = true)
//        Logger.d("/onDescriptorWriteRequest")
    }

    val notificationSent = MutableStateFlow<NotificationSent?>(null)

    override fun onNotificationSent(device: BluetoothDevice, status: Int) {
//        Logger.d("onNotificationSent: ${device.address}")
        notificationSent.tryEmit(
            NotificationSent(
                deviceId = device.address.asPebbleBluetoothIdentifier(),
                status = status,
            )
        )
    }
}

actual class GattServer(
    val server: BluetoothGattServer,
    val callback: GattServerCallback,
    val cbTimeout: Long = 8000,
) : BluetoothGattServerCallback() {
    actual val characteristicReadRequest = callback.characteristicReadRequest.filterNotNull().map {
        ServerCharacteristicReadRequest(
            deviceId = it.device.address.asPebbleBluetoothIdentifier(),
            uuid = it.characteristic.uuid.asUuid(),
            respond = { bytes ->
                try {
                    server.sendResponse(
                        it.device,
                        it.requestId,
                        GATT_SUCCESS,
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

//    actual val connectionState = callback.connectionState.filterNotNull()

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

    actual suspend fun addServices() {
        Logger.d("addServices")
        addService(
            PPOGATT_DEVICE_SERVICE_UUID_SERVER, listOf(
                BluetoothGattCharacteristic(
                    META_CHARACTERISTIC_SERVER.toJavaUuid(),
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
                ),
                BluetoothGattCharacteristic(
                    PPOGATT_DEVICE_CHARACTERISTIC_SERVER.toJavaUuid(),
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
                ).apply {
                    addDescriptor(
                        BluetoothGattDescriptor(
                            CHARACTERISTIC_CONFIGURATION_DESCRIPTOR.toJavaUuid(),
                            BluetoothGattDescriptor.PERMISSION_WRITE,
                        )
                    )
                },
            )
        )
        addService(
            FAKE_SERVICE_UUID, listOf(
                BluetoothGattCharacteristic(
                    FAKE_SERVICE_UUID.toJavaUuid(),
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
                ),
            )
        )
        Logger.d("/addServices")
    }

    private suspend fun addService(
        serviceUuid: Uuid,
        characteristics: List<BluetoothGattCharacteristic>,
    ) {
        Logger.d("addService: $serviceUuid")
        val service = BluetoothGattService(serviceUuid.toJavaUuid(), SERVICE_TYPE_PRIMARY)
        characteristics.forEach { service.addCharacteristic(it) }
        try {
            callback.serviceAdded.onSubscription {
                server.addService(service)
            }.first { service.uuid.asUuid() == serviceUuid }
        } catch (e: SecurityException) {
            Logger.d("error adding gatt service ${service.uuid}", e)
        }
    }

    actual fun registerDevice(
        transport: BleTransport,
        sendChannel: SendChannel<ByteArray>
    ) {
        Logger.d("registerDevice: $transport")
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val bluetoothDevice = adapter.getRemoteDevice(transport.identifier.macAddress)
        callback.registeredDevices[transport.identifier.macAddress] =
            RegisteredDevice(
                dataChannel = sendChannel,
                device = bluetoothDevice,
                notificationsEnabled = false,
            )
    }

    actual fun unregisterDevice(transport: BleTransport) {
        callback.registeredDevices.remove(transport.identifier.macAddress)
    }

    @SuppressLint("MissingPermission")
    actual suspend fun sendData(
        transport: BleTransport,
        serviceUuid: Uuid,
        characteristicUuid: Uuid,
        data: ByteArray,
    ): Boolean {
        val registeredDevice = callback.registeredDevices[transport.identifier.macAddress]
        if (registeredDevice == null) {
            Logger.e("sendData: couldn't find registered device: $transport")
            return false
        }
        val service = server.getService(serviceUuid.toJavaUuid())
        if (service == null) {
            Logger.e("sendData: couldn't find service")
            return false
        }
        val characteristic = service.getCharacteristic(characteristicUuid.toJavaUuid())
        if (characteristic == null) {
            Logger.e("sendData: couldn't find characteristic")
            return false
        }
        callback.notificationSent.value = null // TODO better way of doing this?
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val writeRes = server.notifyCharacteristicChanged(
                registeredDevice.device,
                characteristic,
                false,
                data
            )
            if (writeRes != BluetoothStatusCodes.SUCCESS) {
                Logger.e("couldn't notify data characteristic: $writeRes")
                return false
            }
        } else {
            characteristic.value = data
            if (!server.notifyCharacteristicChanged(
                    registeredDevice.device,
                    characteristic,
                    false
                )
            ) {
                Logger.e("couldn't notify data characteristic")
                return false
            }
        }
        return try {
            val res = withTimeout(cbTimeout) {
                callback.notificationSent.filterNotNull()
                    .first { transport.identifier == it.deviceId }
            }
            if (res.status != GATT_SUCCESS) {
                Logger.e("characteristic notify error: ${res.status}")
                false
            } else {
                true
            }
        } catch (e: TimeoutCancellationException) {
            Logger.e("characteristic notify timed out")
            false
        }
    }
}

private fun UUID.asUuid(): Uuid = Uuid.parse(toString())
