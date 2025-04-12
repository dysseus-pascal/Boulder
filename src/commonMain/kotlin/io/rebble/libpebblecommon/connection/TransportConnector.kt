@file:OptIn(ExperimentalTime::class)

package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import io.ktor.utils.io.ByteReadChannel
import io.rebble.libpebblecommon.PacketPriority
import io.rebble.libpebblecommon.connection.ConnectingPebbleState.Connected
import io.rebble.libpebblecommon.connection.ConnectingPebbleState.Connecting
import io.rebble.libpebblecommon.connection.ConnectingPebbleState.Failed
import io.rebble.libpebblecommon.connection.ConnectingPebbleState.Inactive
import io.rebble.libpebblecommon.connection.ConnectingPebbleState.Negotiating
import io.rebble.libpebblecommon.connection.endpointmanager.AppFetchProvider
import io.rebble.libpebblecommon.connection.endpointmanager.DebugPebbleProtocolSender
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdate
import io.rebble.libpebblecommon.connection.endpointmanager.NotificationManager
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.AppBlobDB
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.NotificationBlobDB
import io.rebble.libpebblecommon.connection.endpointmanager.putbytes.PutBytesSession
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.PlatformNotificationActionHandler
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.TimelineActionManager
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import io.rebble.libpebblecommon.services.AppFetchService
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.PutBytesService
import io.rebble.libpebblecommon.services.SystemService
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.services.app.AppRunStateService
import io.rebble.libpebblecommon.services.blobdb.BlobDBService
import io.rebble.libpebblecommon.services.blobdb.TimelineService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.ExperimentalTime
import kotlin.time.Instant.Companion.DISTANT_PAST

sealed class PebbleConnectionResult {
    class Success(
        val inboundPPBytes: ByteReadChannel,
        // Using ByteArray here because I'm not 100% sure how the watch handles multiple PP messages
        // within a single PPoG packet (we could make this [Byte] (or use source/sink) if that
        // works OK (for all knowns LE watches)).
        val outboundPPBytes: Channel<ByteArray>,
    ) : PebbleConnectionResult()

    class Failed(reason: String) : PebbleConnectionResult()
}

interface TransportConnector {
    suspend fun connect(): PebbleConnectionResult
    suspend fun disconnect()
    val disconnected: Deferred<Unit>
}

interface PebbleProtocolHandler {
    val inboundMessages: SharedFlow<PebblePacket>
    suspend fun send(message: PebblePacket, priority: PacketPriority = PacketPriority.NORMAL)
    suspend fun send(message: ByteArray, priority: PacketPriority = PacketPriority.NORMAL)
}

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
            val firmware: ConnectedPebble.Firmware,
        ) : Connected()

        data class ConnectedNotInPrf(
            override val transport: Transport,
            override val watchInfo: WatchInfo,
            val services: ConnectedPebble.Services,
        ) : Connected()
    }
}

class PebbleConnector(
    private val transportConnector: TransportConnector,
    val transport: Transport,
    val scope: CoroutineScope,
    private val negotiator: Negotiator,
    private val pebbleProtocolRunner: PebbleProtocolRunner,
    private val platformNotificationActionHandler: PlatformNotificationActionHandler,
    private val database: Database,
    private val pbwCache: LockerPBWCache,
) {
    private val logger = Logger.withTag("PebbleConnector-${transport.identifier}")
    private val _state = MutableStateFlow<ConnectingPebbleState>(Inactive(transport))
    val state: StateFlow<ConnectingPebbleState> = _state.asStateFlow()
    val disconnected = transportConnector.disconnected

    suspend fun connect() {
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
                val inboundMessagesFlow = MutableSharedFlow<PebblePacket>()
                val protocolHandler = object : PebbleProtocolHandler {
                    override val inboundMessages: SharedFlow<PebblePacket> = inboundMessagesFlow

                    override suspend fun send(message: PebblePacket, priority: PacketPriority) {
                        logger.d("sending $message")
                        result.outboundPPBytes.send(message.serialize().asByteArray())
                    }

                    override suspend fun send(message: ByteArray, priority: PacketPriority) {
                        logger.d("sending ${message.joinToString()}")
                        result.outboundPPBytes.send(message)
                    }
                }

                _state.value = Negotiating(transport)
                scope.launch {
                    pebbleProtocolRunner.run(result.inboundPPBytes, inboundMessagesFlow)
                }

                val systemService = SystemService(protocolHandler).apply { init(scope) }
                val appRunStateService = AppRunStateService(protocolHandler).apply { init(scope) }

                val watchInfo = negotiator.negotiate(systemService, appRunStateService)
                if (watchInfo == null) {
                    logger.w("negotiation failed: disconnecting")
                    transportConnector.disconnect()
                    _state.value = Failed(transport)
                    return
                }

                val putBytesService = PutBytesService(protocolHandler).apply { init(scope) }
                val putBytesSession = PutBytesSession(scope, putBytesService)
                val firmwareUpdate = FirmwareUpdate(
                    watchName = transport.name,
                    watchDisconnected = disconnected,
                    watchBoard = watchInfo.board,
                    systemService = systemService,
                    putBytesSession = putBytesSession,
                )

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
                        firmware = firmwareUpdate,
                    )
                    return
                }

                val blobDBService = BlobDBService(protocolHandler).apply { init(scope) }
                val appFetchService = AppFetchService(protocolHandler).apply { init(scope) }
                val timelineService = TimelineService(protocolHandler)
                val notificationBlobDB = NotificationBlobDB(
                    watchScope = scope,
                    blobDBService = blobDBService,
                    blobDBDao = database.blobDBDao(),
                    watchIdentifier = transport.identifier.asString,
                )
                val timelineActionManager = TimelineActionManager(
                    watchTransport = transport,
                    timelineService = timelineService,
                    blobDBDao = database.blobDBDao(),
                    notifActionHandler = platformNotificationActionHandler,
                ).apply { init(scope) }
                val notificationManager = NotificationManager(
                    timelineActionManager = timelineActionManager,
                    notificationBlobDB = notificationBlobDB,
                )
                val appBlobDB = AppBlobDB(
                    watchScope = scope,
                    blobDBService = blobDBService,
                    blobDBDao = database.blobDBDao(),
                    watchIdentifier = transport.identifier.asString,
                )
                // TODO bail out connecting if we don't know the platform. Not used yet
                val appFetchProvider = watchInfo.platform?.watchType?.let {
                    AppFetchProvider(pbwCache, appFetchService, putBytesSession, it)
                        .apply { init(scope) }
                } ?: run {
                    logger.e { "App fetch provider cannot init, watchInfo.platform was null" }
                }
                val messages = DebugPebbleProtocolSender(protocolHandler)

                _state.value = Connected.ConnectedNotInPrf(
                    transport = transport,
                    watchInfo = watchInfo,
                    services = ConnectedPebble.Services(
                        debug = systemService,
                        appRunState = appRunStateService,
                        firmware = firmwareUpdate,
                        locker = appBlobDB,
                        notifications = notificationManager,
                        messages = messages,
                        time = systemService,
                    )
                )
            }
        }
    }

    fun disconnect() {
        scope.launch {
            transportConnector.disconnect()
        }
    }

    class Factory(
        private val platformNotificationActionHandler: PlatformNotificationActionHandler,
        private val database: Database,
        private val pbwCache: LockerPBWCache,
    ) {
        fun create(
            transportConnector: TransportConnector,
            transport: Transport,
            scope: CoroutineScope,
        ): PebbleConnector = PebbleConnector(
            transportConnector = transportConnector,
            transport = transport,
            scope = scope,
            negotiator = Negotiator(),
            pebbleProtocolRunner = PebbleProtocolRunner(),
            platformNotificationActionHandler = platformNotificationActionHandler,
            database = database,
            pbwCache = pbwCache,
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