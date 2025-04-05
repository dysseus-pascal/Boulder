package io.rebble.libpebblecommon.connection.bt

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.Transport
import io.rebble.libpebblecommon.connection.bt.ble.pebble.ConnectivityStatus
import kotlinx.coroutines.flow.Flow

actual fun isBonded(transport: Transport.BluetoothTransport): Boolean {
    TODO("Not yet implemented")
}

actual fun createBond(transport: Transport.BluetoothTransport): Boolean {
    TODO("Not yet implemented")
}

actual fun getBluetoothDevicePairEvents(
    context: AppContext,
    transport: Transport.BluetoothTransport,
    connectivity: Flow<ConnectivityStatus>
): Flow<BluetoothDevicePairEvent> {
    TODO("Not yet implemented")
}