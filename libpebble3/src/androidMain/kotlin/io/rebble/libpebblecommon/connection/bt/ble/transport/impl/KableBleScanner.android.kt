package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import com.juul.kable.Identifier
import io.rebble.libpebblecommon.connection.PebbleBluetoothIdentifier

actual fun Identifier.asPebbleBluetoothIdentifier(): PebbleBluetoothIdentifier
 = PebbleBluetoothIdentifier(macAddress = toString())