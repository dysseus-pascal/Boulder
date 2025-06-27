package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.connection.bt.BluetoothState
import io.rebble.libpebblecommon.connection.bt.BluetoothStateProvider
import io.rebble.libpebblecommon.database.MillisecondInstant
import io.rebble.libpebblecommon.database.asMillisecond
import io.rebble.libpebblecommon.database.dao.KnownWatchDao
import io.rebble.libpebblecommon.database.entity.KnownWatchItem
import io.rebble.libpebblecommon.database.entity.transport
import io.rebble.libpebblecommon.database.entity.type
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.di.ConnectionScope
import io.rebble.libpebblecommon.di.ConnectionScopeFactory
import io.rebble.libpebblecommon.di.ConnectionScopeProperties
import io.rebble.libpebblecommon.di.HackyProvider
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.web.FirmwareUpdateManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.seconds

/** Everything that is persisted, not including fields that are duplicated elsewhere (e.g. goal) */
internal data class KnownWatchProperties(
    val name: String,
    val runningFwVersion: String,
    val serial: String,
    val lastConnected: MillisecondInstant?
)

internal fun WatchInfo.asWatchProperties(transport: Transport, lastConnected: MillisecondInstant?): KnownWatchProperties =
    KnownWatchProperties(
        name = transport.name,
        runningFwVersion = runningFwVersion.stringVersion,
        serial = serial,
        lastConnected = lastConnected,
    )

private fun Watch.asKnownWatchItem(): KnownWatchItem? {
    if (knownWatchProps == null) return null
    return KnownWatchItem(
        transportIdentifier = transport.identifier.asString,
        transportType = transport.type(),
        name = transport.name,
        runningFwVersion = knownWatchProps.runningFwVersion,
        serial = knownWatchProps.serial,
        connectGoal = connectGoal,
        lastConnected = knownWatchProps.lastConnected,
    )
}

interface WatchConnector {
    fun addScanResult(scanResult: PebbleScanResult)
    fun requestConnection(transport: Transport, uiContext: UIContext?)
    fun requestDisconnection(transport: Transport)
    fun clearScanResults()
    fun forget(transport: Transport)
}

private data class Watch(
    val transport: Transport,
    /** Populated (and updated with fresh rssi etc) if recently discovered */
    val scanResult: PebbleScanResult?,
    val connectGoal: Boolean,
    /** Always populated if we have previously connected to this watch */
    val knownWatchProps: KnownWatchProperties?,
    /** Populated if there is an active connection */
    val activeConnection: ConnectionScope?,
    /**
     * What is currently persisted for this watch? Only used to check whether we need to persist
     * changes.
     */
    val asPersisted: KnownWatchItem?,
    val forget: Boolean,
    val firmwareUpdateAvailable: FirmwareUpdateCheckResult?,
    // TODO can add non-persisted state here e.g. previous connection failures to manage backoff etc
) {
    init {
        check(scanResult != null || knownWatchProps != null)
    }
}

private fun KnownWatchItem.asProps(): KnownWatchProperties = KnownWatchProperties(
    name = name,
    runningFwVersion = runningFwVersion,
    serial = serial,
    lastConnected = lastConnected,
)

private data class CombinedState(
    val watches: Map<Transport, Watch>,
    val active: Map<Transport, ConnectingPebbleState>,
    val previousActive: Map<Transport, ConnectingPebbleState>,
    val btstate: BluetoothState,
)

class WatchManager(
    private val knownWatchDao: KnownWatchDao,
    private val pebbleDeviceFactory: PebbleDeviceFactory,
    private val createPlatformIdentifier: CreatePlatformIdentifier,
    private val connectionScopeFactory: ConnectionScopeFactory,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
    private val bluetoothStateProvider: BluetoothStateProvider,
    private val companionDevice: CompanionDevice,
    private val scanning: HackyProvider<Scanning>,
    private val watchConfig: WatchConfigFlow,
    private val firmwareUpdateManager: FirmwareUpdateManager,
    private val clock: Clock,
) : WatchConnector {
    private val logger = Logger.withTag("WatchManager")
    private val allWatches = MutableStateFlow<Map<Transport, Watch>>(emptyMap())
    private val _watches = MutableStateFlow<List<PebbleDevice>>(emptyList())
    val watches: StateFlow<List<PebbleDevice>> = _watches.asStateFlow()
    private val activeConnections = mutableSetOf<Transport>()
    private var connectionNum = 0

    private suspend fun loadKnownWatchesFromDb() {
        allWatches.value = knownWatchDao.knownWatches().associate {
            it.transport() to Watch(
                transport = it.transport(),
                scanResult = null,
                connectGoal = it.connectGoal,
                knownWatchProps = it.asProps(),
                activeConnection = null,
                asPersisted = it,
                forget = false,
                firmwareUpdateAvailable = null,
            )
        }
    }

    private suspend fun persistIfNeeded(
        watch: Watch,
    ) {
        if (watch.forget) {
            logger.d("Deleting $watch from db")
            knownWatchDao.remove(watch.transport)
        } else {
            val wouldPersist = watch.asKnownWatchItem()
            if (wouldPersist != null && wouldPersist != watch.asPersisted) {
                knownWatchDao.insertOrUpdate(wouldPersist)
                updateWatch(watch.transport) {
                    logger.d("Persisting changes for $wouldPersist")
                    it.copy(asPersisted = wouldPersist)
                }
            }
        }
    }

    fun init() {
        logger.d("watchmanager init()")
        libPebbleCoroutineScope.launch {
            loadKnownWatchesFromDb()
            val activeConnectionStates = allWatches.flowOfAllDevices()
            combine(
                allWatches,
                activeConnectionStates,
                bluetoothStateProvider.state,
            ) { watches, active, btstate ->
                CombinedState(watches, active, emptyMap(), btstate)
            }.scan(null as CombinedState?) { previous, current ->
                current.copy(previousActive = previous?.active ?: emptyMap())
            }.map { state ->
                // State can be null for the first scan emission
                val (watches, active, previousActive, btstate) = state ?: return@map emptyList()
                logger.v { "combine: watches=$watches / active=$active / btstate=$btstate / activeConnections=$activeConnections" }
                // Update for active connection state
                watches.values.mapNotNull { device ->
                    val transport = device.transport
                    val states = CurrentAndPreviousState(
                        previousState = previousActive[transport],
                        currentState = active[transport],
                    )
                    val hasConnectionAttempt =
                        active.containsKey(device.transport) || activeConnections.contains(device.transport)

                    persistIfNeeded(device)
                    // Removed forgotten device once it is disconnected
                    if (!hasConnectionAttempt && device.forget) {
                        logger.d("removing ${device.transport} from allWatches")
                        allWatches.update { it.minus(device.transport) }
                        return@mapNotNull null
                    }

                    if (device.connectGoal && !hasConnectionAttempt && btstate.enabled()) {
                        if (watchConfig.value.multipleConnectedWatchesSupported) {
                            connectTo(device)
                        } else {
                            if (active.isEmpty() && activeConnections.isEmpty()) {
                                connectTo(device)
                            }
                        }
                    } else if (hasConnectionAttempt && !btstate.enabled()) {
                        disconnectFrom(device.transport)
                        device.activeConnection?.cleanup()
                    } else if (!device.connectGoal && hasConnectionAttempt) {
                        disconnectFrom(device.transport)
                    }
                    // Update persisted props after connection
                    logger.v { "states=$states" }
                    if (states.currentState is ConnectingPebbleState.Connected && states.previousState !is ConnectingPebbleState.Connected) {
                        val newProps = states.currentState.watchInfo.asWatchProperties(transport, clock.now().asMillisecond())
                        if (newProps != device.knownWatchProps) {
                            updateWatch(transport) {
                                it.copy(knownWatchProps = newProps)
                            }
                        }

                        // Clear scan results after we connected to one of them
                        if (device.scanResult != null) {
                            clearScanResults()
                        }

                        updateWatch(transport) {
                            it.copy(firmwareUpdateAvailable = null)
                        }
                        libPebbleCoroutineScope.launch {
                            val update =
                                firmwareUpdateManager.checkForUpdates(states.currentState.watchInfo)
                            logger.d { "fw update available=$update" }
                            updateWatch(transport) {
                                it.copy(firmwareUpdateAvailable = update)
                            }
                        }
                    }
                    pebbleDeviceFactory.create(
                        transport = transport,
                        state = states.currentState,
                        watchConnector = this@WatchManager,
                        scanResult = device.scanResult,
                        knownWatchProperties = device.knownWatchProps,
                        connectGoal = device.connectGoal,
                        firmwareUpdateAvailable = device.firmwareUpdateAvailable,
                    )
                }
            }.collect {
                _watches.value = it.also { logger.v("watches: $it") }
            }
        }
    }

    override fun addScanResult(scanResult: PebbleScanResult) {
        logger.d("addScanResult: $scanResult")
        val transport = scanResult.transport
        allWatches.update { devices ->
            val mutableDevices = devices.toMutableMap()
            val existing = devices[transport]
            if (existing == null) {
                mutableDevices.put(
                    transport, Watch(
                        transport = transport,
                        scanResult = scanResult,
                        connectGoal = false,
                        knownWatchProps = null,
                        activeConnection = null,
                        asPersisted = null,
                        forget = false,
                        firmwareUpdateAvailable = null,
                    )
                )
            } else {
                mutableDevices.put(transport, existing.copy(scanResult = scanResult))
            }
            mutableDevices
        }
    }

    /**
     * Update the list of known watches, mutating the specific watch, if it exists, and it the
     * mutation is not null.
     */
    private fun updateWatch(transport: Transport, mutation: (Watch) -> Watch?) {
        allWatches.update { watches ->
            val device = watches[transport]
            if (device == null) {
                logger.w("couldn't mutate device $transport - not found")
                return@update watches
            }
            val mutated = mutation(device) ?: return@update watches
            watches.plus(transport to mutated)
        }
    }

    override fun requestConnection(transport: Transport, uiContext: UIContext?) {
        libPebbleCoroutineScope.launch {
            logger.d("requestConnection: $transport")
            val scanning = scanning.get()
            scanning.stopBleScan()
            scanning.stopClassicScan()
            val registered = companionDevice.registerDevice(transport, uiContext)
            if (!registered) {
                logger.w { "failed to register companion device; not connecting to $transport" }
                return@launch
            }
            allWatches.update { watches ->
                watches.mapValues { entry ->
                    if (entry.key == transport) {
                        entry.value.copy(connectGoal = true)
                    } else {
                        if (watchConfig.value.multipleConnectedWatchesSupported) {
                            entry.value
                        } else {
                            entry.value.copy(connectGoal = false)
                        }
                    }
                }
            }
        }
    }

    override fun requestDisconnection(transport: Transport) {
        logger.d("requestDisconnection: $transport")
        updateWatch(transport = transport) { it.copy(connectGoal = false) }
    }

    private fun connectTo(device: Watch) {
        val transport = device.transport
        logger.d("connectTo: $transport (activeConnections=$activeConnections)")
        // TODO I think there is a still a race here, where we can wind up connecting multiple
        //  times to the same watch, because the Flow wasn't updated yet
        if (device.activeConnection != null) {
            logger.w("Already connecting to $transport")
            return
        }
        updateWatch(transport = device.transport) { watch ->
//            val connectionExists = allWatches.value[transport]?.activeConnection != null
            val connectionExists = activeConnections.contains(transport)
            if (connectionExists) {
                logger.e("Already connecting to $transport (this is a bug)")
                return@updateWatch null
            }

            var caughtException = false
            val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
                logger.e(
                    "watchmanager caught exception for $transport: $throwable",
                    throwable,
                )
                if (caughtException) {
                    return@CoroutineExceptionHandler
                }
                caughtException = true
                // TODO (not necessarily here but..) handle certain types of "fatal" disconnection (e.g.
                //  bad FW version) by not attempting to endlessly reconnect.
                val connection = allWatches.value[transport]?.activeConnection
                connection?.let {
                    libPebbleCoroutineScope.launch {
                        connection.cleanup()
                    }
                }
            }
            val platformIdentifier = createPlatformIdentifier.identifier(transport)
            if (platformIdentifier == null) {
                // Probably because it couldn't create the device (ios throws on an unknown peristed
                // uuid, so we'll need to scan for it using the name/serial?)...
                // ...but TODO revit this once have more error modes + are handling BT being disabled
                if (device.knownWatchProps != null) {
                    logger.w("removing known device: $transport")
                    forget(transport)
                }
                // hack force another connection
                updateWatch(transport = device.transport) { watch ->
                    watch.copy()
                }
                return@updateWatch null
            }

            activeConnections.add(transport)
            val deviceIdString = transport.identifier.asString
            val thisConnectionNum = connectionNum++
            val coroutineContext =
                SupervisorJob() + exceptionHandler + CoroutineName("con-$deviceIdString-$thisConnectionNum")
            val connectionScope = ConnectionCoroutineScope(coroutineContext)
            logger.v("transport.createConnector")
            val connectionKoinScope = connectionScopeFactory.createScope(
                ConnectionScopeProperties(
                    transport,
                    connectionScope,
                    platformIdentifier
                )
            )
            val pebbleConnector: PebbleConnector = connectionKoinScope.pebbleConnector

//            // TODO why do we need this?
//            val disconnectDuringConnectionJob = connectionScope.launch {
//                pebbleConnector.disconnected.await()
//                logger.d("got disconnection (before connection)")
//                connectionKoinScope.cleanup()
//            }

            connectionScope.launch {
                try {
                    pebbleConnector.connect(device.knownWatchProps != null)
//                    disconnectDuringConnectionJob.cancel()
                    logger.d("watchmanager connected (or failed..); waiting for disconnect: $transport")
                    pebbleConnector.disconnected.disconnected.await()
                    // TODO if not know (i.e. if only a scanresult), then don't reconnect (set goal = false)
                    logger.d("watchmanager got disconnection: $transport")
                } finally {
                    connectionKoinScope.cleanup()
                }
            }
            watch.copy(activeConnection = connectionKoinScope)
        }
    }

    private suspend fun ConnectionScope.cleanup() {
        // Always run in the global scope, so that no cleanup work dies when we kill the connection
        // scope.
        libPebbleCoroutineScope.async {
            if (!closed.compareAndSet(expectedValue = false, newValue = true)) {
                logger.w("${transport}: already done cleanup")
                return@async
            }
            logger.d("${transport}: cleanup")
            pebbleConnector.disconnect()
            try {
                // TODO can this break when BT gets disabled? we call this, it times out, ...
                withTimeout(DISCONNECT_TIMEOUT) {
                    logger.d("${transport}: cleanup: waiting for disconnection")
                    pebbleConnector.disconnected.disconnected.await()
                }
            } catch (e: TimeoutCancellationException) {
                logger.w("cleanup: timed out waiting for disconnection from ${transport}")
            }
            logger.d("${transport}: cleanup: removing active device")
            logger.d("${transport}: cleanup: cancelling scope")
            close()
            activeConnections.remove(transport)
            updateWatch(transport) { it.copy(activeConnection = null) }
        }.await()
    }

    private fun disconnectFrom(transport: Transport) {
        logger.d("disconnectFrom: $transport")
        val activeConnection = allWatches.value[transport]?.activeConnection
        if (activeConnection == null) {
            Logger.d("disconnectFrom / not an active device")
            return
        }
        activeConnection.pebbleConnector.disconnect()
    }

    private fun Watch.isOnlyScanResult() =
        scanResult != null && activeConnection == null && !connectGoal

    override fun clearScanResults() {
        logger.d("clearScanResults")
        allWatches.update { aw ->
            aw.filterValues { watch ->
                !watch.isOnlyScanResult()
            }.mapValues { it.value.copy(scanResult = null) }
        }
    }

    override fun forget(transport: Transport) {
        requestDisconnection(transport)
        updateWatch(transport) { it.copy(forget = true) }
    }

    companion object {
        private val DISCONNECT_TIMEOUT = 3.seconds
    }
}

data class CurrentAndPreviousState(
    val previousState: ConnectingPebbleState?,
    val currentState: ConnectingPebbleState?,
)

fun CurrentAndPreviousState?.justConnected(): Boolean {
    if (this == null) return false
    return currentState is ConnectingPebbleState.Connected && previousState !is ConnectingPebbleState.Connected
}

private fun StateFlow<Map<Transport, Watch>>.flowOfAllDevices(): Flow<Map<Transport, ConnectingPebbleState>> {
    return flatMapLatest { map ->
        val listOfInnerFlows =
            map.values.mapNotNull { it.activeConnection?.pebbleConnector?.state }
        if (listOfInnerFlows.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(listOfInnerFlows) { innerValues ->
                innerValues.associateBy { it.transport }
            }
        }
    }
}
