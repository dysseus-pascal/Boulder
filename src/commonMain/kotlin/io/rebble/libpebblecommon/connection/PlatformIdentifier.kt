package io.rebble.libpebblecommon.connection

import com.juul.kable.Peripheral
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.peripheralFromIdentifier

sealed class PlatformIdentifier {
    class BlePlatformIdentifier(val peripheral: Peripheral) : PlatformIdentifier()
}

class CreatePlatformIdentifier {
    fun identifier(transport: Transport): PlatformIdentifier? = when (transport) {
        is Transport.BluetoothTransport.BleTransport -> peripheralFromIdentifier(transport)?.let {
            PlatformIdentifier.BlePlatformIdentifier(
                it
            )
        }

        else -> TODO()
    }
}