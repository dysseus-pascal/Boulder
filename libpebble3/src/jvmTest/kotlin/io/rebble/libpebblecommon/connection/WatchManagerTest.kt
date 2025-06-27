package io.rebble.libpebblecommon.connection

import io.rebble.libpebblecommon.WatchConfig
import io.rebble.libpebblecommon.asFlow
import io.rebble.libpebblecommon.connection.bt.BluetoothState
import io.rebble.libpebblecommon.connection.bt.BluetoothStateProvider
import io.rebble.libpebblecommon.database.dao.KnownWatchDao
import io.rebble.libpebblecommon.database.entity.KnownWatchItem
import io.rebble.libpebblecommon.di.ConnectionScope
import io.rebble.libpebblecommon.di.ConnectionScopeFactory
import io.rebble.libpebblecommon.di.ConnectionScopeProperties
import io.rebble.libpebblecommon.di.HackyProvider
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.web.FirmwareUpdateManager
import io.rebble.libpebblecommon.web.LockerModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.datetime.Clock
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WatchManagerTest {
    private val knownWatchDao = object : KnownWatchDao {
        override suspend fun insertOrUpdate(watch: KnownWatchItem) {
        }

        override suspend fun knownWatches(): List<KnownWatchItem> {
            return emptyList()
        }

        override suspend fun remove(transportIdentifier: String) {
        }
    }
    private val pebbleDeviceFactory = PebbleDeviceFactory()
    private val createPlatformIdentifier = object : CreatePlatformIdentifier {
        override fun identifier(transport: Transport): PlatformIdentifier {
            return PlatformIdentifier.SocketPlatformIdentifier("addr")
        }
    }
    class TestConnectionScope(
        override val transport: Transport,
        override val pebbleConnector: PebbleConnector,
        override val closed: AtomicBoolean = AtomicBoolean(false),
    ) : ConnectionScope {
        override fun close() {
        }
    }
    private val transport = Transport.SocketTransport(PebbleSocketIdentifier("addr"), "name")

    private var activeConnections = 0
    private var connectSuccess = false
    private var exceededMax = false

    inner class TestPebbleConnector :  PebbleConnector {
        private val _disconnected = CompletableDeferred<Unit>()
        private val _state = MutableStateFlow<ConnectingPebbleState>(ConnectingPebbleState.Inactive(transport))

        fun onDisconnection() {
            activeConnections--
            _disconnected.complete(Unit)
        }

        override suspend fun connect(previouslyConnected: Boolean) {
            activeConnections++
            if (activeConnections > 1) {
                exceededMax = true
                throw IllegalStateException("too many active connections!")
            }
            _state.value = ConnectingPebbleState.Connecting(transport)
            delay(1.milliseconds)
            _state.value = ConnectingPebbleState.Negotiating(transport)
            delay(1.milliseconds)
            if (connectSuccess) {
                _state.value = ConnectingPebbleState.Connected.ConnectedNotInPrf(
                    transport = transport,
                    watchInfo = TODO(),
                    services = TODO(),
                )
            } else {
                _state.value = ConnectingPebbleState.Failed(transport)
                onDisconnection()
            }
        }

        override fun disconnect() {
            _state.value = ConnectingPebbleState.Inactive(transport)
            onDisconnection()
        }

        override val disconnected: WasDisconnected = WasDisconnected(_disconnected)
        override val state: StateFlow<ConnectingPebbleState> = _state.asStateFlow()
    }
    private val connectionScopeFactory = object : ConnectionScopeFactory {
        override fun createScope(props: ConnectionScopeProperties): ConnectionScope {
            return TestConnectionScope(props.transport, TestPebbleConnector())
        }
    }
    private val bluetoothStateProvider = object : BluetoothStateProvider {
        override fun init() {
        }

        override val state: StateFlow<BluetoothState> = MutableStateFlow(BluetoothState.Enabled).asStateFlow()
    }
    private val companionDevice = object : CompanionDevice {
        override suspend fun registerDevice(transport: Transport) {
        }

        override val companionAccessGranted: SharedFlow<Unit>
            get() = MutableSharedFlow()
        override val notificationAccessGranted: SharedFlow<Unit> = MutableSharedFlow()
    }
    private val scanning = object : Scanning {
        override val bluetoothEnabled: StateFlow<BluetoothState>
            get() = TODO("Not yet implemented")

        override suspend fun startBleScan() {
        }

        override suspend fun stopBleScan() {
        }

        override suspend fun startClassicScan() {
        }

        override suspend fun stopClassicScan() {
        }
    }
    private val watchConfig = WatchConfig(multipleConnectedWatchesSupported = false).asFlow()
    private val webServices = object : WebServices {
        override suspend fun fetchLocker(): LockerModel? {
            TODO("Not yet implemented")
        }
        override suspend fun checkForFirmwareUpdate(watch: WatchInfo): FirmwareUpdateCheckResult? {
            TODO("Not yet implemented")
        }

        override suspend fun uploadMemfaultChunk(chunk: ByteArray, watchInfo: WatchInfo) {
            TODO("Not yet implemented")
        }
    }
    private val firmwareUpdateManager = FirmwareUpdateManager(webServices)

    private fun create(scope: CoroutineScope): WatchManager {
        val libPebbleCoroutineScope = LibPebbleCoroutineScope(scope.coroutineContext)
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
            firmwareUpdateManager = firmwareUpdateManager,
            clock = Clock.System,
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
        val scanResult = PebbleScanResult(transport, 0, null)
        watchManager.init()
        yield()
        watchManager.addScanResult(scanResult)
        watchManager.requestConnection(transport)
        for (i in 1..20) {
            watchManager.watches.first { it.any { it is ConnectingPebbleDevice } }
            watchManager.watches.first { it.any { it !is ActiveDevice } }
        }
        assertFalse(exceededMax)
    }
}