package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import com.juul.kable.Identifier
import com.juul.kable.Peripheral

actual fun peripheralFromIdentifier(identifier: Identifier): Peripheral
 = Peripheral(identifier)
