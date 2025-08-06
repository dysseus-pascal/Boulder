package io.rebble.libpebblecommon.connection

import io.rebble.libpebblecommon.WatchConfig
import io.rebble.libpebblecommon.asFlow
import io.rebble.libpebblecommon.connection.bt.BluetoothState
import io.rebble.libpebblecommon.connection.bt.BluetoothStateProvider
import io.rebble.libpebblecommon.connection.bt.ble.BlePlatformConfig
import io.rebble.libpebblecommon.connection.bt.ble.pebble.BatteryWatcher
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater
import io.rebble.libpebblecommon.database.dao.KnownWatchDao
import io.rebble.libpebblecommon.database.entity.KnownWatchItem
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.di.ConnectionScope
import io.rebble.libpebblecommon.di.ConnectionScopeFactory
import io.rebble.libpebblecommon.di.ConnectionScopeProperties
import io.rebble.libpebblecommon.di.HackyProvider
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.web.FirmwareUpdateManager
import io.rebble.libpebblecommon.web.LockerModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.datetime.Clock
import kotlinx.io.files.Path
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class WatchManagerTest {
    private val knownWatchDao = object : KnownWatchDao {
        override suspend fun insertOrUpdate(watch: KnownWatchItem) {
        }

        override suspend fun knownWatches(): List<KnownWatchItem> {
            return emptyList()
        }

        override suspend fun remove(transportIdentifier: String) {
        }

        override suspend fun setNickname(identifier: String, nickname: String?) {
        }
    }
    private val pebbleDeviceFactory = PebbleDeviceFactory()
    private val createPlatformIdentifier = object : CreatePlatformIdentifier {
        override fun identifier(identifier: PebbleIdentifier, name: String): PlatformIdentifier {
            return PlatformIdentifier.SocketPlatformIdentifier("addr")
        }
    }

    class TestConnectionScope(
        override val identifier: PebbleIdentifier,
        override val pebbleConnector: PebbleConnector,
        override val closed: AtomicBoolean = AtomicBoolean(false),
        override val firmwareUpdateManager: FirmwareUpdateManager,
        override val firmwareUpdater: FirmwareUpdater,
        override val batteryWatcher: BatteryWatcher,
    ) : ConnectionScope {
        override fun close() {
        }
    }

    private val identifier = "addr".asPebbleBleIdentifier()
    private val name = "name"

    private var activeConnections = 0
    private var totalConnections = 0
    private var connectSuccess = false
    private var exceededMax = false

    inner class TestPebbleConnector : PebbleConnector {
        private val _disconnected = CompletableDeferred<Unit>()
        private val _state =
            MutableStateFlow<ConnectingPebbleState>(ConnectingPebbleState.Inactive(identifier))

        fun onDisconnection() {
            activeConnections--
            _disconnected.complete(Unit)
        }

        override suspend fun connect(previouslyConnected: Boolean) {
            activeConnections++
            totalConnections++
            if (activeConnections > 1) {
                exceededMax = true
                throw IllegalStateException("too many active connections!")
            }
            _state.value = ConnectingPebbleState.Connecting(identifier)
            delay(1.milliseconds)
            _state.value = ConnectingPebbleState.Negotiating(identifier)
            delay(1.milliseconds)
            if (connectSuccess) {
                _state.value = ConnectingPebbleState.Connected.ConnectedNotInPrf(
                    identifier = identifier,
                    watchInfo = TODO(),
                    services = TODO(),
                )
            } else {
                _state.value = ConnectingPebbleState.Failed(identifier)
                onDisconnection()
            }
        }

        override fun disconnect() {
            _state.value = ConnectingPebbleState.Inactive(identifier)
            onDisconnection()
        }

        override val disconnected: WasDisconnected = WasDisconnected(_disconnected)
        override val state: StateFlow<ConnectingPebbleState> = _state.asStateFlow()
    }

    private val firmwareUpdateManager = object : FirmwareUpdateManager {
        override fun init(watchInfo: WatchInfo) {
        }

        override fun checkForUpdates() {
        }

        override val availableUpdates: Flow<FirmwareUpdateCheckResult?>
            get() = MutableStateFlow(null)

    }
    private val firmwareUpdater = object : FirmwareUpdater {
        override val firmwareUpdateState: StateFlow<FirmwareUpdater.FirmwareUpdateStatus>
            = MutableStateFlow(FirmwareUpdater.FirmwareUpdateStatus.NotInProgress.Idle)

        override fun setPlatform(watchPlatform: WatchHardwarePlatform) {}

        override fun sideloadFirmware(path: Path) {}

        override fun updateFirmware(update: FirmwareUpdateCheckResult) {}

        override fun checkforFirmwareUpdate() {}
    }
    private val bluetoothStateProvider = object : BluetoothStateProvider {
        override fun init() {
        }

        override val state: StateFlow<BluetoothState> =
            MutableStateFlow(BluetoothState.Enabled).asStateFlow()
    }
    private val companionDevice = object : CompanionDevice {
        override suspend fun registerDevice(
            identifier: PebbleIdentifier,
            uiContext: UIContext?
        ): Boolean {
            return true
        }


        override val companionAccessGranted: SharedFlow<Unit>
            get() = MutableSharedFlow()
        override val notificationAccessGranted: SharedFlow<Unit> = MutableSharedFlow()
    }
    private val scanning = object : Scanning {
        override val bluetoothEnabled: StateFlow<BluetoothState>
            get() = TODO("Not yet implemented")
        override val isScanningBle: StateFlow<Boolean>
            get() = TODO("Not yet implemented")

        override fun startBleScan() {
        }

        override fun stopBleScan() {
        }

        override fun startClassicScan() {
        }

        override fun stopClassicScan() {
        }
    }
    private val watchConfig = WatchConfig(multipleConnectedWatchesSupported = false).asFlow()
    private val webServices = object : WebServices {
        override suspend fun fetchLocker(): LockerModel? {
            TODO("Not yet implemented")
        }

        override suspend fun removeFromLocker(id: Uuid): Boolean {
            TODO("Not yet implemented")
        }

        override suspend fun checkForFirmwareUpdate(watch: WatchInfo): FirmwareUpdateCheckResult? {
            TODO("Not yet implemented")
        }

        override suspend fun uploadMemfaultChunk(chunk: ByteArray, watchInfo: WatchInfo) {
            TODO("Not yet implemented")
        }
    }
    private val blePlatformConfig =
        BlePlatformConfig(delayBleConnectionsAfterAppStart = false, delayBleDisconnections = false)

    private fun create(scope: CoroutineScope): WatchManager {
        val libPebbleCoroutineScope = LibPebbleCoroutineScope(scope.coroutineContext)
        val connectionCoroutineScope = ConnectionCoroutineScope(scope.coroutineContext)
        val batteryWatcher = BatteryWatcher(connectionCoroutineScope)
        val connectionScopeFactory = object : ConnectionScopeFactory {
            override fun createScope(props: ConnectionScopeProperties): ConnectionScope {
                return TestConnectionScope(
                    identifier = props.identifier,
                    pebbleConnector = TestPebbleConnector(),
                    firmwareUpdateManager = firmwareUpdateManager,
                    firmwareUpdater = firmwareUpdater,
                    batteryWatcher = batteryWatcher,
                )
            }
        }
        return WatchManager(
            knownWatchDao = knownWatchDao,
            pebbleDeviceFactory = pebbleDeviceFactory,
            createPlatformIdentifier = createPlatformIdentifier,
            connectionScopeFactory = connectionScopeFactory,
            libPebbleCoroutineScope = libPebbleCoroutineScope,
            bluetoothStateProvider = bluetoothStateProvider,
            companionDevice = companionDevice,
            scanning = HackyProvider { scanning },
            watchConfig = watchConfig,
            clock = Clock.System,
            blePlatformConfig = blePlatformConfig,
        )
    }

//    @Test
//    fun happyCase() = runTest(timeout = 5.seconds) {
//        val watchManager = create(backgroundScope)
//        val scanResult = PebbleScanResult(transport, 0, null)
//        watchManager.init()
//        yield()
//        watchManager.addScanResult(scanResult)
//        watchManager.requestConnection(transport)
//        watchManager.watches.first { it.any { it is ConnectingPebbleDevice } }
//        watchManager.watches.first { it.any { it is ConnectedPebbleDevice } }
//        assertFalse(exceededMax)
//    }

    @Test
    fun repeatConnections() = runTest(timeout = 5.seconds) {
        val watchManager = create(backgroundScope)
        val scanResult = PebbleScanResult(identifier, name, 0, null)
        watchManager.init()
        yield()
        watchManager.addScanResult(scanResult)
        watchManager.requestConnection(identifier, null)
        for (i in 1..20) {
            watchManager.watches.first { totalConnections >= i && it.any { it is ConnectingPebbleDevice } }
        }
        assertFalse(exceededMax)
    }
}
