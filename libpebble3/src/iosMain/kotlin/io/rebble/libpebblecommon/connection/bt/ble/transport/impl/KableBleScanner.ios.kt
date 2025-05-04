package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import com.juul.kable.Identifier
import io.rebble.libpebblecommon.connection.PebbleBluetoothIdentifier
import kotlin.uuid.Uuid

actual fun Identifier.asPebbleBluetoothIdentifier(): PebbleBluetoothIdentifier
 = PebbleBluetoothIdentifier(uuid = Uuid.parse(toString()))