package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.Transport.BluetoothTransport.BleTransport
import io.rebble.libpebblecommon.connection.bt.BluetoothState
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebbleLeScanRecord
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater.FirmwareUpdateStatus
import io.rebble.libpebblecommon.database.MillisecondInstant
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
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
        firmwareUpdateState: FirmwareUpdateStatus,
        bluetoothState: BluetoothState,
        lastFirmwareUpdateState: FirmwareUpdateStatus,
    ): PebbleDevice {
        val pebbleDevice = RealPebbleDevice(transport = transport, watchConnector)
        val knownDevice = knownWatchProperties?.let {
            RealKnownPebbleDevice(
                runningFwVersion = knownWatchProperties.runningFwVersion,
                serial = knownWatchProperties.serial,
                pebbleDevice = pebbleDevice,
                watchConnector = watchConnector,
                lastConnected = knownWatchProperties.lastConnected.asLastConnected(),
                watchType = knownWatchProperties.watchType,
            )
        }
        if (bluetoothState.enabled() && !connectGoal && state.isActive()) {
            return when (knownDevice) {
                null -> RealDisconnectingPebbleDevice(pebbleDevice)
                else -> RealDisconnectingKnownPebbleDevice(knownDevice)
            }
        }
        return when {
            bluetoothState.enabled() && !connectGoal && state.isActive() -> when (knownDevice) {
                null -> RealDisconnectingPebbleDevice(pebbleDevice)
                else -> RealDisconnectingKnownPebbleDevice(knownDevice)
            }
            bluetoothState.enabled() && state is ConnectingPebbleState.Connected -> {
                val knownDevice = RealKnownPebbleDevice(
                    runningFwVersion = state.watchInfo.runningFwVersion.stringVersion,
                    serial = state.watchInfo.serial,
                    pebbleDevice = pebbleDevice,
                    watchConnector = watchConnector,
                    lastConnected = knownWatchProperties?.lastConnected.asLastConnected(),
                    watchType = state.watchInfo.platform,
                )
                val activeDevice = RealActiveDevice(transport, watchConnector)
                when (state) {
                    is ConnectingPebbleState.Connected.ConnectedInPrf ->
                        RealConnectedPebbleDeviceInRecovery(
                            knownDevice = knownDevice,
                            watchInfo = state.watchInfo,
                            activeDevice = activeDevice,
                            services = state.services,
                            firmwareUpdateState = firmwareUpdateState,
                            firmwareUpdateAvailable = firmwareUpdateAvailable,
                        )

                    is ConnectingPebbleState.Connected.ConnectedNotInPrf ->
                        RealConnectedPebbleDevice(
                            knownDevice = knownDevice,
                            watchInfo = state.watchInfo,
                            activeDevice = activeDevice,
                            services = state.services,
                            firmwareUpdateState = firmwareUpdateState,
                            firmwareUpdateAvailable = firmwareUpdateAvailable,
                        )
                }
            }

            bluetoothState.enabled() && (state is ConnectingPebbleState.Connecting ||
                    state is ConnectingPebbleState.Negotiating ||
                         connectGoal) -> when (knownDevice) {
                null -> RealConnectingPebbleDevice(
                    pebbleDevice = pebbleDevice,
                    activeDevice = RealActiveDevice(transport, watchConnector),
                    negotiating = state is ConnectingPebbleState.Negotiating,
                    rebootingAfterFirmwareUpdate = lastFirmwareUpdateState !is FirmwareUpdateStatus.NotInProgress,
                )

                else -> RealConnectingKnownPebbleDevice(
                    knownDevice = knownDevice,
                    activeDevice = RealActiveDevice(transport, watchConnector),
                    negotiating = state is ConnectingPebbleState.Negotiating,
                    rebootingAfterFirmwareUpdate = lastFirmwareUpdateState !is FirmwareUpdateStatus.NotInProgress,
                )
            }

            else -> {
                val leScanRecord = scanResult?.leScanRecord
                when {
                    leScanRecord != null && transport is BleTransport ->
                        RealBleDiscoveredPebbleDevice(
                            pebbleDevice = pebbleDevice,
                            pebbleScanRecord = leScanRecord,
                            rssi = scanResult.rssi,
                        )

                    knownDevice != null -> knownDevice

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
    override fun connect(uiContext: UIContext?) {
        watchConnector.requestConnection(transport, uiContext)
    }

    override fun toString(): String = transport.toString()
}

internal class RealBleDiscoveredPebbleDevice(
    private val pebbleDevice: PebbleDevice,
    override val pebbleScanRecord: PebbleLeScanRecord,
    override val rssi: Int,
) : PebbleDevice by pebbleDevice, BleDiscoveredPebbleDevice {
    override fun toString(): String =
        "RealBleDiscoveredPebbleDevice: $pebbleDevice / pebbleScanRecord=$pebbleScanRecord / rssi=$rssi"
}

internal class RealKnownPebbleDevice(
    override val runningFwVersion: String,
    override val serial: String,
    private val pebbleDevice: PebbleDevice,
    private val watchConnector: WatchConnector,
    override val lastConnected: Instant,
    override val watchType: WatchHardwarePlatform,
) : KnownPebbleDevice,
    PebbleDevice by pebbleDevice {
    override fun forget() {
        watchConnector.forget(transport)
    }

    override fun toString(): String =
        "KnownPebbleDevice: $pebbleDevice watchType=${watchType.revision} serial=$serial runningFwVersion=$runningFwVersion lastConnected=$lastConnected"
}

internal class RealActiveDevice(
    private val transport: Transport,
    private val watchConnector: WatchConnector,
) : ActiveDevice {
    override fun disconnect() {
        watchConnector.requestDisconnection(transport)
    }

    override fun toString(): String =
        "ActiveDevice: $transport"
}

internal class RealDisconnectingPebbleDevice(
    private val pebbleDevice: PebbleDevice,
) : DisconnectingPebbleDevice, PebbleDevice by pebbleDevice {
    override fun toString(): String =
        "DisconnectingPebbleDevice: $pebbleDevice"
}

internal class RealDisconnectingKnownPebbleDevice(
    private val knownDevice: KnownPebbleDevice,
) : DisconnectingKnownPebbleDevice, KnownPebbleDevice by knownDevice {
    override fun toString(): String =
        "DisconnectingKnownPebbleDevice: $knownDevice"
}

internal class RealConnectingPebbleDevice(
    private val pebbleDevice: PebbleDevice,
    private val activeDevice: ActiveDevice,
    override val negotiating: Boolean,
    override val rebootingAfterFirmwareUpdate: Boolean,
) :
    PebbleDevice by pebbleDevice, ConnectingPebbleDevice, ActiveDevice by activeDevice {
    override fun toString(): String = "ConnectingPebbleDevice: $pebbleDevice"
}

internal class RealConnectingKnownPebbleDevice(
    private val knownDevice: KnownPebbleDevice,
    private val activeDevice: ActiveDevice,
    override val negotiating: Boolean,
    override val rebootingAfterFirmwareUpdate: Boolean,
) : ConnectingKnownPebbleDevice, ActiveDevice by activeDevice, KnownPebbleDevice by knownDevice {
    override fun toString(): String = "ConnectingKnownPebbleDevice: $knownDevice"
}

internal class RealConnectedPebbleDevice(
    override val watchInfo: WatchInfo,
    private val knownDevice: KnownPebbleDevice,
    private val activeDevice: ActiveDevice,
    private val services: ConnectedPebble.Services,
    override val firmwareUpdateState: FirmwareUpdateStatus,
    override val firmwareUpdateAvailable: FirmwareUpdateCheckResult?,
) : ConnectedPebbleDevice,
    KnownPebbleDevice by knownDevice,
    ActiveDevice by activeDevice,
    ConnectedPebble.Debug by services.debug,
    ConnectedPebble.AppRunState by services.appRunState,
    ConnectedPebble.FirmwareUpdate by services.firmware,
    ConnectedPebble.Messages by services.messages,
    ConnectedPebble.Time by services.time,
    ConnectedPebble.AppMessages by services.appMessages,
    ConnectedPebble.Logs by services.logs,
    ConnectedPebble.CoreDump by services.coreDump,
    ConnectedPebble.Music by services.music,
    ConnectedPebble.PKJS by services.pkjs {

    override fun toString(): String =
        "ConnectedPebbleDevice: $knownDevice $watchInfo firmwareUpdateState=$firmwareUpdateState firmwareUpdateAvailable=$firmwareUpdateAvailable"
}

internal class RealConnectedPebbleDeviceInRecovery(
    override val watchInfo: WatchInfo,
    private val knownDevice: KnownPebbleDevice,
    private val activeDevice: ActiveDevice,
    private val services: ConnectedPebble.PrfServices,
    override val firmwareUpdateState: FirmwareUpdateStatus,
    override val firmwareUpdateAvailable: FirmwareUpdateCheckResult?,
) : ConnectedPebbleDeviceInRecovery,
    KnownPebbleDevice by knownDevice,
    ActiveDevice by activeDevice,
    ConnectedPebble.FirmwareUpdate by services.firmware,
    ConnectedPebble.Logs by services.logs,
    ConnectedPebble.CoreDump by services.coreDump {

    override fun toString(): String =
        "ConnectedPebbleDeviceInRecovery: $knownDevice $watchInfo firmwareUpdateState=$firmwareUpdateState firmwareUpdateAvailable=$firmwareUpdateAvailable"
}
