package io.rebble.libpebblecommon.connection.bt

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.Transport
import io.rebble.libpebblecommon.connection.Transport.BluetoothTransport
import kotlinx.coroutines.flow.Flow

expect fun isBonded(transport: BluetoothTransport): Boolean

expect fun createBond(transport: BluetoothTransport): Boolean

class BluetoothDevicePairEvent(val address: String, val bondState: Int, val unbondReason: Int?)

expect fun getBluetoothDevicePairEvents(context: AppContext, transport: BluetoothTransport): Flow<BluetoothDevicePairEvent>
