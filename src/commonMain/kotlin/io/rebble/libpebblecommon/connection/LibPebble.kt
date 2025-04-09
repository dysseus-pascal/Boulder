package io.rebble.libpebblecommon.connection

import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebbleBle
import io.rebble.libpebblecommon.connection.bt.ble.transport.bleScanner
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.PlatformNotificationActionHandler
import io.rebble.libpebblecommon.database.getRoomDatabase
import io.rebble.libpebblecommon.disk.pbw.PbwApp
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.io.files.Path

data class LibPebbleConfig(
    val context: AppContext,
    val bleConfig: BleConfig,
)

data class BleConfig(
    val roleReversal: Boolean,
    val pinAddress: Boolean,
    val phoneRequestsPairing: Boolean,
    val writeConnectivityTrigger: Boolean,
)

typealias PebbleDevices = Flow<List<PebbleDevice>>

interface LibPebble : Scanning {
    fun init()

    val watches: PebbleDevices

    // Generally, use these. They will act on all watches (or all connected watches, if that makes
    // sense)
    suspend fun sendNotification(notification: TimelineItem) // calls for every known watch
    suspend fun sendPing(cookie: UInt)
    suspend fun sideloadApp(pbwPath: Path)
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
    val watchManager: WatchManager,
    val scanning: Scanning,
    val locker: Locker,
) : LibPebble, Scanning by scanning {

    override fun init() {
        PebbleBle.init(config)
        watchManager.init()
        locker.init()
    }

    override val watches: StateFlow<List<PebbleDevice>> = watchManager.watches

    override suspend fun sendNotification(notification: TimelineItem) {
        forEachConnectedWatch { sendNotification(notification) }
    }

    override suspend fun sendPing(cookie: UInt) {
        forEachConnectedWatch { sendPing(cookie) }
    }

    override suspend fun sideloadApp(pbwPath: Path) {
        locker.sideloadApp(PbwApp(pbwPath))
    }

    private suspend fun forEachConnectedWatch(block: suspend ConnectedPebbleDevice.() -> Unit) {
        watches.value.filterIsInstance<ConnectedPebbleDevice>().forEach {
            it.block()
        }
    }

    companion object {
        fun create(config: LibPebbleConfig): LibPebble {
            // All the singletons
            val database = getRoomDatabase(config.context)
            val pbwCache = StaticLockerPBWCache(config.context)
            val notifActionHandler = PlatformNotificationActionHandler(config.context)
            val watchManager = WatchManager(config, database, pbwCache, notifActionHandler)
            val bleScanner = bleScanner()
            val scanning = RealScanning(watchManager, bleScanner)
            val locker = Locker(config, watchManager, database, pbwCache, GlobalScope)
            return LibPebble3(config, watchManager, scanning, locker)
        }
    }
}