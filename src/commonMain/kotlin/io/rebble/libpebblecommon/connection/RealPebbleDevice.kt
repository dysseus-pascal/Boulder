@file:OptIn(ExperimentalUuidApi::class)

package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebbleLeScanRecord
import io.rebble.libpebblecommon.packets.WatchVersion
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import io.rebble.libpebblecommon.services.SystemService
import io.rebble.libpebblecommon.services.app.AppRunStateService
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
        watchManager.connectTo(this)
    }

    override suspend fun disconnect() {
        watchManager.disconnectFrom(this)
    }
}

class RealBleDiscoveredPebbleDevice(
    val pebbleDevice: PebbleDevice,
    override val pebbleScanRecord: PebbleLeScanRecord,
) : PebbleDevice by pebbleDevice, BleDiscoveredPebbleDevice

class RealKnownPebbleDevice(
    val pebbleDevice: PebbleDevice,
    override val isRunningRecoveryFw: Boolean,
) : PebbleDevice by pebbleDevice, KnownPebbleDevice {
    override suspend fun forget() {
        TODO("Not yet implemented")
    }
}

class RealConnectingPebbleDevice(val pebbleDevice: PebbleDevice) : PebbleDevice by pebbleDevice, ConnectingPebbleDevice

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
}

class RealConnectedPebbleDevice(
    val pebbleDevice: KnownPebbleDevice,
    // These were already created in a previous connection state so keep them running
    val appRunStateService: AppRunStateService,
    val systemService: SystemService,
) : KnownPebbleDevice by pebbleDevice, ConnectedPebbleDevice {
    override fun sendPPMessage(bytes: ByteArray) {
        TODO("Not yet implemented")
    }

    override fun sendPPMessage(ppMessage: PebblePacket) {
        TODO("Not yet implemented")
    }

    override suspend fun sendNotification() {
        TODO("Not yet implemented")
    }

    override suspend fun sendPing(cookie: UInt): UInt {
        return systemService.sendPing(cookie)
    }
}