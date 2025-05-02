package io.rebble.libpebblecommon.connection

import androidx.compose.ui.graphics.ImageBitmap
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.bt.BluetoothStateProvider
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.DEFAULT_MTU
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.MAX_RX_WINDOW
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.MAX_TX_WINDOW
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattServerManager
import io.rebble.libpebblecommon.database.entity.LockerEntryWithPlatforms
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.database.entity.NotificationAppEntity
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.di.initKoin
import io.rebble.libpebblecommon.notification.NotificationApi
import io.rebble.libpebblecommon.notification.NotificationListenerConnection
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.time.TimeChanged
import io.rebble.libpebblecommon.web.LockerModel
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
    val initialMtu: Int = DEFAULT_MTU,
    val desiredTxWindow: Int = MAX_TX_WINDOW,
    val desiredRxWindow: Int = MAX_RX_WINDOW,
    val useNativeMtu: Boolean,
)

data class PhoneCapabilities(val capabilities: Set<ProtocolCapsFlag>)

typealias PebbleDevices = StateFlow<List<PebbleDevice>>

interface LibPebble : Scanning, RequestSync, LockerApi, NotificationApps {
    fun init()

    val watches: PebbleDevices

    // Generally, use these. They will act on all watches (or all connected watches, if that makes
    // sense)
    suspend fun sendNotification(notification: TimelineItem) // calls for every known watch
    suspend fun deleteNotification(itemId: Uuid)
    suspend fun sendPing(cookie: UInt)
    suspend fun launchApp(uuid: Uuid)
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

interface LockerApi {
    suspend fun sideloadApp(pbwPath: Path)
    fun getLocker(): Flow<List<LockerEntryWithPlatforms>>
}

interface NotificationApps {
    val notificationApps: Flow<List<NotificationAppEntity>>
    fun updateNotificationAppMuteState(packageName: String, muteState: MuteState)
    fun syncAppsFromOS()
    /** Will only return a value on Android */
    suspend fun getAppIcon(packageName: String): ImageBitmap?
}

// Impl

class LibPebble3(
    private val watchManager: WatchManager,
    private val scanning: Scanning,
    private val locker: Locker,
    private val timeChanged: TimeChanged,
    private val webSyncManager: RequestSync,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
    private val gattServerManager: GattServerManager,
    private val bluetoothStateProvider: BluetoothStateProvider,
    private val notificationListenerConnection: NotificationListenerConnection,
    private val notificationApi: NotificationApi,
) : LibPebble, Scanning by scanning, RequestSync by webSyncManager, LockerApi by locker,
    NotificationApps by notificationApi {
    private val logger = Logger.withTag("LibPebble3")

    override fun init() {
        bluetoothStateProvider.init()
        gattServerManager.init()
        watchManager.init()
        locker.init()
        notificationListenerConnection.init(this)
        timeChanged.registerForTimeChanges {
            logger.d("Time changed")
            libPebbleCoroutineScope.launch { forEachConnectedWatch { updateTime() } }
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

    override suspend fun launchApp(uuid: Uuid) {
        forEachConnectedWatch { launchApp(uuid) }
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
            return libPebble
        }
    }
}