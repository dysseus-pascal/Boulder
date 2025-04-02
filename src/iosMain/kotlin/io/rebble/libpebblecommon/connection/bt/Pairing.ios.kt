package io.rebble.libpebblecommon.connection.bt

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.Transport
import kotlinx.coroutines.flow.Flow

actual fun isBonded(transport: Transport.BluetoothTransport): Boolean {
    TODO("Not yet implemented")
}

actual fun createBond(transport: Transport.BluetoothTransport): Boolean {
    TODO("Not yet implemented")
}

actual fun getBluetoothDevicePairEvents(
    context: AppContext,
    transport: Transport.BluetoothTransport
): Flow<BluetoothDevicePairEvent> {
    TODO("Not yet implemented")
}