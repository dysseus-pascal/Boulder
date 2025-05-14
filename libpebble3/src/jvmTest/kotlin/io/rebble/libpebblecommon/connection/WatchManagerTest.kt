package io.rebble.libpebblecommon.connection

import com.juul.kable.Peripheral
import io.rebble.libpebblecommon.connection.bt.BluetoothState
import io.rebble.libpebblecommon.connection.bt.BluetoothStateProvider
import io.rebble.libpebblecommon.database.dao.KnownWatchDao
import io.rebble.libpebblecommon.database.entity.KnownWatchItem
import io.rebble.libpebblecommon.di.ConnectionScope
import io.rebble.libpebblecommon.di.ConnectionScopeFactory
import io.rebble.libpebblecommon.di.ConnectionScopeProperties
import io.rebble.libpebblecommon.di.HackyProvider
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import org.koin.core.Koin
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
        override val pebbleConnector: PebbleConnector
    ) : ConnectionScope {
        override fun close() {
        }
    }
    private val transport = Transport.SocketTransport(PebbleSocketIdentifier("addr"), "name")

    private var activeConnections = 0

    inner class TestPebbleConnector :  PebbleConnector {
        private val _disconnected = CompletableDeferred<Unit>()

        fun onDisconnection() {
            activeConnections--
            _disconnected.complete(Unit)
        }

        override suspend fun connect() {
            activeConnections++
            if (activeConnections > 1) {
                throw IllegalStateException("too many active connections!")
            }
        }

        override fun disconnect() {
        }

        override val disconnected: Deferred<Unit> = _disconnected
        override val state: StateFlow<ConnectingPebbleState>
            get() = MutableStateFlow(ConnectingPebbleState.Connecting(transport))
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
    }
    private val scanning = object : Scanning {
        override suspend fun startBleScan() {
        }

        override suspend fun stopBleScan() {
        }

        override suspend fun startClassicScan() {
        }

        override suspend fun stopClassicScan() {
        }
    }
    private val watchConfig = WatchConfig(multipleConnectedWatchesSupported = false)

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
        )
    }

    @Test
    fun happyCase() = runTest(timeout = 5.seconds) {
        val watchManager = create(backgroundScope)
        val scanResult = PebbleScanResult(transport, 0, null)
        watchManager.init()
        yield()
        watchManager.addScanResult(scanResult)
        watchManager.requestConnection(transport)
        watchManager.watches.first { it.any { it is ConnectingPebbleDevice } }
    }
}