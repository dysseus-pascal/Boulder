package io.rebble.libpebblecommon.connection

import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebbleBle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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

    // Generally, use these. They will act on all watches (or all connected watches, if that makes
    // sense)
    suspend fun sendNotification() // calls for every known watch
    suspend fun sendPing(cookie: UInt)
    // ....
}

// Impl

class LibPebble3(
    private val config: LibPebbleConfig,
) : LibPebble {
    private val ble = PebbleBle(config)
    private val pebbleConnector = PebbleConnector(ble)
    private val watchManager = WatchManager(pebbleConnector)
    private var bleScanJob: Job? = null

    fun init() {
        ble.init()
        watchManager.init()
    }

    override val watches: StateFlow<List<PebbleDevice>> = watchManager.watches

    override suspend fun bleScan() {
        bleScanJob?.cancel()
        val scanResults = ble.scan(watchManager)
        bleScanJob = GlobalScope.launch {
            scanResults.collect {
                watchManager.addScanResult(it)
                bleScanJob?.cancel() // FIXME find a proper way to stop scan (especially when connecting)
            }
        }
    }

    override suspend fun classicScan() {
        TODO("Not yet implemented")
    }

    override suspend fun sendNotification() {
        TODO("Not yet implemented")
    }

    override suspend fun sendPing(cookie: UInt) {
        watches.value.filterIsInstance<ConnectedPebbleDevice>().forEach {
            it.sendPing(cookie)
        }
    }
}