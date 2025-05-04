package io.rebble.libpebblecommon.connection.bt

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.Transport
import io.rebble.libpebblecommon.connection.bt.ble.pebble.ConnectivityStatus
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.BOND_BONDED
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.BOND_NONE
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

actual fun isBonded(transport: Transport.BluetoothTransport): Boolean {
    return true
}

actual fun createBond(transport: Transport.BluetoothTransport): Boolean {
    return true
}

actual fun getBluetoothDevicePairEvents(
    context: AppContext,
    transport: Transport.BluetoothTransport,
    connectivity: Flow<ConnectivityStatus>,
): Flow<BluetoothDevicePairEvent> = connectivity
    .map { BluetoothDevicePairEvent(
        device = transport.identifier,
        bondState = when {
            it.paired && it.encrypted -> BOND_BONDED
            else -> BOND_NONE
        },
        unbondReason = -1,
    ) }