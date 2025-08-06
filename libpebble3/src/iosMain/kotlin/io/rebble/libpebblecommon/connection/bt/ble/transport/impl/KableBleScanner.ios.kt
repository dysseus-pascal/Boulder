package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import com.juul.kable.Identifier
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import kotlin.uuid.Uuid

actual fun Identifier.asPebbleBleIdentifier(): PebbleBleIdentifier = PebbleBleIdentifier(uuid = Uuid.parse(toString()))