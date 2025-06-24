package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.Transport.BluetoothTransport.BleTransport
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebbleLeScanRecord
import io.rebble.libpebblecommon.database.MillisecondInstant
import io.rebble.libpebblecommon.services.WatchInfo
import kotlinx.datetime.Instant

class PebbleDeviceFactory {
    internal fun create(
        transport: Transport,
        state: ConnectingPebbleState?,
        watchConnector: WatchConnector,
        scanResult: PebbleScanResult?,
        knownWatchProperties: KnownWatchProperties?,
        connectGoal: Boolean,
        firmwareUpdateAvailable: FirmwareUpdateCheckResult?,
    ): PebbleDevice {
        val pebbleDevice = RealPebbleDevice(transport = transport, watchConnector)
        if (!connectGoal && state.isActive()) {
            return RealDisconnectingPebbleDevice(pebbleDevice)
        }
        return when (state) {
            is ConnectingPebbleState.Connected -> {
                val knownDevice = RealKnownPebbleDevice(
                    runningFwVersion = state.watchInfo.runningFwVersion.stringVersion,
                    serial = state.watchInfo.serial,
                    pebbleDevice = pebbleDevice,
                    watchConnector = watchConnector,
                    lastConnected = knownWatchProperties?.lastConnected.asLastConnected(),
                )
                val activeDevice = RealActiveDevice(transport, watchConnector)
                when (state) {
                    is ConnectingPebbleState.Connected.ConnectedInPrf ->
                        RealConnectedPebbleDeviceInRecovery(
                            knownDevice = knownDevice,
                            watchInfo = state.watchInfo,
                            activeDevice = activeDevice,
                            services = state.services,
                            firmwareUpdateAvailable = firmwareUpdateAvailable,
                        )

                    is ConnectingPebbleState.Connected.ConnectedNotInPrf ->
                        RealConnectedPebbleDevice(
                            knownDevice = knownDevice,
                            watchInfo = state.watchInfo,
                            activeDevice = activeDevice,
                            services = state.services,
                            firmwareUpdateAvailable = firmwareUpdateAvailable,
                        )
                }
            }

            // TODO should have separate "KnownConnecting" (so we can show serial/etc in UI)
            is ConnectingPebbleState.Connecting -> RealConnectingPebbleDevice(
                pebbleDevice = pebbleDevice,
                activeDevice = RealActiveDevice(transport, watchConnector),
            )

            is ConnectingPebbleState.Negotiating -> RealNegotiatingPebbleDevice(
                pebbleDevice = pebbleDevice,
                activeDevice = RealActiveDevice(transport, watchConnector),
            )

            is ConnectingPebbleState.Failed, is ConnectingPebbleState.Inactive,
            null -> {
                val leScanRecord = scanResult?.leScanRecord
                when {
                    leScanRecord != null && transport is BleTransport ->
                        RealBleDiscoveredPebbleDevice(
                            pebbleDevice = pebbleDevice,
                            pebbleScanRecord = leScanRecord,
                            rssi = scanResult.rssi,
                        )

                    knownWatchProperties != null -> RealKnownPebbleDevice(
                        runningFwVersion = knownWatchProperties.runningFwVersion,
                        serial = knownWatchProperties.serial,
                        pebbleDevice = pebbleDevice,
                        watchConnector = watchConnector,
                        lastConnected = knownWatchProperties.lastConnected.asLastConnected(),
                    )

                    else -> {
                        Logger.w("not sure how to create a device for $transport")
                        pebbleDevice
                    }
                }

            }
        }
    }
}

private fun MillisecondInstant?.asLastConnected(): Instant = this?.instant ?: Instant.DISTANT_PAST

internal class RealPebbleDevice(
    override val transport: Transport,
    private val watchConnector: WatchConnector,
) : PebbleDevice, DiscoveredPebbleDevice {
    override suspend fun connect() {
        watchConnector.requestConnection(transport)
    }

    override fun toString(): String = transport.toString()
}

internal class RealBleDiscoveredPebbleDevice(
    private val pebbleDevice: PebbleDevice,
    override val pebbleScanRecord: PebbleLeScanRecord,
    override val rssi: Int,
) : PebbleDevice by pebbleDevice, BleDiscoveredPebbleDevice {
    override fun toString(): String =
        "RealBleDiscoveredPebbleDevice: $pebbleDevice / pebbleScanRecord=$pebbleScanRecord"
}

internal class RealKnownPebbleDevice(
    override val runningFwVersion: String,
    override val serial: String,
    private val pebbleDevice: PebbleDevice,
    private val watchConnector: WatchConnector,
    override val lastConnected: Instant,
) : KnownPebbleDevice,
    PebbleDevice by pebbleDevice {
    override suspend fun forget() {
        watchConnector.forget(transport)
    }

    override fun toString(): String =
        "KnownPebbleDevice: $pebbleDevice $serial / runningFwVersion=$runningFwVersion lastConnected=$lastConnected"
}

internal class RealActiveDevice(
    private val transport: Transport,
    private val watchConnector: WatchConnector,
) : ActiveDevice {
    override suspend fun disconnect() {
        watchConnector.requestDisconnection(transport)
    }
}

internal class RealDisconnectingPebbleDevice(
    private val pebbleDevice: PebbleDevice,
) : DisconnectingPebbleDevice, PebbleDevice by pebbleDevice

internal class RealConnectingPebbleDevice(
    private val pebbleDevice: PebbleDevice,
    private val activeDevice: ActiveDevice,
) :
    PebbleDevice by pebbleDevice, ConnectingPebbleDevice, ActiveDevice by activeDevice {
    override fun toString(): String = "ConnectingPebbleDevice: $pebbleDevice"
}

internal class RealNegotiatingPebbleDevice(
    private val pebbleDevice: PebbleDevice,
    private val activeDevice: ActiveDevice,
) :
    PebbleDevice by pebbleDevice, ConnectingPebbleDevice, NegotiatingPebbleDevice,
    ActiveDevice by activeDevice {
    override fun toString(): String = "NegotiatingPebbleDevice: $pebbleDevice"
}

internal class RealConnectedPebbleDevice(
    override val watchInfo: WatchInfo,
    private val knownDevice: KnownPebbleDevice,
    private val activeDevice: ActiveDevice,
    private val services: ConnectedPebble.Services,
    override val firmwareUpdateAvailable: FirmwareUpdateCheckResult?,
) : ConnectedPebbleDevice,
    KnownPebbleDevice by knownDevice,
    ActiveDevice by activeDevice,
    ConnectedPebble.Debug by services.debug,
    ConnectedPebble.AppRunState by services.appRunState,
    ConnectedPebble.Firmware by services.firmware,
    ConnectedPebble.Messages by services.messages,
    ConnectedPebble.Time by services.time,
    ConnectedPebble.AppMessages by services.appMessages,
    ConnectedPebble.Logs by services.logs,
    ConnectedPebble.CoreDump by services.coreDump,
    ConnectedPebble.Music by services.music,
    ConnectedPebble.PKJS by services.pkjs {

    override fun toString(): String = "ConnectedPebbleDevice: $knownDevice"
}

internal class RealConnectedPebbleDeviceInRecovery(
    override val watchInfo: WatchInfo,
    private val knownDevice: KnownPebbleDevice,
    private val activeDevice: ActiveDevice,
    private val services: ConnectedPebble.PrfServices,
    override val firmwareUpdateAvailable: FirmwareUpdateCheckResult?,
) : ConnectedPebbleDeviceInRecovery,
    KnownPebbleDevice by knownDevice,
    ActiveDevice by activeDevice,
    ConnectedPebble.Firmware by services.firmware,
    ConnectedPebble.Logs by services.logs,
    ConnectedPebble.CoreDump by services.coreDump