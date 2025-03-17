package io.rebble.libpebblecommon.connection.bt.ble.pebble

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.BleConfig
import io.rebble.libpebblecommon.connection.LibPebbleConfig
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
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

class PebbleBle(
    val config: LibPebbleConfig,
) {
    var gattServer: GattServer? = null

    suspend fun init() {
        val scope = CoroutineScope(coroutineContext)
        if (!config.bleConfig.roleReversal) {
            gattServer = openGattServer(config.context)
            gattServer?.addServices(GATT_SERVICES)
            scope.async {
                gattServer?.characteristicReadRequest?.collect {
                    Logger.d("sending meta response")
                    it.respond(SERVER_META_RESPONSE)
                }
            }
        }
    }

    suspend fun connect(scannedPebbleDevice: ScannedPebbleDevice, scope: CoroutineScope) = withContext(Dispatchers.Main) {
        val connector = gattConnector(scannedPebbleDevice, config.context)

        val inboundPPoGBytesChannel = Channel<ByteArray>(capacity = 100)
        gattServer?.registerDevice(scannedPebbleDevice, inboundPPoGBytesChannel)

        val inboundPPChannel = Channel<ByteArray>(capacity = 100)
        val outboundPPChannel = Channel<ByteArray>(capacity = 100)

        val ppog = PPoG(
            inboundPPBytes = inboundPPChannel,
            outboundPPBytes = outboundPPChannel,
            inboundPacketData = inboundPPoGBytesChannel,
            pPoGPacketSender = object : PPoGPacketSender {
                override suspend fun sendPacket(packet: ByteArray): Boolean {
                    return gattServer?.sendData(scannedPebbleDevice, PPOGATT_DEVICE_SERVICE_UUID_SERVER, PPOGATT_DEVICE_CHARACTERISTIC_SERVER, packet) ?: false
                }
            },
            initialMtu = DEFAULT_MTU,
            desiredTxWindow = MAX_TX_WINDOW,
            desiredRxWindow = MAX_RX_WINDOW,
        )

        scope.async {
            while (true) {
                val bytes = inboundPPChannel.receive()
                Logger.d("PP bytes: ${bytes.joinToString()}")
            }
        }

        val device = connector.connect()
        if (device == null) {
            Logger.d("null device")
            return@withContext
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
            return@withContext
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
            val pairing = PebblePairing(device, config.context, scannedPebbleDevice)
            pairing.requestPairing(connectionStatus)
        }

        ppog.run(scope)
        // TODO update PPoG with new MTU whenever we get it
    }

    suspend fun scan(): Flow<ScannedPebbleDevice> {
        return bleScanner().scan(namePrefix = "Pebble")
    }

    companion object {
        private val CONNECTIVITY_UPDATE_TIMEOUT = 10000L
    }
}
