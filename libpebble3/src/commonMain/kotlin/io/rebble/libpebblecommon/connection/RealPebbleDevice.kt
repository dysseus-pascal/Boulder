package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.Transport.BluetoothTransport.BleTransport
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebbleLeScanRecord
import io.rebble.libpebblecommon.services.WatchInfo

class PebbleDeviceFactory {
    internal fun create(
        transport: Transport,
        state: ConnectingPebbleState?,
        watchConnector: WatchConnector,
        scanResult: PebbleScanResult?,
        knownWatchProperties: KnownWatchProperties?,
        connectGoal: Boolean,
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
                )
                val activeDevice = RealActiveDevice(transport, watchConnector)
                when (state) {
                    is ConnectingPebbleState.Connected.ConnectedInPrf ->
                        RealConnectedPebbleDeviceInRecovery(
                            knownDevice = knownDevice,
                            watchInfo = state.watchInfo,
                            activeDevice = activeDevice,
                            services = state.services,
                        )

                    is ConnectingPebbleState.Connected.ConnectedNotInPrf ->
                        RealConnectedPebbleDevice(
                            knownDevice = knownDevice,
                            watchInfo = state.watchInfo,
                            activeDevice = activeDevice,
                            services = state.services,
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
) : KnownPebbleDevice,
    PebbleDevice by pebbleDevice {
    override suspend fun forget() {
        watchConnector.forget(transport)
    }

    override fun toString(): String =
        "KnownPebbleDevice: $pebbleDevice $serial / runningFwVersion=$runningFwVersion"
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
    PebbleDevice by pebbleDevice, ConnectingPebbleDevice, ActiveDevice by activeDevice {
    override fun toString(): String = "NegotiatingPebbleDevice: $pebbleDevice"
}

internal class RealConnectedPebbleDevice(
    override val watchInfo: WatchInfo,
    private val knownDevice: KnownPebbleDevice,
    private val activeDevice: ActiveDevice,
    private val services: ConnectedPebble.Services,
) : ConnectedPebbleDevice,
    KnownPebbleDevice by knownDevice,
    ActiveDevice by activeDevice,
    ConnectedPebble.Debug by services.debug,
    ConnectedPebble.AppRunState by services.appRunState,
    ConnectedPebble.Firmware by services.firmware,
    ConnectedPebble.Locker by services.locker,
    ConnectedPebble.Notifications by services.notifications,
    ConnectedPebble.Messages by services.messages,
    ConnectedPebble.Time by services.time,
    ConnectedPebble.AppMessages by services.appMessages,
    ConnectedPebble.Logs by services.logs,
    ConnectedPebble.CoreDump by services.coreDump {

    override fun toString(): String = "ConnectedPebbleDevice: $knownDevice"
}

internal class RealConnectedPebbleDeviceInRecovery(
    override val watchInfo: WatchInfo,
    private val knownDevice: KnownPebbleDevice,
    private val activeDevice: ActiveDevice,
    private val services: ConnectedPebble.PrfServices,
) : ConnectedPebbleDeviceInRecovery,
    KnownPebbleDevice by knownDevice,
    ActiveDevice by activeDevice,
    ConnectedPebble.Firmware by services.firmware,
    ConnectedPebble.Logs by services.logs,
    ConnectedPebble.CoreDump by services.coreDump