package io.rebble.libpebblecommon.connection.bt.ble.transport

import io.rebble.libpebblecommon.connection.BleDiscoveredPebbleDevice
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.kableBleScanner
import kotlinx.coroutines.flow.Flow

//expect fun libpebbleBleScanner(): BleScanner

fun bleScanner(): BleScanner
 = kableBleScanner()
// = libpebbleBleScanner()

interface BleScanner {
    suspend fun scan(namePrefix: String): Flow<BleDiscoveredPebbleDevice>
}
