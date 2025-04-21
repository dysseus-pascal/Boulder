package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebbleBle
import io.rebble.libpebblecommon.di.initKoin
import io.rebble.libpebblecommon.disk.pbw.PbwApp
import io.rebble.libpebblecommon.notification.initPlatformNotificationListener
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.time.TimeChanged
import io.rebble.libpebblecommon.web.LockerModel
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import org.koin.core.Koin
import kotlin.uuid.Uuid

data class LibPebbleConfig(
    val context: AppContext,
    val bleConfig: BleConfig,
    val webServices: WebServices,
)

data class BleConfig(
    val reversedPPoG: Boolean,
    val pinAddress: Boolean,
    val phoneRequestsPairing: Boolean,
    val writeConnectivityTrigger: Boolean,
)

typealias PebbleDevices = Flow<List<PebbleDevice>>

interface LibPebble : Scanning, RequestSync {
    fun init()

    val watches: PebbleDevices

    // Generally, use these. They will act on all watches (or all connected watches, if that makes
    // sense)
    suspend fun sendNotification(notification: TimelineItem) // calls for every known watch
    suspend fun deleteNotification(itemId: Uuid)
    suspend fun sendPing(cookie: UInt)
    suspend fun sideloadApp(pbwPath: Path)
    // ....
}

interface WebServices {
    suspend fun fetchLocker(): LockerModel?
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

interface RequestSync {
    fun requestLockerSync()
}

// Impl

class LibPebble3(
    private val config: LibPebbleConfig,
    private val watchManager: WatchManager,
    private val scanning: Scanning,
    private val locker: Locker,
    private val timeChanged: TimeChanged,
    private val webSyncManager: RequestSync,
) : LibPebble, Scanning by scanning, RequestSync by webSyncManager {
    private val logger = Logger.withTag("LibPebble3")

    override fun init() {
        PebbleBle.init(config)
        watchManager.init()
        locker.init()
        timeChanged.registerForTimeChanges {
            logger.d("Time changed")
            GlobalScope.launch { forEachConnectedWatch { updateTime() } }
        }
    }

    override val watches: StateFlow<List<PebbleDevice>> = watchManager.watches

    override suspend fun sendNotification(notification: TimelineItem) {
        forEachConnectedWatch { sendNotification(notification) }
    }

    override suspend fun deleteNotification(itemId: Uuid) {
        forEachConnectedWatch { deleteNotification(itemId) }
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
        private lateinit var koin: Koin

        fun create(config: LibPebbleConfig): LibPebble {
            koin = initKoin(config)
            val libPebble = koin.get<LibPebble>()
            initPlatformNotificationListener(config.context, GlobalScope, libPebble)
            return libPebble
        }
    }
}