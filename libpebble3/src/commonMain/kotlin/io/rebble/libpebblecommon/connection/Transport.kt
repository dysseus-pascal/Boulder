package io.rebble.libpebblecommon.connection

interface PebbleIdentifier {
    val asString: String
}

// mac address on android, uuid on ios etc
expect class PebbleBluetoothIdentifier : PebbleIdentifier {
    override val asString: String
}

expect fun String.asPebbleBluetoothIdentifier(): PebbleBluetoothIdentifier

data class PebbleSocketIdentifier(val address: String) : PebbleIdentifier {
    override val asString: String = address
}

sealed class Transport {
    abstract val name: String
    abstract val identifier: PebbleIdentifier

    sealed class BluetoothTransport : Transport() {
        abstract override val identifier: PebbleBluetoothIdentifier

        data class BtClassicTransport(
            override val identifier: PebbleBluetoothIdentifier,
            override val name: String,
        ) :
            BluetoothTransport()

        data class BleTransport(
            override val identifier: PebbleBluetoothIdentifier,
            override val name: String,
        ) :
            BluetoothTransport()
    }

    // e.g. emulator
    data class SocketTransport(
        override val identifier: PebbleSocketIdentifier,
        override val name: String,
    ) : Transport()
}