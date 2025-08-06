package io.rebble.libpebblecommon.connection

interface PebbleIdentifier {
    val asString: String
}

// mac address on android, uuid on ios etc
expect class PebbleBleIdentifier : PebbleIdentifier {
    override val asString: String
}

expect fun String.asPebbleBleIdentifier(): PebbleBleIdentifier

data class PebbleSocketIdentifier(val address: String) : PebbleIdentifier {
    override val asString: String = address
}
