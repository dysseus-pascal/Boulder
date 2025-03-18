package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import com.juul.kable.Peripheral
import io.rebble.libpebblecommon.connection.PebbleBluetoothIdentifier

actual fun peripheralFromIdentifier(identifier: PebbleBluetoothIdentifier): Peripheral
 = Peripheral(identifier.macAddress)
