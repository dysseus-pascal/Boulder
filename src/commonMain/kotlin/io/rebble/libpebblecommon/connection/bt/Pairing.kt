package io.rebble.libpebblecommon.connection.bt

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.PebbleBluetoothIdentifier
import io.rebble.libpebblecommon.connection.Transport
import io.rebble.libpebblecommon.connection.Transport.BluetoothTransport
import io.rebble.libpebblecommon.connection.bt.ble.pebble.ConnectivityStatus
import kotlinx.coroutines.flow.Flow

expect fun isBonded(transport: BluetoothTransport): Boolean

expect fun createBond(transport: BluetoothTransport): Boolean

class BluetoothDevicePairEvent(val device: PebbleBluetoothIdentifier, val bondState: Int, val unbondReason: Int?)

expect fun getBluetoothDevicePairEvents(
    context: AppContext,
    transport: BluetoothTransport,
    connectivity: Flow<ConnectivityStatus>,
): Flow<BluetoothDevicePairEvent>
