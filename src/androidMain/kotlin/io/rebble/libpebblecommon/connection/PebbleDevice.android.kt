package io.rebble.libpebblecommon.connection

import java.util.Locale

actual data class PebbleBluetoothIdentifier private constructor(
    val macAddress: String,
) : PebbleIdentifier {
    actual override val asString: String = macAddress

    init {
        check(macAddress == macAddress.uppercase(Locale.US))
    }

    companion object {
        // Force address to always be uppercase (so we can safely compare it)
        operator fun invoke(macAddress: String): PebbleBluetoothIdentifier {
            return PebbleBluetoothIdentifier(macAddress.uppercase(Locale.US))
        }
    }
}

actual fun String.asPebbleBluetoothIdentifier(): PebbleBluetoothIdentifier {
    return PebbleBluetoothIdentifier(this)
}
