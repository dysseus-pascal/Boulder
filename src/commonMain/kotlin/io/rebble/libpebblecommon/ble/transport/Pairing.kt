package io.rebble.libpebblecommon.ble.transport

import io.rebble.libpebblecommon.ble.pebble.AppContext
import io.rebble.libpebblecommon.ble.pebble.ScannedPebbleDevice
import kotlinx.coroutines.flow.Flow

expect fun isBonded(scannedPebbleDevice: ScannedPebbleDevice): Boolean

expect fun createBond(scannedPebbleDevice: ScannedPebbleDevice): Boolean

class BluetoothDevicePairEvent(val address: String, val bondState: Int, val unbondReason: Int?)

expect fun getBluetoothDevicePairEvents(context: AppContext, address: String): Flow<BluetoothDevicePairEvent>
