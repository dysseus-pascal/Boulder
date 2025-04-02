package io.rebble.libpebblecommon.connection.bt.ble.transport

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.Transport
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow

actual class GattServer {
    actual suspend fun addServices(services: List<GattService>) {
    }

    actual suspend fun closeServer() {
    }

    actual val characteristicReadRequest: Flow<ServerCharacteristicReadRequest>
        get() = TODO("Not yet implemented")
    actual val connectionState: Flow<ServerConnectionstateChanged>
        get() = TODO("Not yet implemented")

    actual fun registerDevice(
        transport: Transport.BluetoothTransport.BleTransport,
        sendChannel: SendChannel<ByteArray>
    ) {
    }

    actual fun unregisterDevice(transport: Transport.BluetoothTransport.BleTransport) {
    }

    actual suspend fun sendData(
        transport: Transport.BluetoothTransport.BleTransport,
        serviceUuid: String,
        characteristicUuid: String,
        data: ByteArray
    ): Boolean {
        TODO("Not yet implemented")
    }
}

actual fun openGattServer(appContext: AppContext): GattServer? {
    return null
}