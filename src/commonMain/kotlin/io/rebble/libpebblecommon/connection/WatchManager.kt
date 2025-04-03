@file:OptIn(ExperimentalTime::class)

package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.Transport.BluetoothTransport.BleTransport
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebbleBle
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebbleLeScanRecord
import io.rebble.libpebblecommon.connection.bt.ble.transport.gattConnector
import io.rebble.libpebblecommon.connection.endpointmanager.AppFetchProvider
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdate
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.AppBlobDB
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.NotificationBlobDB
import io.rebble.libpebblecommon.connection.endpointmanager.putbytes.PutBytesSession
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.entity.knownWatchItem
import io.rebble.libpebblecommon.database.entity.transport
import io.rebble.libpebblecommon.disk.pbz.PbzFirmware
import io.rebble.libpebblecommon.packets.blobdb.AppMetadata
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import io.rebble.libpebblecommon.services.AppFetchService
import io.rebble.libpebblecommon.services.PutBytesService
import io.rebble.libpebblecommon.services.SystemService
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.services.app.AppRunStateService
import io.rebble.libpebblecommon.services.blobdb.BlobDBService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.uuid.Uuid

private data class ScanResultWithGoal(
    val scanResult: PebbleScanResult,
    val connectGoal: Boolean,
)

class WatchManager(
    private val config: LibPebbleConfig,
    private val database: Database,
    private val pbwCache: LockerPBWCache,
) {
    private val knownWatchDao = database.knownWatchDao()

    // Scan results - may or may not have extra scan record data
    private val scanResults = MutableStateFlow<Map<Transport, ScanResultWithGoal>>(emptyMap())

    // We have previously connected (with successful negotiation) to these
    private val persistedWatches: Flow<Map<Transport, KnownPebbleDevice>> =
        knownWatchDao.knownWatches().map { devices ->
            devices.map { d ->
                RealKnownPebbleDevice(
                    pebbleDevice = RealPebbleDevice(
                        name = d.name,
                        transport = d.transport(),
                        connectGoal = d.connectGoal,
                    ),
//                    runningFwVersion = FirmwareVersion(Instant.fromEpochSeconds(1), 1, 1, 1, null, "", false),
                    runningFwVersion = d.runningFwVersion,
                    serial = d.serial,
                )
            }.associateBy { it.transport }
        }
    private val _watches = MutableStateFlow<List<PebbleDevice>>(emptyList())
    private val activeConnections = MutableStateFlow<Map<Transport, PebbleConnector>>(emptyMap())

    val watches: StateFlow<List<PebbleDevice>> = _watches.asStateFlow()

    fun init() {
        Logger.d("watchmanager init()")
        GlobalScope.launch {
            val activeConnectionStates = activeConnections.flowOfAllDevices()
            combine(
                scanResults,
                persistedWatches,
                activeConnectionStates,
            ) { scan, persisted, active ->
                val allDevices: MutableMap<Transport, PebbleDevice> =
                    scan.mapValues { it.value.asPebbleDevice() }.toMutableMap()
                // Known device takes priority over scan result for the same device
                allDevices.putAll(persisted)
                // Update for active connection state
                active.forEach { activeDevice ->
                    val existingDevice = allDevices[activeDevice.key]
                    if (existingDevice == null) {
                        // maybe can happen if we forget a know device, before disconnecting?
                        // TODO force a disconnect in that case?
                        Logger.w("${activeDevice.value} / existingDevice == null")
                        return@forEach
                    }
                    val pebbleDevice = activeDevice.value.asPebbleDevice(existingDevice)
                    allDevices += activeDevice.key to pebbleDevice

                    // Persist if fully negotiated
                    if (pebbleDevice is KnownPebbleDevice) {
                        val knownWatchItemToPersist = pebbleDevice.knownWatchItem()
                        val existingStoredDevice = existingDevice as? KnownPebbleDevice
                        if (existingStoredDevice == null || knownWatchItemToPersist != existingStoredDevice.knownWatchItem()) {
                            Logger.d("Persisting $pebbleDevice becuase it changed (or is new)")
                            knownWatchDao.insertOrUpdate(knownWatchItemToPersist)
                        }
                    }
                }

                allDevices.values.toList().onEach { device ->
                    val hasConnectionAttempt = active.containsKey(device.transport)
                    if (device.connectGoal && !hasConnectionAttempt) {
                        connectTo(device)
                    } else if (!device.connectGoal && hasConnectionAttempt) {
                        disconnectFrom(device)
                    }
                }
            }.collect {
                _watches.value = it.also { Logger.v("watches: $it") }
            }
        }
    }

    fun addScanResult(scanResult: PebbleScanResult) {
        Logger.d("addScanResult: $scanResult")
        val existingGoal = scanResults.value[scanResult.transport]?.connectGoal ?: false
        scanResults.value += scanResult.transport to ScanResultWithGoal(scanResult, existingGoal)
    }

    private suspend fun setConnectGoal(pebbleDevice: PebbleDevice, connectGoal: Boolean) {
        val knownDevice = persistedWatches.first()[pebbleDevice.transport]
        if (knownDevice != null) {
            knownWatchDao.insertOrUpdate(
                knownDevice.knownWatchItem().copy(connectGoal = connectGoal)
            )
        } else {
            val scanResult = scanResults.value[pebbleDevice.transport]
            if (scanResult != null) {
                scanResults.value += pebbleDevice.transport to ScanResultWithGoal(
                    scanResult = scanResult.scanResult,
                    connectGoal = connectGoal,
                )
            }
        }
    }

    suspend fun requestConnection(pebbleDevice: PebbleDevice) {
        Logger.d("requestConnection: $pebbleDevice")
        setConnectGoal(pebbleDevice = pebbleDevice, connectGoal = true)
    }

    suspend fun requestDisconnection(pebbleDevice: PebbleDevice) {
        Logger.d("requestDisconnection: $pebbleDevice")
        setConnectGoal(pebbleDevice = pebbleDevice, connectGoal = false)
    }

    private fun connectTo(pebbleDevice: PebbleDevice) {
        Logger.d("connectTo: $pebbleDevice")
        val existingDevice = activeConnections.value[pebbleDevice.transport]
        if (existingDevice != null) {
            Logger.d("Already connecting to $pebbleDevice")
            return
        }
        val exceptionHandler = CoroutineExceptionHandler { coroutineContext, throwable ->
            Logger.e("watchmanager caught exception for ${pebbleDevice.transport}", throwable)
            // TODO (not necessarily here but..) handle certain types of "fatal" disconnection (e.g.
            //  bad FW version) by not attempting to endlessly reconnect.
            val connection = activeConnections.value[pebbleDevice.transport]
            connection?.let {
                GlobalScope.launch {
                    connection.cleanup()
                }
            }
        }
        val deviceIdString = pebbleDevice.transport.identifier.asString
        val coroutineContext =
            SupervisorJob() + exceptionHandler + CoroutineName("con-$deviceIdString")
        val connectionScope = CoroutineScope(coroutineContext)
        val transportConnector = pebbleDevice.createConnector(connectionScope)
        val pebbleConnector =
            PebbleConnector(
                transportConnector,
                database,
                pebbleDevice.transport,
                connectionScope
            )
        activeConnections.value += pebbleDevice.transport to pebbleConnector

        connectionScope.launch {
            try {
                pebbleConnector.connect()
                Logger.d("watchmanager connected; waiting for disconnect: ${pebbleDevice.transport}")
                pebbleConnector.disconnected.first()
                // TODO if not know (i.e. if only a scanresult), then don't reconnect (set goal = false)
                Logger.d("watchmanager got disconnection: ${pebbleDevice.transport}")
            } finally {
                pebbleConnector.cleanup()
            }
        }
    }

    private suspend fun PebbleConnector.cleanup() {
        Logger.d("${transport}: cleanup")
        disconnect()
        try {
            withTimeout(DISCONNECT_TIMEOUT) {
                Logger.d("${transport}: cleanup: waiting for disconnection")
                disconnected.first()
            }
        } catch (e: TimeoutCancellationException) {
            Logger.w("cleanup: timed out waiting for disconnection from ${transport}")
        }
        Logger.d("${transport}: cleanup: removing active device")
        activeConnections.value -= transport
        Logger.d("${transport}: cleanup: cancelling scope")
        scope.cancel()
    }

    private fun disconnectFrom(pebbleDevice: PebbleDevice) {
        Logger.d("disconnectFrom: $pebbleDevice")
        val activeConnection = activeConnections.value[pebbleDevice.transport]
        if (activeConnection == null) {
            Logger.d("disconnectFrom / not an active device")
            return
        }
        activeConnection.disconnect()
    }

    private fun PebbleDevice.createConnector(scope: CoroutineScope): TransportConnector {
        val pebbleTransport = transport
        return when (pebbleTransport) {
            is BleTransport -> PebbleBle(
                config = config,
                pebbleDevice = this,
                scope = scope,
                gattConnector = gattConnector(pebbleTransport, config.context),
            )

            else -> TODO("not implemented")
        }
    }

    private fun ConnectingPebbleState.asPebbleDevice(
        basePebbleDevice: PebbleDevice,
    ): PebbleDevice = when (this) {
        is ConnectingPebbleState.Inactive -> RealConnectingPebbleDevice(basePebbleDevice)
        is ConnectingPebbleState.Connecting -> RealConnectingPebbleDevice(basePebbleDevice)
        is ConnectingPebbleState.Negotiating -> RealNegotiatingPebbleDevice(basePebbleDevice)
        is ConnectingPebbleState.Connected -> {
            RealConnectedPebbleDevice(
                pebbleDevice = RealKnownPebbleDevice(
                    pebbleDevice = basePebbleDevice,
                    runningFwVersion = watchInfo.runningFwVersion.stringVersion,
                    serial = watchInfo.serial,
                ),
                pebbleProtocol = pebbleProtocol,
                scope = scope,
                database = database,
                appRunStateService = appRunStateService,
                systemService = systemService,
                watchInfo = watchInfo,
            )
        }

        is ConnectingPebbleState.Failed -> basePebbleDevice
    }

    companion object {
        private val DISCONNECT_TIMEOUT = 3.seconds
    }

    private fun ScanResultWithGoal.asPebbleDevice(): DiscoveredPebbleDevice {
        val pebbleDevice = RealPebbleDevice(
            name = scanResult.name,
            transport = scanResult.transport,
            connectGoal = connectGoal,
        )
        return when {
            scanResult.transport is BleTransport && scanResult.leScanRecord != null ->
                RealBleDiscoveredPebbleDevice(
                    pebbleDevice = pebbleDevice,
                    pebbleScanRecord = scanResult.leScanRecord
                )

            else -> pebbleDevice
        }
    }

    private inner class RealPebbleDevice(
        override val name: String,
        override val transport: Transport,
        override val connectGoal: Boolean,
    ) : PebbleDevice, DiscoveredPebbleDevice {
        override suspend fun connect() {
            requestConnection(this)
        }

        override suspend fun disconnect() {
            requestDisconnection(this)
        }

        override fun toString(): String = "PebbleDevice: name=$name transport=$transport"
    }

    private inner class RealBleDiscoveredPebbleDevice(
        val pebbleDevice: PebbleDevice,
        override val pebbleScanRecord: PebbleLeScanRecord,
    ) : PebbleDevice by pebbleDevice, BleDiscoveredPebbleDevice {
        override fun toString(): String =
            "RealBleDiscoveredPebbleDevice: $pebbleDevice / pebbleScanRecord=$pebbleScanRecord"
    }

    private inner class RealKnownPebbleDevice(
        val pebbleDevice: PebbleDevice,
        override val runningFwVersion: String,
        override val serial: String,
    ) : PebbleDevice by pebbleDevice, KnownPebbleDevice {
        override suspend fun forget() {
            TODO("Not yet implemented")
        }

        override fun toString(): String =
            "KnownPebbleDevice: $pebbleDevice $serial / runningFwVersion=$runningFwVersion"
    }

    private inner class RealConnectingPebbleDevice(val pebbleDevice: PebbleDevice) :
        PebbleDevice by pebbleDevice, ConnectingPebbleDevice {
        override fun toString(): String = "ConnectingPebbleDevice: $pebbleDevice"
    }

    private inner class RealNegotiatingPebbleDevice(val pebbleDevice: PebbleDevice) :
        PebbleDevice by pebbleDevice, ConnectingPebbleDevice {
        override fun toString(): String = "NegotiatingPebbleDevice: $pebbleDevice"
    }

    private inner class RealConnectedPebbleDevice(
        val pebbleDevice: KnownPebbleDevice,
        val pebbleProtocol: PebbleProtocolHandler,
        val scope: CoroutineScope,
        val database: Database,
        // These were already created in a previous connection state so keep them running
        val appRunStateService: AppRunStateService,
        val systemService: SystemService,
        override val watchInfo: WatchInfo,
    ) : KnownPebbleDevice by pebbleDevice, ConnectedPebbleDevice {
        private val logger = Logger.withTag("CPD-${pebbleDevice.name}")
        val blobDBService = BlobDBService(pebbleProtocol).apply { init(scope) }
        val putBytesService = PutBytesService(pebbleProtocol).apply { init(scope) }
        val appFetchService = AppFetchService(pebbleProtocol).apply { init(scope) }

        val notificationBlobDB = NotificationBlobDB(
            scope,
            blobDBService,
            database.blobDBDao(),
            pebbleDevice.transport.identifier.asString
        )
        val appBlobDB = AppBlobDB(
            scope,
            blobDBService,
            database.blobDBDao(),
            pebbleDevice.transport.identifier.asString
        )
        val putBytesSession = PutBytesSession(scope, putBytesService)
        val appFetchProvider = watchInfo.platform?.watchType?.let {
            AppFetchProvider(pbwCache, appFetchService, putBytesSession, it)
                .apply { init(scope) }
        } ?: run {
            logger.e { "App fetch provider cannot init, watchInfo.platform was null" }
        }
        val firmwareUpdate = FirmwareUpdate(
            watchName = name,
            watchFlow = watches.forDevice(this),
            watchBoard = watchInfo.board,
            systemService = systemService,
            putBytesSession = putBytesSession,
        )

        override fun sendPPMessage(bytes: ByteArray) {
            TODO("Not yet implemented")
        }

        override fun sendPPMessage(ppMessage: PebblePacket) {
            TODO("Not yet implemented")
        }

        override fun sideloadFirmware(path: Path): Flow<FirmwareUpdate.FirmwareUpdateStatus> {
            require(SystemFileSystem.exists(path)) { "File does not exist: $path" }
            //TODO: Resumable firmware update
            return firmwareUpdate.beginFirmwareUpdate(PbzFirmware(path), 0u)
        }

        override suspend fun sendNotification(notification: TimelineItem) {
            notificationBlobDB.insert(notification)
        }

        override suspend fun sendPing(cookie: UInt): UInt {
            return systemService.sendPing(cookie)
        }

        override suspend fun insertLockerEntry(entry: AppMetadata) {
            appBlobDB.insertOrReplace(entry)
        }

        override suspend fun deleteLockerEntry(uuid: Uuid) {
            appBlobDB.delete(uuid)
        }

        override suspend fun isLockerEntryNew(entry: AppMetadata): Boolean {
            val existing = appBlobDB.get(entry.uuid.get())
            val nw = entry.toBytes().asByteArray()
            return existing?.contentEquals(nw) != true
        }

        override suspend fun offloadApp(uuid: Uuid) {
            appBlobDB.markForResync(uuid)
        }

        override suspend fun launchApp(uuid: Uuid) {
            appRunStateService.startApp(uuid)
        }

        override fun toString(): String = "ConnectedPebbleDevice: $pebbleDevice"
    }
}

expect fun getTempAppPath(appContext: AppContext): Path

fun StateFlow<Map<Transport, PebbleConnector>>.flowOfAllDevices(): Flow<Map<Transport, ConnectingPebbleState>> {
    return flatMapLatest { map ->
        val listOfInnerFlows = map.values.map { it.state }
        if (listOfInnerFlows.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(listOfInnerFlows) { innerValues ->
                innerValues.associateBy { it.transport }
            }
        }
    }
}

data class NegotiationResult(
    val watchVersion: WatchInfo,
    val runningApp: Uuid?,
)

data class ActiveConnection(
    val transport: Transport,
    val connector: PebbleConnector,
)