package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import com.juul.kable.Filter
import com.juul.kable.Scanner
import io.rebble.libpebblecommon.connection.bt.ble.pebble.ScannedPebbleDevice
import io.rebble.libpebblecommon.connection.bt.ble.transport.BleScanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

fun kableBleScanner(): BleScanner = KableBleScanner()

class KableBleScanner : BleScanner {
    override suspend fun scan(namePrefix: String): Flow<ScannedPebbleDevice> {
        return Scanner {
            filters {
                match {
                    name = Filter.Name.Prefix(namePrefix)
                }
            }
        }.advertisements
            .map { ScannedPebbleDevice(it.identifier.toString()) }
    }
}
