package io.rebble.libpebblecommon.connection

import kotlinx.coroutines.flow.Flow

data class LibPebbleConfig(
    val context: AppContext,
    val bleConfig: BleConfig,
)

data class BleConfig(
    val roleReversal: Boolean,
)

interface LibPebble {
    val watches: Flow<List<PebbleDevice>>

    suspend fun bleScan()
    suspend fun classicScan()

    suspend fun connect(watch: PebbleDevice)

    // Generally, use these. They will act on all watches (or all connected watches, if that makes
    // sense)
    suspend fun sendNotification() // calls for every known watch
    suspend fun sendPing()
    // ....
}

// Impl

//class LibPebble3(
//    private val config: LibPebbleConfig,
//) : LibPebble {
//
//}