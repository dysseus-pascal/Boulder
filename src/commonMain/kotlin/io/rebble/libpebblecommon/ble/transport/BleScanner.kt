package io.rebble.libpebblecommon.ble.transport

import io.rebble.libpebblecommon.ble.pebble.ScannedPebbleDevice
import kotlinx.coroutines.flow.Flow

interface BleScanner {
    suspend fun scan(namePrefix: String): Flow<ScannedPebbleDevice>
}