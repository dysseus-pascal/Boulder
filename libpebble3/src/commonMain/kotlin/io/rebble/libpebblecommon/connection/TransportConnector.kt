package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.ConnectingPebbleState.Connected
import io.rebble.libpebblecommon.connection.ConnectingPebbleState.Connecting
import io.rebble.libpebblecommon.connection.ConnectingPebbleState.Failed
import io.rebble.libpebblecommon.connection.ConnectingPebbleState.Inactive
import io.rebble.libpebblecommon.connection.ConnectingPebbleState.Negotiating
import io.rebble.libpebblecommon.connection.endpointmanager.AppFetchProvider
import io.rebble.libpebblecommon.connection.endpointmanager.DebugPebbleProtocolSender
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater
import io.rebble.libpebblecommon.connection.endpointmanager.PKJSLifecycleManager
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.BlobDB
import io.rebble.libpebblecommon.connection.endpointmanager.musiccontrol.MusicControlManager
import io.rebble.libpebblecommon.connection.endpointmanager.phonecontrol.PhoneControlManager
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.TimelineActionManager
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.services.AppFetchService
import io.rebble.libpebblecommon.services.DataLoggingService
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.GetBytesService
import io.rebble.libpebblecommon.services.LogDumpService
import io.rebble.libpebblecommon.services.MusicService
import io.rebble.libpebblecommon.services.PutBytesService
import io.rebble.libpebblecommon.services.SystemService
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.services.app.AppRunStateService
import io.rebble.libpebblecommon.services.appmessage.AppMessageService
import io.rebble.libpebblecommon.services.blobdb.BlobDBService
import io.rebble.libpebblecommon.web.FirmwareUpdateManager
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Instant.Companion.DISTANT_PAST

sealed class PebbleConnectionResult {
    data object Success : PebbleConnectionResult()

    data class Failed(val reason: String) : PebbleConnectionResult()
}

interface TransportConnector {
    suspend fun connect(): PebbleConnectionResult
    suspend fun disconnect()
    val disconnected: Deferred<Unit>
}

class WasDisconnected(val disconnected: Deferred<Unit>)

sealed class ConnectingPebbleState {
    abstract val transport: Transport

    data class Inactive(override val transport: Transport) : ConnectingPebbleState()
    data class Connecting(override val transport: Transport) : ConnectingPebbleState()
    data class Failed(override val transport: Transport) : ConnectingPebbleState()
    data class Negotiating(override val transport: Transport) : ConnectingPebbleState()
    sealed class Connected : ConnectingPebbleState() {
        abstract val watchInfo: WatchInfo

        data class ConnectedInPrf(
            override val transport: Transport,
            override val watchInfo: WatchInfo,
            val services: ConnectedPebble.PrfServices,
        ) : Connected()

        data class ConnectedNotInPrf(
            override val transport: Transport,
            override val watchInfo: WatchInfo,
            val services: ConnectedPebble.Services,
        ) : Connected()
    }
}

fun ConnectingPebbleState?.isActive(): Boolean = when (this) {
    is Connected.ConnectedInPrf, is Connected.ConnectedNotInPrf, is Connecting, is Negotiating -> true
    is Failed, is Inactive, null -> false
}

interface PebbleConnector {
    suspend fun connect(previouslyConnected: Boolean)
    fun disconnect()
    val disconnected: WasDisconnected
    val state: StateFlow<ConnectingPebbleState>
}

class RealPebbleConnector(
    private val transportConnector: TransportConnector,
    private val transport: Transport,
    private val scope: ConnectionCoroutineScope,
    private val negotiator: Negotiator,
    private val pebbleProtocolRunner: PebbleProtocolRunner,
    private val systemService: SystemService,
    private val appRunStateService: AppRunStateService,
    private val dataLoggingService: DataLoggingService,
    private val putBytesService: PutBytesService,
    private val firmwareUpdater: FirmwareUpdater,
    private val blobDBService: BlobDBService,
    private val appFetchService: AppFetchService,
    private val appMessageService: AppMessageService,
    private val timelineActionManager: TimelineActionManager,
    private val blobDB: BlobDB,
    private val pkjsLifecycleManager: PKJSLifecycleManager,
    private val appFetchProvider: AppFetchProvider,
    private val debugPebbleProtocolSender: DebugPebbleProtocolSender,
    private val logDumpService: LogDumpService,
    private val getBytesService: GetBytesService,
    private val phoneControlManager: PhoneControlManager,
    private val musicService: MusicService,
    private val musicControlManager: MusicControlManager,
    private val firmwareUpdateManager: FirmwareUpdateManager,
) : PebbleConnector {
    private val logger = Logger.withTag("PebbleConnector-${transport.identifier}")
    private val _state = MutableStateFlow<ConnectingPebbleState>(Inactive(transport))
    override val state: StateFlow<ConnectingPebbleState> = _state.asStateFlow()
    override val disconnected = WasDisconnected(transportConnector.disconnected)

    override suspend fun connect(previouslyConnected: Boolean) {
        _state.value = Connecting(transport)

        val result = transportConnector.connect()
        when (result) {
            is PebbleConnectionResult.Failed -> {
                logger.e("failed to connect: $result")
                transportConnector.disconnect()
                _state.value = Failed(transport)
            }

            is PebbleConnectionResult.Success -> {
                logger.d("$result")
                val negotiationJob = scope.async {
                    doAfterConnection(previouslyConnected)
                }
                val disconnectedJob = scope.launch {
                    transportConnector.disconnected.await()
                    logger.w { "Disconnected during negotiation" }
                    negotiationJob.cancel()
                }
                disconnectedJob.cancel()
            }
        }
    }

    override fun disconnect() {
        scope.launch {
            transportConnector.disconnect()
        }
    }

    private suspend fun doAfterConnection(previouslyConnected: Boolean) {
        _state.value = Negotiating(transport)
        scope.launch {
            pebbleProtocolRunner.run()
        }

        systemService.init()
        appRunStateService.init()
        dataLoggingService.initialInit()

        val watchInfo = negotiator.negotiate(systemService, appRunStateService)
        if (watchInfo == null) {
            logger.w("negotiation failed: disconnecting")
            transportConnector.disconnect()
            _state.value = Failed(transport)
            return
        }

        putBytesService.init()
        firmwareUpdater.setPlatform(watchInfo.platform)
        firmwareUpdateManager.init(watchInfo)

        val recoveryMode = when {
            watchInfo.runningFwVersion.isRecovery -> true.also {
                logger.i("PRF running; going into recovery mode")
            }

            watchInfo.recoveryFwVersion == null -> true.also {
                logger.w("No recovery FW installed!!! going into recovery mode")
            }

            watchInfo.runningFwVersion < FW_3_0_0 -> true.also {
                logger.w("FW below v3.0 isn't supported; going into recovery mode")
            }

            else -> false
        }
        if (recoveryMode) {
            _state.value = Connected.ConnectedInPrf(
                transport = transport,
                watchInfo = watchInfo,
                services = ConnectedPebble.PrfServices(
                    firmware = firmwareUpdater,
                    logs = logDumpService,
                    coreDump = getBytesService,
                ),
            )
            return
        }

        blobDBService.init()
        blobDB.init(
            watchType = watchInfo.platform.watchType,
            unfaithful = watchInfo.isUnfaithful,
            previouslyConnected = previouslyConnected,
            capabilities = watchInfo.capabilities,
        )
        appFetchService.init()
        timelineActionManager.init()
        appFetchProvider.init(watchInfo.platform.watchType)
        appMessageService.init()
        pkjsLifecycleManager.init(transport, watchInfo)
        phoneControlManager.init()
        musicControlManager.init()
        dataLoggingService.realInit(watchInfo)

        _state.value = Connected.ConnectedNotInPrf(
            transport = transport,
            watchInfo = watchInfo,
            services = ConnectedPebble.Services(
                debug = systemService,
                appRunState = appRunStateService,
                firmware = firmwareUpdater,
                messages = debugPebbleProtocolSender,
                time = systemService,
                appMessages = appMessageService,
                logs = logDumpService,
                coreDump = getBytesService,
                music = musicService,
                pkjs = pkjsLifecycleManager
            )
        )
    }
}

private val FW_3_0_0 = FirmwareVersion(
    stringVersion = "v3.0.0",
    timestamp = DISTANT_PAST,
    major = 3,
    minor = 0,
    patch = 0,
    suffix = null,
    gitHash = "",
    isRecovery = false,
)