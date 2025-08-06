package io.rebble.libpebblecommon.connection

// mac address on android, uuid on ios etc
actual class PebbleBleIdentifier(id: String) : PebbleIdentifier {
    actual override val asString: String = id
}

actual fun String.asPebbleBleIdentifier(): PebbleBleIdentifier {
    return PebbleBleIdentifier(this)
}