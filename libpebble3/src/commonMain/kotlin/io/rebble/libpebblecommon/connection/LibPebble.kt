package io.rebble.libpebblecommon.connection

import androidx.compose.ui.graphics.ImageBitmap
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.calendar.PhoneCalendarSyncer
import io.rebble.libpebblecommon.connection.bt.BluetoothStateProvider
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.DEFAULT_MTU
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.MAX_RX_WINDOW
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.MAX_TX_WINDOW
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattServerManager
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.ActionOverrides
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.CustomTimelineActionHandler
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.database.entity.NotificationAppItem
import io.rebble.libpebblecommon.database.entity.TimelineNotification
import io.rebble.libpebblecommon.database.entity.TimelineNotificationDao
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.di.initKoin
import io.rebble.libpebblecommon.notification.NotificationListenerConnection
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
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
    val watchConfig: WatchConfig,
)

data class WatchConfig(
    val multipleConnectedWatchesSupported: Boolean,
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
    val sendPpogResetOnDisconnection: Boolean,
)

data class PhoneCapabilities(val capabilities: Set<ProtocolCapsFlag>)

typealias PebbleDevices = StateFlow<List<PebbleDevice>>

interface LibPebble : Scanning, RequestSync, LockerApi, NotificationApps {
    fun init()

    val watches: PebbleDevices

    // Generally, use these. They will act on all watches (or all connected watches, if that makes
    // sense)
    suspend fun sendNotification(notification: TimelineNotification, actionHandlers: Map<UByte, CustomTimelineActionHandler>? = null)
    suspend fun deleteNotification(itemId: Uuid)
    suspend fun sendPing(cookie: UInt)
    suspend fun launchApp(uuid: Uuid)
    // ....
}

interface WebServices {
    suspend fun fetchLocker(): LockerModel?
}

fun PebbleDevices.forDevice(transport: Transport): Flow<PebbleDevice> {
    return mapNotNull { it.firstOrNull { it.transport == transport } }
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
    fun getLocker(): Flow<List<LockerEntry>>
}

interface NotificationApps {
    val notificationApps: Flow<List<NotificationAppItem>>
    fun updateNotificationAppMuteState(packageName: String, muteState: MuteState)
    fun updateNotificationChannelMuteState(
        packageName: String,
        channelId: String,
        muteState: MuteState,
    )

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
    private val notificationApi: NotificationApps,
    private val timelineNotificationsDao: TimelineNotificationDao,
    private val actionOverrides: ActionOverrides,
    private val phoneCalendarSyncer: PhoneCalendarSyncer,
) : LibPebble, Scanning by scanning, RequestSync by webSyncManager, LockerApi by locker,
    NotificationApps by notificationApi {
    private val logger = Logger.withTag("LibPebble3")

    override fun init() {
        bluetoothStateProvider.init()
        gattServerManager.init()
        watchManager.init()
        phoneCalendarSyncer.init()
        notificationListenerConnection.init(this)
        timeChanged.registerForTimeChanges {
            logger.d("Time changed")
            libPebbleCoroutineScope.launch { forEachConnectedWatch { updateTime() } }
        }
    }

    override val watches: StateFlow<List<PebbleDevice>> = watchManager.watches

    override suspend fun sendNotification(notification: TimelineNotification, actionHandlers: Map<UByte, CustomTimelineActionHandler>?) {
        timelineNotificationsDao.insertOrReplace(notification)
        actionHandlers?.let { actionOverrides.setActionHandlers(notification.itemId, actionHandlers) }
    }

    override suspend fun deleteNotification(itemId: Uuid) {
        timelineNotificationsDao.markForDeletion(itemId)
        actionOverrides.setActionHandlers(itemId, emptyMap())
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