package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import com.juul.kable.Peripheral
import io.rebble.libpebblecommon.connection.PebbleBluetoothIdentifier
import io.rebble.libpebblecommon.connection.Transport.BluetoothTransport.BleTransport

actual fun peripheralFromIdentifier(transport: BleTransport): Peripheral? {
    TODO("Not yet implemented")
}

actual suspend fun Peripheral.requestMtuNative(mtu: Int): Int {
}