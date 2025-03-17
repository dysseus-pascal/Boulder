package io.rebble.libpebblecommon.connection

import android.bluetooth.BluetoothDevice

actual class PebbleIdentifier(
    val macAddress: String,
) {
    actual fun asString(): String = macAddress

    fun isEqualTo(device: BluetoothDevice) = device.address.equals(macAddress, ignoreCase = true)
}