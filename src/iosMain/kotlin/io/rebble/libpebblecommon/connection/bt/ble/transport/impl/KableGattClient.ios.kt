package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import com.juul.kable.Peripheral
import com.juul.kable.logs.Logging
import io.rebble.libpebblecommon.connection.PebbleBluetoothIdentifier

actual fun peripheralFromIdentifier(identifier: PebbleBluetoothIdentifier): Peripheral =
    Peripheral(identifier.uuid) {
        logging {
//            level = Logging.Level.Data
        }
    }