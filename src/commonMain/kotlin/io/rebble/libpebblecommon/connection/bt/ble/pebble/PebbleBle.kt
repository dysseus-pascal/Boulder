package io.rebble.libpebblecommon.connection.bt.ble.pebble

import co.touchlab.kermit.Logger
import io.ktor.utils.io.ByteChannel
import io.rebble.libpebblecommon.connection.BleDiscoveredPebbleDevice
import io.rebble.libpebblecommon.connection.BleScanResult
import io.rebble.libpebblecommon.connection.LibPebbleConfig
import io.rebble.libpebblecommon.connection.PebbleConnectionResult
import io.rebble.libpebblecommon.connection.PebbleDevice
import io.rebble.libpebblecommon.connection.RealBleDiscoveredPebbleDevice
import io.rebble.libpebblecommon.connection.RealPebbleDevice
import io.rebble.libpebblecommon.connection.Transport.BluetoothTransport.BleTransport
import io.rebble.libpebblecommon.connection.TransportConnector
import io.rebble.libpebblecommon.connection.WatchManager
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.DEFAULT_MTU
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.GATT_SERVICES
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.MAX_RX_WINDOW
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.MAX_TX_WINDOW
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.SERVER_META_RESPONSE
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.PPOGATT_DEVICE_CHARACTERISTIC_SERVER
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.PPOGATT_DEVICE_SERVICE_UUID_SERVER
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoG
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoGPacketSender
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattServer
import io.rebble.libpebblecommon.connection.bt.ble.transport.bleScanner
import io.rebble.libpebblecommon.connection.bt.ble.transport.gattConnector
import io.rebble.libpebblecommon.connection.bt.ble.transport.openGattServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class PebbleBle(
    val config: LibPebbleConfig,
) : TransportConnector {
    var gattServer: GattServer? = null

    fun init() {
        GlobalScope.async {
            if (!config.bleConfig.roleReversal) {
                gattServer = openGattServer(config.context)
                gattServer?.addServices(GATT_SERVICES)
                gattServer?.characteristicReadRequest?.collect {
                    Logger.d("sending meta response")
                    it.respond(SERVER_META_RESPONSE)
                }
            }
        }
    }

    override suspend fun connect(
        pebbleDevice: PebbleDevice,
        scope: CoroutineScope,
    ): PebbleConnectionResult =
        withContext(Dispatchers.Main) {
            val transport = pebbleDevice.transport
            check(transport is BleTransport)
            val connector = gattConnector(transport, config.context)

            val inboundPPoGBytesChannel = Channel<ByteArray>(capacity = 100)
            gattServer?.registerDevice(transport, inboundPPoGBytesChannel)

            val inboundPPChannel = ByteChannel()
            val outboundPPChannel = Channel<ByteArray>(capacity = 100)

            val ppog = PPoG(
                inboundPPBytes = inboundPPChannel,
                outboundPPBytes = outboundPPChannel,
                inboundPacketData = inboundPPoGBytesChannel,
                pPoGPacketSender = object : PPoGPacketSender {
                    override suspend fun sendPacket(packet: ByteArray): Boolean {
                        return gattServer?.sendData(
                            transport,
                            PPOGATT_DEVICE_SERVICE_UUID_SERVER,
                            PPOGATT_DEVICE_CHARACTERISTIC_SERVER,
                            packet
                        ) ?: false
                    }
                },
                initialMtu = DEFAULT_MTU,
                desiredTxWindow = MAX_TX_WINDOW,
                desiredRxWindow = MAX_RX_WINDOW,
            )

            val device = connector.connect()
            if (device == null) {
                Logger.d("null device")
                return@withContext PebbleConnectionResult.Failed("failed to connect")
            }
            val services = device.discoverServices()
            Logger.d("services = $services")

            val connectionParams = ConnectionParams(device)
            if (!connectionParams.subscribeAndConfigure()) {
                // this can happen on some older firmwares (PRF?)
                Logger.i("error setting up connection params")
            }

            val connectivity = ConnectivityWatcher(device)
            if (!connectivity.subscribe()) {
                Logger.d("failed to subscribe to connectivity")
                // TODO disconnect
                return@withContext PebbleConnectionResult.Failed("failed to subscribe to connectivity")
            }
            val connectionStatus = withTimeout(CONNECTIVITY_UPDATE_TIMEOUT) {
                connectivity.status.first()
            }
            Logger.d("connectionStatus = $connectionStatus")

            val needToPair = if (connectionStatus.paired) {
                if (device.isBonded()) {
                    Logger.d("already paired")
                    false
                } else {
                    Logger.d("watch thinks it is paired, phone does not")
                    true
                }
            } else {
                if (device.isBonded()) {
                    Logger.d("phone thinks it is paired, watch does not; unpairing on phone")
                    // TODO removeBond()
                    true
                } else {
                    Logger.d("needs pairing")
                    true
                }
            }

            if (needToPair) {
                val pairing = PebblePairing(device, config.context, pebbleDevice)
                pairing.requestPairing(connectionStatus)
            }

            ppog.run(scope)
            return@withContext PebbleConnectionResult.Success(
                inboundPPBytes = inboundPPChannel,
                outboundPPBytes = outboundPPChannel,
            )
            // TODO update PPoG with new MTU whenever we get it
        }

    suspend fun scan(prefix: String?): Flow<BleScanResult> {
        return bleScanner().scan(namePrefix = prefix)
    }

    companion object {
        private val CONNECTIVITY_UPDATE_TIMEOUT = 10000L
    }
}
