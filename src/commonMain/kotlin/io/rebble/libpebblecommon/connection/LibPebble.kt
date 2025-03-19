package io.rebble.libpebblecommon.connection

import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebbleBle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapNotNull

data class LibPebbleConfig(
    val context: AppContext,
    val bleConfig: BleConfig,
)

data class BleConfig(
    val roleReversal: Boolean,
)

typealias PebbleDevices = Flow<List<PebbleDevice>>

interface LibPebble : Scanning {
    fun init()

    val watches: PebbleDevices

    // Generally, use these. They will act on all watches (or all connected watches, if that makes
    // sense)
    suspend fun sendNotification() // calls for every known watch
    suspend fun sendPing(cookie: UInt)
    // ....
}

fun PebbleDevices.forDevice(pebbleDevice: PebbleDevice): Flow<PebbleDevice> {
    return mapNotNull { it.firstOrNull { it.transport == pebbleDevice.transport } }
}

interface Scanning {
    suspend fun startBleScan()
    suspend fun stopBleScan()
    suspend fun startClassicScan()
    suspend fun stopClassicScan()
}

// Impl

class LibPebble3(
    private val config: LibPebbleConfig,
    val ble: PebbleBle,
    val watchManager: WatchManager,
    val scanning: Scanning,
) : LibPebble, Scanning by scanning {

    override fun init() {
        ble.init()
        watchManager.init()
    }

    override val watches: StateFlow<List<PebbleDevice>> = watchManager.watches

    override suspend fun sendNotification() {
        scanning.stopClassicScan()
    }

    override suspend fun sendPing(cookie: UInt) {
        forEachConnectedWatch { sendPing(cookie) }
    }

    private suspend fun forEachConnectedWatch(block: suspend ConnectedPebbleDevice.() -> Unit) {
        watches.value.filterIsInstance<ConnectedPebbleDevice>().forEach {
            it.block()
        }
    }

    companion object {
        fun create(config: LibPebbleConfig): LibPebble {
            // All the singletons
            val ble = PebbleBle(config)
            val pebbleConnector = PebbleConnector(ble)
            val watchManager = WatchManager(pebbleConnector)
            val scanning = RealScanning(watchManager, ble)
            return LibPebble3(config, ble, watchManager, scanning)
        }
    }
}