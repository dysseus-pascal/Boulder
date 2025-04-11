package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import com.oldguy.common.getUShortAt
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.peek
import io.ktor.utils.io.readByteArray
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
import io.rebble.libpebblecommon.services.PutBytesService
import io.rebble.libpebblecommon.services.SystemService
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.services.app.AppRunStateService
import io.rebble.libpebblecommon.services.blobdb.BlobDBService
import io.rebble.libpebblecommon.services.blobdb.TimelineService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlin.time.Duration.Companion.seconds

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
    data class Connected(
        override val transport: Transport,
        val watchInfo: WatchInfo,
        val services: ConnectedPebble.Services,
    ) : ConnectingPebbleState()
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
                    transportConnector.disconnect()
                    // TODO PRF state
                    _state.value = Failed(transport)
                    return
                }

                val blobDBService = BlobDBService(protocolHandler).apply { init(scope) }
                val putBytesService = PutBytesService(protocolHandler).apply { init(scope) }
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
                val putBytesSession = PutBytesSession(scope, putBytesService)
                // TODO bail out connecting if we don't know the platform. Not used yet
                val appFetchProvider = watchInfo.platform?.watchType?.let {
                    AppFetchProvider(pbwCache, appFetchService, putBytesSession, it)
                        .apply { init(scope) }
                } ?: run {
                    logger.e { "App fetch provider cannot init, watchInfo.platform was null" }
                }
                val firmwareUpdate = FirmwareUpdate(
                    watchName = transport.name,
                    watchDisconnected = disconnected,
                    watchBoard = watchInfo.board,
                    systemService = systemService,
                    putBytesSession = putBytesSession,
                )
                val messages = DebugPebbleProtocolSender(protocolHandler)

                _state.value = Connected(
                    transport = transport,
                    watchInfo = watchInfo,
                    services = ConnectedPebble.Services(
                        debug = systemService,
                        appRunState = appRunStateService,
                        firmware = firmwareUpdate,
                        locker = appBlobDB,
                        notifications = notificationManager,
                        messages = messages,
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