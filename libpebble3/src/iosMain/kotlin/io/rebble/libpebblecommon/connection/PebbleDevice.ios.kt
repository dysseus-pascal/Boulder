package io.rebble.libpebblecommon.connection

import kotlin.uuid.Uuid

// mac address on android, uuid on ios etc
actual data class PebbleBluetoothIdentifier(
    val uuid: Uuid,
) : PebbleIdentifier {
    actual override val asString: String = uuid.toString()
}

actual fun String.asPebbleBluetoothIdentifier(): PebbleBluetoothIdentifier {
    return PebbleBluetoothIdentifier(Uuid.parse(this))
}
