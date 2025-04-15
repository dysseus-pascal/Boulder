package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.Transport.BluetoothTransport.BleTransport
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebbleBle
import io.rebble.libpebblecommon.connection.bt.ble.transport.gattConnector
import io.rebble.libpebblecommon.database.dao.KnownWatchDao
import io.rebble.libpebblecommon.database.entity.KnownWatchItem
import io.rebble.libpebblecommon.database.entity.transport
import io.rebble.libpebblecommon.database.entity.type
import io.rebble.libpebblecommon.services.WatchInfo
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlin.time.Duration.Companion.seconds

/** Everything that is persisted, not including fields that are duplicated elsewhere (e.g. goal) */
internal data class KnownWatchProperties(
    val name: String,
    val runningFwVersion: String,
    val serial: String,
)

internal fun WatchInfo.asWatchProperties(transport: Transport): KnownWatchProperties =
    KnownWatchProperties(
        name = transport.name,
        runningFwVersion = runningFwVersion.stringVersion,
        serial = serial,
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
    )
}

interface WatchConnector {
    fun addScanResult(scanResult: PebbleScanResult)
    fun requestConnection(transport: Transport)
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
    val activeConnection: PebbleConnector?,
    /**
     * What is currently persisted for this watch? Only used to check whether we need to persist
     * changes.
     */
    val asPersisted: KnownWatchItem?,
    val forget: Boolean,
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
)

class WatchManager(
    private val config: LibPebbleConfig,
    private val knownWatchDao: KnownWatchDao,
    private val pebbleDeviceFactory: PebbleDeviceFactory,
    private val pebbleConnectorFactory: PebbleConnector.Factory,
) : WatchConnector {
    private val logger = Logger.withTag("WatchManager")
    private val allWatches = MutableStateFlow<Map<Transport, Watch>>(emptyMap())
    private val _watches = MutableStateFlow<List<PebbleDevice>>(emptyList())
    val watches: StateFlow<List<PebbleDevice>> = _watches.asStateFlow()

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
            )
        }
    }

    private suspend fun persistIfNeeded(watch: Watch) {
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

    @OptIn(DelicateCoroutinesApi::class)
    fun init() {
        logger.d("watchmanager init()")
        GlobalScope.launch {
            loadKnownWatchesFromDb()
            val activeConnectionStates = allWatches.flowOfAllDevices()
            combine(
                allWatches,
                activeConnectionStates,
            ) { watches, active ->
                // Update for active connection state
                watches.values.mapNotNull { device ->
                    val transport = device.transport
                    val connectionState = active[transport]
                    val hasConnectionAttempt = active.containsKey(device.transport)

                    persistIfNeeded(device)
                    // Removed forgotten device once it is disconnected
                    if (!hasConnectionAttempt && device.forget) {
                        logger.d("removing ${device.transport} from allWatches")
                        allWatches.update { it.minus(device.transport) }
                        return@mapNotNull null
                    }

                    if (device.connectGoal && !hasConnectionAttempt) {
                        connectTo(device)
                    } else if (!device.connectGoal && hasConnectionAttempt) {
                        disconnectFrom(device.transport)
                    }
                    // Update persisted props after connection
                    if (connectionState is ConnectingPebbleState.Connected) {
                        val newProps = connectionState.watchInfo.asWatchProperties(transport)
                        if (newProps != device.knownWatchProps) {
                            updateWatch(transport) {
                                it.copy(knownWatchProps = newProps)
                            }
                        }

                        // Clear scan results after we connected to one of them
                        if (device.scanResult != null) {
                            clearScanResults()
                        }
                    }
                    pebbleDeviceFactory.create(
                        transport = transport,
                        state = connectionState,
                        watchConnector = this@WatchManager,
                        scanResult = device.scanResult,
                        knownWatchProperties = device.knownWatchProps,
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

    override fun requestConnection(transport: Transport) {
        logger.d("requestConnection: $transport")
        updateWatch(transport = transport) { it.copy(connectGoal = true) }
    }

    override fun requestDisconnection(transport: Transport) {
        logger.d("requestDisconnection: $transport")
        updateWatch(transport = transport) { it.copy(connectGoal = false) }
    }

    private fun connectTo(device: Watch) {
        val transport = device.transport
        logger.d("connectTo: ${transport}")
        // TODO I think there is a still a race here, where we can wind up connecting multiple
        //  times to the same watch, because the Flow wasn't updated yet
        if (device.activeConnection != null) {
            logger.w("Already connecting to $transport")
            return
        }
        updateWatch(transport = device.transport) { watch ->
            val connectionExists = allWatches.value[transport]?.activeConnection != null
            if (connectionExists) {
                logger.e("Already connecting to $transport (this is a bug)")
                return@updateWatch null
            }

            val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
                logger.e(
                    "watchmanager caught exception for $transport: $throwable",
                    throwable,
                )
                // TODO (not necessarily here but..) handle certain types of "fatal" disconnection (e.g.
                //  bad FW version) by not attempting to endlessly reconnect.
                val connection = allWatches.value[transport]?.activeConnection
                connection?.let {
                    GlobalScope.launch {
                        connection.cleanup()
                    }
                }
            }
            val deviceIdString = transport.identifier.asString
            val coroutineContext =
                SupervisorJob() + exceptionHandler + CoroutineName("con-$deviceIdString")
            val connectionScope = CoroutineScope(coroutineContext)
            logger.v("transport.createConnector")
            val transportConnector = transport.createConnector(connectionScope)
            if (transportConnector == null) {
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
                connectionScope.cancel()
                return@updateWatch null
            }

            logger.v("creating pebbleConnector")
            val pebbleConnector = pebbleConnectorFactory.create(
                transportConnector = transportConnector,
                transport = transport,
                scope = connectionScope,
            )
            val disconnectDuringConnectionJob = connectionScope.launch {
                pebbleConnector.disconnected.await()
                logger.d("got disconnection (before connection)")
                pebbleConnector.cleanup()
            }

            connectionScope.launch {
                try {
                    pebbleConnector.connect()
                    disconnectDuringConnectionJob.cancel()
                    logger.d("watchmanager connected; waiting for disconnect: $transport")
                    pebbleConnector.disconnected.await()
                    // TODO if not know (i.e. if only a scanresult), then don't reconnect (set goal = false)
                    logger.d("watchmanager got disconnection: $transport")
                } finally {
                    pebbleConnector.cleanup()
                }
            }
            watch.copy(activeConnection = pebbleConnector)
        }
    }

    private suspend fun PebbleConnector.cleanup() {
        logger.d("${transport}: cleanup")
        disconnect()
        try {
            withTimeout(DISCONNECT_TIMEOUT) {
                logger.d("${transport}: cleanup: waiting for disconnection")
                disconnected.await()
            }
        } catch (e: TimeoutCancellationException) {
            logger.w("cleanup: timed out waiting for disconnection from ${transport}")
        }
        logger.d("${transport}: cleanup: removing active device")
        updateWatch(transport) { it.copy(activeConnection = null) }
        logger.d("${transport}: cleanup: cancelling scope")
        scope.cancel()
    }

    private fun disconnectFrom(transport: Transport) {
        logger.d("disconnectFrom: $transport")
        val activeConnection = allWatches.value[transport]?.activeConnection
        if (activeConnection == null) {
            Logger.d("disconnectFrom / not an active device")
            return
        }
        activeConnection.disconnect()
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

    private fun Transport.createConnector(scope: CoroutineScope): TransportConnector? {
        return when (this) {
            is BleTransport -> {
                val connector = gattConnector(this, config.context, scope) ?: return null
                PebbleBle(
                    config = config,
                    transport = this,
                    scope = scope,
                    gattConnector = connector,
                )
            }

            else -> TODO("not implemented")
        }
    }

    companion object {
        private val DISCONNECT_TIMEOUT = 3.seconds
    }
}

expect fun getTempAppPath(appContext: AppContext): Path

private fun StateFlow<Map<Transport, Watch>>.flowOfAllDevices(): Flow<Map<Transport, ConnectingPebbleState>> {
    return flatMapLatest { map ->
        val listOfInnerFlows = map.values.mapNotNull { it.activeConnection?.state }
        if (listOfInnerFlows.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(listOfInnerFlows) { innerValues ->
                innerValues.associateBy { it.transport }
            }
        }
    }
}
