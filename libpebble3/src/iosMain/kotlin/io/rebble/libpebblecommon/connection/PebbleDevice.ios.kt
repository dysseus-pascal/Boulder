package io.rebble.libpebblecommon.connection

import kotlin.uuid.Uuid

// mac address on android, uuid on ios etc
actual data class PebbleBleIdentifier(
    val uuid: Uuid,
) : PebbleIdentifier {
    actual override val asString: String = uuid.toString()
}

actual fun String.asPebbleBleIdentifier(): PebbleBleIdentifier {
    return PebbleBleIdentifier(Uuid.parse(this))
}
