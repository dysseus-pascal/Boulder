//package io.rebble.libpebblecommon.ble.transport
//
//import android.app.Application
//import android.bluetooth.BluetoothAdapter
//import android.bluetooth.BluetoothDevice
//import android.bluetooth.BluetoothGatt
//import android.bluetooth.BluetoothGattCallback
//import android.bluetooth.BluetoothGattCharacteristic
//import android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
//import android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
//import android.bluetooth.BluetoothGattDescriptor
//import android.bluetooth.BluetoothGattService
//import android.bluetooth.BluetoothStatusCodes
//import co.touchlab.kermit.Logger
//import io.rebble.libpebblecommon.ble.pebble.AppContext
//import io.rebble.libpebblecommon.ble.pebble.LEConstants.CHARACTERISTIC_SUBSCRIBE_VALUE
//import io.rebble.libpebblecommon.ble.pebble.LEConstants.UUIDs.CHARACTERISTIC_CONFIGURATION_DESCRIPTOR
//import io.rebble.libpebblecommon.ble.pebble.ScannedPebbleDevice
//import kotlinx.coroutines.TimeoutCancellationException
//import kotlinx.coroutines.flow.Flow
//import kotlinx.coroutines.flow.MutableStateFlow
//import kotlinx.coroutines.flow.filter
//import kotlinx.coroutines.flow.filterNotNull
//import kotlinx.coroutines.flow.first
//import kotlinx.coroutines.flow.map
//import kotlinx.coroutines.withTimeout
//import java.util.UUID
//
//actual fun libpebbleGattConnector(
//    scannedPebbleDevice: ScannedPebbleDevice,
//    appContext: AppContext
//): GattConnector {
//    return LibpebbleGattConnector(
//        scannedPebbleDevice,
//        appContext.context,
//        GattClientCallback(scannedPebbleDevice)
//    )
//}
//
//class GattClientCallback(
//    val scannedPebbleDevice: ScannedPebbleDevice,
//) : BluetoothGattCallback() {
//    open class BlueGATTResult(val gatt: BluetoothGatt?)
//
//    open class StatusResult(gatt: BluetoothGatt?, val status: Int) : BlueGATTResult(gatt) {
//        fun isSuccess() = status == BluetoothGatt.GATT_SUCCESS
//    }
//
//    private fun deviceMatches(gatt: BluetoothGatt): Boolean =
//        scannedPebbleDevice.identifier
//            .equals(gatt.device?.address, ignoreCase = true)
//
//    class ConnectionStateResult(gatt: BluetoothGatt, status: Int, val newState: Int) :
//        StatusResult(gatt, status)
//
//    private val _connectionStateChanged = MutableStateFlow<ConnectionStateResult?>(null)
//    val connectionStateChanged = _connectionStateChanged.filterNotNull()
//
//    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
//        if (!deviceMatches(gatt)) return
//        Logger.d("onConnectionStateChange newState = $newState")
//        _connectionStateChanged.value = ConnectionStateResult(gatt, status, newState)
//    }
//
//    private val _servicesDiscovered = MutableStateFlow<StatusResult?>(null)
//    val servicesDiscovered = _servicesDiscovered.filterNotNull()
//
//    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
//        if (!deviceMatches(gatt)) return
//        Logger.d("onServicesDiscovered status = $status size = ${gatt.services.size}")
//        _servicesDiscovered.value = StatusResult(gatt, status)
//    }
//
//    private val _descriptorWritten = MutableStateFlow<DescriptorResult?>(null)
//    val descriptorWritten = _descriptorWritten.filterNotNull()
//
//    class DescriptorResult(
//        gatt: BluetoothGatt?,
//        val descriptor: BluetoothGattDescriptor?,
//        status: Int = BluetoothGatt.GATT_SUCCESS,
//        value: ByteArray? = null,
//    ) : StatusResult(gatt, status)
//
//    override fun onDescriptorWrite(
//        gatt: BluetoothGatt,
//        descriptor: BluetoothGattDescriptor,
//        status: Int
//    ) {
//        if (!deviceMatches(gatt)) return
//        Logger.d("onDescriptorWrite status = $status")
//        _descriptorWritten.value = DescriptorResult(gatt, descriptor, status)
//    }
//
//    private val _characteristicWritten = MutableStateFlow<CharacteristicResult?>(null)
//    val characteristicWritten = _characteristicWritten.filterNotNull()
//
//    class CharacteristicResult(
//        gatt: BluetoothGatt,
//        val characteristic: BluetoothGattCharacteristic,
//        val value: ByteArray? = null,
//        status: Int = BluetoothGatt.GATT_SUCCESS
//    ) : StatusResult(gatt, status)
//
//    override fun onCharacteristicWrite(
//        gatt: BluetoothGatt,
//        characteristic: BluetoothGattCharacteristic,
//        status: Int,
//    ) {
//        if (!deviceMatches(gatt)) return
//        Logger.d("onCharacteristicWrite status = $status")
//        _characteristicWritten.value = CharacteristicResult(gatt, characteristic, status = status)
//    }
//
//    private val _characteristicChanged = MutableStateFlow<CharacteristicResult?>(null)
//    val characteristicChanged = _characteristicChanged.filterNotNull()
//
//    override fun onCharacteristicChanged(
//        gatt: BluetoothGatt,
//        characteristic: BluetoothGattCharacteristic,
//    ) {
//        onCharacteristicChanged(gatt, characteristic, characteristic.value)
//    }
//
//    override fun onCharacteristicChanged(
//        gatt: BluetoothGatt,
//        characteristic: BluetoothGattCharacteristic,
//        value: ByteArray,
//    ) {
//        if (!deviceMatches(gatt)) return
//        Logger.d("onCharacteristicChanged uuid = ${characteristic.uuid} value = $value")
//        _characteristicChanged.value = CharacteristicResult(gatt, characteristic, value)
//    }
//}
//
//class LibpebbleGattConnector(
//    val scannedPebbleDevice: ScannedPebbleDevice,
//    val context: Application,
//    val callback: GattClientCallback,
//    val cbTimeout: Long = 8000,
//    val auto: Boolean = false,
//) : GattConnector {
//    override suspend fun connect(): ConnectedGattClient? {
//        val adapter = BluetoothAdapter.getDefaultAdapter()
//        val macAddress = scannedPebbleDevice.identifier
//        val device = adapter.getRemoteDevice(macAddress)
//        val gatt = try {
//            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
//                device.connectGatt(
//                    context,
//                    auto,
//                    callback,
//                    BluetoothDevice.TRANSPORT_LE,
//                    BluetoothDevice.PHY_LE_1M
//                )
//            } else {
//                device.connectGatt(
//                    context,
//                    auto,
//                    callback,
//                    BluetoothDevice.TRANSPORT_LE
//                )
//            }
//        } catch (e: SecurityException) {
//            Logger.e("connectGatt", e)
//            return null
//        }
//        val res = try {
//            withTimeout(cbTimeout) {
//                callback.connectionStateChanged.first()
//            }
//        } catch (e: TimeoutCancellationException) {
////            if (unbondOnTimeout) {
////                Timber.w("Gatt timed out. Removing bond and retrying.")
////
////                if (device.bondState == BluetoothDevice.BOND_BONDED) {
////                    device::class.java.getMethod("removeBond").invoke(device)
////                }
////
////                return connectGatt(context, auto, unbondOnTimeout = false)
////            }
//            Logger.e("connectGatt timed out")
//            return null
//        }
//        if (res.status != BluetoothGatt.GATT_SUCCESS) {
////            Logger.e("connectGatt status ${GattStatus(res.status ?: -1)}")
//            Logger.e("connectGatt status ${res.status}")
//        }
//        return if (res.isSuccess() && res.newState == BluetoothGatt.STATE_CONNECTED) {
//            LibpebbleConnectedGattClient(scannedPebbleDevice, gatt, callback)
//        } else {
//            try {
//                gatt.close() // TODO
//            } catch (e: SecurityException) {
//                Logger.e("error closing gatt", e)
//            }
//            null
//        }
//    }
//}
//
//class LibpebbleConnectedGattClient(
//    val scannedPebbleDevice: ScannedPebbleDevice,
//    val gatt: BluetoothGatt,
//    val callback: GattClientCallback,
//    val cbTimeout: Long = 8000,
//) : ConnectedGattClient {
//    override suspend fun discoverServices(): Boolean {
//        if (!gatt.discoverServices()) return false
//        return try {
//            withTimeout(cbTimeout) {
//                callback.servicesDiscovered.first()
//            }
//            true
//        } catch (e: TimeoutCancellationException) {
//            Logger.e("discoverServices timed out")
//            false
//        }
//    }
//
//    override suspend fun subscribeToCharacteristic(
//        serviceUuid: String,
//        characteristicUuid: String,
//    ): Flow<ByteArray>? {
//        val service = gatt.getService(UUID.fromString(serviceUuid))
//        if (service == null) {
//            Logger.e("couldn't find service $serviceUuid")
//            return null
//        }
//        val characteristicUUID = UUID.fromString(characteristicUuid)
//        val characteristic = service.getCharacteristic(characteristicUUID)
//        if (characteristic == null) {
//            Logger.e("couldn't find characteristic $characteristicUuid")
//            return null
//        }
//        if (!gatt.setCharacteristicNotification(characteristic, true)) {
//            Logger.e("couldn't set notifications for characteristic $characteristicUuid")
//            return null
//        }
//        val descriptor =
//            characteristic.getDescriptor(UUID.fromString(CHARACTERISTIC_CONFIGURATION_DESCRIPTOR))
//        val descriptorRes = writeDescriptor(descriptor, CHARACTERISTIC_SUBSCRIBE_VALUE)
//        if (descriptorRes == null || !descriptorRes.isSuccess()) {
//            Logger.e("couldn't write descriptor for characteristic $characteristicUuid")
//            return null
//        }
//        return callback.characteristicChanged.filter { it.characteristic.uuid == characteristicUUID }
//            .map { it.value }.filterNotNull()
//    }
//
//    override suspend fun isBonded(): Boolean {
//        return io.rebble.libpebblecommon.ble.transport.isBonded(scannedPebbleDevice)
//    }
//
//    fun GattWriteType.asAndroidWriteType() = when (this) {
//        GattWriteType.WithResponse -> WRITE_TYPE_DEFAULT
//        GattWriteType.NoResponse -> WRITE_TYPE_NO_RESPONSE
//    }
//
//    override suspend fun writeCharacteristic(
//        serviceUuid: String,
//        characteristicUuid: String,
//        value: ByteArray,
//        writeType: GattWriteType,
//    ): Boolean {
//        Logger.d("writeCharacteristic: $characteristicUuid : ${value.joinToString()}")
//        val service = gatt.getService(UUID.fromString(serviceUuid))
//        if (service == null) {
//            Logger.e("couldn't find service $serviceUuid")
//            return false
//        }
//        val characteristicUUID = UUID.fromString(characteristicUuid)
//        val characteristic = service.getCharacteristic(characteristicUUID)
//        if (characteristic == null) {
//            Logger.e("couldn't find characteristic $characteristicUuid")
//            return false
//        }
//        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
//            val writeRes = gatt.writeCharacteristic(characteristic, value, writeType.asAndroidWriteType())
//            if (writeRes != BluetoothStatusCodes.SUCCESS) {
//                Logger.e("couldn't write characteristic $characteristicUuid: error $writeRes")
//                return false
//            }
//        } else {
//            characteristic.value = value
//            // TODO: to type or not to type?
//            characteristic.writeType = writeType.asAndroidWriteType()
//            if (!gatt.writeCharacteristic(characteristic)) {
//                Logger.e("couldn't write characteristic $characteristicUuid")
//                return false
//            }
//        }
//        return try {
//            val res = withTimeout(cbTimeout) {
//                callback.characteristicWritten.first { a -> a.characteristic.uuid == characteristic.uuid }
//            }
//            if (!res.isSuccess()) {
//                Logger.e("characteristic write error: ${res.status}")
//                false
//            } else {
//                true
//            }
//        } catch (e: TimeoutCancellationException) {
//            Logger.e("writeDescriptor timed out")
//            false
//        }
//    }
//
//    private suspend fun writeDescriptor(
//        descriptor: BluetoothGattDescriptor,
//        value: ByteArray
//    ): GattClientCallback.DescriptorResult? {
//        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
//            if (gatt.writeDescriptor(descriptor, value) != BluetoothStatusCodes.SUCCESS) return null
//        } else {
//            descriptor.value = value
//            if (!gatt.writeDescriptor(descriptor)) return null
//        }
//        return try {
//            withTimeout(cbTimeout) {
//                callback.descriptorWritten.first { a -> a.descriptor?.uuid == descriptor.uuid && a.descriptor?.characteristic?.uuid == descriptor.characteristic.uuid }
//            }
//        } catch (e: TimeoutCancellationException) {
//            Logger.e("writeDescriptor timed out")
//            null
//        }
//    }
//
//    override val services: List<GattService>?
//        get() = gatt.services.map { it.asGattService() }
//}
//
//private fun BluetoothGattService.asGattService(): GattService = GattService(
//    uuid = uuid.toString(),
//    characteristics = characteristics.map { c ->
//        GattCharacteristic(
//            uuid = c.uuid.toString(),
//            properties = c.properties,
//            permissions = c.permissions, // TODO right?
//            descriptors = c.descriptors.map { d ->
//                GattDescriptor(
//                    uuid = d.uuid.toString(),
//                    permissions = 0, // not provided by kable
//                )
//            },
//        )
//    },
//)
