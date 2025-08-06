package io.rebble.libpebblecommon.connection.bt

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.pebble.ConnectivityStatus
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.BOND_BONDED
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.BOND_NONE
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

actual fun isBonded(identifier: PebbleBleIdentifier): Boolean {
    return true
}

actual fun createBond(identifier: PebbleBleIdentifier): Boolean {
    return true
}

actual fun getBluetoothDevicePairEvents(
    context: AppContext,
    identifier: PebbleBleIdentifier,
    connectivity: Flow<ConnectivityStatus>,
): Flow<BluetoothDevicePairEvent> = connectivity
    .map { BluetoothDevicePairEvent(
        device = identifier,
        bondState = when {
            it.paired && it.encrypted -> BOND_BONDED
            else -> BOND_NONE
        },
        unbondReason = -1,
    ) }