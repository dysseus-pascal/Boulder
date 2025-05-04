package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import com.juul.kable.AndroidPeripheral
import com.juul.kable.Peripheral
import io.rebble.libpebblecommon.connection.PebbleBluetoothIdentifier
import io.rebble.libpebblecommon.connection.Transport.BluetoothTransport.BleTransport

actual fun peripheralFromIdentifier(transport: BleTransport): Peripheral?
 = Peripheral(transport.identifier.macAddress)

actual suspend fun Peripheral.requestMtuNative(mtu: Int): Int {
    if (this is AndroidPeripheral) {
        return this.requestMtu(mtu)
    }
    throw IllegalStateException("Not an AndroidPeripheral")
}