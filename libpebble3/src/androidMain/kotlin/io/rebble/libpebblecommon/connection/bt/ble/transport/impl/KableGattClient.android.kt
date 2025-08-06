package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import com.juul.kable.AndroidPeripheral
import com.juul.kable.Peripheral
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier

actual fun peripheralFromIdentifier(identifier: PebbleBleIdentifier, name: String): Peripheral?
 = Peripheral(identifier.macAddress)

actual suspend fun Peripheral.requestMtuNative(mtu: Int): Int {
    if (this is AndroidPeripheral) {
        return this.requestMtu(mtu)
    }
    throw IllegalStateException("Not an AndroidPeripheral")
}