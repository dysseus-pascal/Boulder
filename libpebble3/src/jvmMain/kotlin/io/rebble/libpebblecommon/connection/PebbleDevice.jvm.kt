package io.rebble.libpebblecommon.connection

// mac address on android, uuid on ios etc
actual class PebbleBluetoothIdentifier : PebbleIdentifier {
    actual override val asString: String
        get() = TODO("Not yet implemented")
}

actual fun String.asPebbleBluetoothIdentifier(): PebbleBluetoothIdentifier {
    TODO("Not yet implemented")
}