@file:OptIn(ExperimentalUuidApi::class)

package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.blobdb.NotificationBlobDB
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebbleLeScanRecord
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.packets.WatchVersion
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import io.rebble.libpebblecommon.services.SystemService
import io.rebble.libpebblecommon.services.app.AppRunStateService
import io.rebble.libpebblecommon.services.blobdb.BlobDBService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class RealPebbleDevice(
    override val name: String,
    override val transport: Transport,
    private val watchManager: WatchManager,
) : PebbleDevice {
    override suspend fun connect() {
        watchManager.requestConnection(this)
    }

    override suspend fun disconnect() {
        watchManager.requestDisconnection(this)
    }

    override fun toString(): String = "PebbleDevice: name=$name transport=$transport"
}

class RealBleDiscoveredPebbleDevice(
    val pebbleDevice: PebbleDevice,
    override val pebbleScanRecord: PebbleLeScanRecord,
) : PebbleDevice by pebbleDevice, BleDiscoveredPebbleDevice {
    override fun toString(): String = "RealBleDiscoveredPebbleDevice: $pebbleDevice / pebbleScanRecord=$pebbleScanRecord"
}

class RealKnownPebbleDevice(
    val pebbleDevice: PebbleDevice,
    override val isRunningRecoveryFw: Boolean,
) : PebbleDevice by pebbleDevice, KnownPebbleDevice {
    override suspend fun forget() {
        TODO("Not yet implemented")
    }

    override fun toString(): String = "KnownPebbleDevice: $pebbleDevice / isRunningRecoveryFw=$isRunningRecoveryFw"
}

class RealConnectingPebbleDevice(val pebbleDevice: PebbleDevice) : PebbleDevice by pebbleDevice, ConnectingPebbleDevice {
    override fun toString(): String = "ConnectingPebbleDevice: $pebbleDevice"
}

data class NegotiationResult(
    val watchVersion: WatchVersion.WatchVersionResponse,
    val runningApp: Uuid?,
)

class RealNegotiatingPebbleDevice(
    val pebbleDevice: PebbleDevice,
    val pebbleProtocol: PebbleProtocolHandler,
    scope: CoroutineScope,
) : PebbleDevice by pebbleDevice, NegotiatingPebbleDevice {
    val systemService = SystemService(pebbleProtocol).apply { init(scope) }
    val appRunStateService = AppRunStateService(pebbleProtocol).apply { init(scope) }

    suspend fun negotiate(): NegotiationResult {
        Logger.d("RealNegotiatingPebbleDevice negotiate()")
        val appVersionRequest = systemService.appVersionRequest.await()
        Logger.d("RealNegotiatingPebbleDevice appVersionRequest = $appVersionRequest")
        systemService.sendPhoneVersionResponse()
        Logger.d("RealNegotiatingPebbleDevice sent watch version request")
        val watchVersionResponse = systemService.requestWatchVersion()
        Logger.d("RealNegotiatingPebbleDevice watchVersionResponse = $watchVersionResponse")
        val runningApp = appRunStateService.runningApp.first()
        Logger.d("RealNegotiatingPebbleDevice runningApp = $runningApp")
        return NegotiationResult(watchVersionResponse, runningApp)
    }

    override fun toString(): String = "NegotiatingPebbleDevice: $pebbleDevice"
}

class RealConnectedPebbleDevice(
    val pebbleDevice: KnownPebbleDevice,
    val pebbleProtocol: PebbleProtocolHandler,
    val scope: CoroutineScope,
    val database: Database,
    // These were already created in a previous connection state so keep them running
    val appRunStateService: AppRunStateService,
    val systemService: SystemService,
) : KnownPebbleDevice by pebbleDevice, ConnectedPebbleDevice {
    val blobDBService = BlobDBService(pebbleProtocol).apply { init(scope) }
    val notificationBlobDB = NotificationBlobDB(
        scope,
        blobDBService,
        database.blobDBDao(),
        name, //TODO: use identifier instead of name
    )

    override fun sendPPMessage(bytes: ByteArray) {
        TODO("Not yet implemented")
    }

    override fun sendPPMessage(ppMessage: PebblePacket) {
        TODO("Not yet implemented")
    }

    override suspend fun sendNotification(notification: TimelineItem) {
        notificationBlobDB.insert(notification)
    }

    override suspend fun sendPing(cookie: UInt): UInt {
        return systemService.sendPing(cookie)
    }

    override fun toString(): String = "ConnectedPebbleDevice: $pebbleDevice"
}