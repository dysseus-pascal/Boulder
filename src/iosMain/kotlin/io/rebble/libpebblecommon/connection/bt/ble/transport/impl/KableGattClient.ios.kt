package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import co.touchlab.kermit.Logger
import com.juul.kable.Peripheral
import com.juul.kable.logs.Logging
import io.rebble.libpebblecommon.connection.PebbleBluetoothIdentifier

actual fun peripheralFromIdentifier(identifier: PebbleBluetoothIdentifier): Peripheral? = try {
    Peripheral(identifier.uuid) {
        logging {
//            level = Logging.Level.Data
        }
    }
} catch (e: NoSuchElementException) {
    Logger.w("ios periopheral not found: $identifier")
    null
}