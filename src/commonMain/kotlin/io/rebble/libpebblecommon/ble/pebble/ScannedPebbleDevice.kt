package io.rebble.libpebblecommon.ble.pebble

import com.juul.kable.Advertisement

data class ScannedPebbleDevice(
    internal val device: Advertisement,
)

//expect fun Identifier.asPeripheral(): Peripheral
