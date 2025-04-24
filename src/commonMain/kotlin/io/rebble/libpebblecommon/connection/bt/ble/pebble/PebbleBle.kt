package io.rebble.libpebblecommon.connection.bt.ble.pebble

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.LibPebbleConfig
import io.rebble.libpebblecommon.connection.PebbleConnectionResult
import io.rebble.libpebblecommon.connection.Transport.BluetoothTransport.BleTransport
import io.rebble.libpebblecommon.connection.TransportConnector
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.TARGET_MTU
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoG
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoGPacketSender
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoGStream
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattConnector
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattServer
import io.rebble.libpebblecommon.connection.bt.ble.transport.openGattServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class PebbleBle(
    private val config: LibPebbleConfig,
    private val transport: BleTransport,
    private val scope: CoroutineScope,
    private val gattConnector: GattConnector,
    private val ppog: PPoG,
    private val ppogPacketSender: PPoGPacketSender,
    private val pPoGStream: PPoGStream,
    private val connectionParams: ConnectionParams,
    private val mtuParam: Mtu,
    private val connectivity: ConnectivityWatcher,
    private val pairing: PebblePairing,
) : TransportConnector {
    private val logger = Logger.withTag("PebbleBle/${transport.identifier.asString}")

    override suspend fun connect(): PebbleConnectionResult =
        withContext(Dispatchers.Main) {
            logger.d("connect() reversedPPoG = ${config.bleConfig.reversedPPoG}")
            if (!config.bleConfig.reversedPPoG) {
                val gs = gattServer
                check(gs != null)
                gs.registerDevice(transport, pPoGStream.inboundPPoGBytesChannel)
            }

            val device = gattConnector.connect()
            if (device == null) {
                logger.d("pebbleble: null device")
                return@withContext PebbleConnectionResult.Failed("failed to connect")
            }
            val services = device.discoverServices()
            logger.d("services = $services")

            if (!connectionParams.subscribeAndConfigure(device)) {
                // this can happen on some older firmwares (PRF?)
                logger.i("error setting up connection params")
            }
            logger.d("done connectionParams")

            scope.launch {
                mtuParam.mtu.collect { newMtu ->
                    logger.d("newMtu = $newMtu")
                    ppog.updateMtu(newMtu)
                }
            }
            mtuParam.update(device, TARGET_MTU)
            logger.d("done mtu update")

            if (!connectivity.subscribe(device)) {
                logger.d("failed to subscribe to connectivity")
                return@withContext PebbleConnectionResult.Failed("failed to subscribe to connectivity")
            }
            logger.d("subscribed connectivity d")
            val connectionStatus = withTimeout(CONNECTIVITY_UPDATE_TIMEOUT) {
                connectivity.status.first()
            }
            logger.d("connectionStatus = $connectionStatus")

            val needToPair = if (connectionStatus.paired) {
                if (device.isBonded()) {
                    logger.d("already paired")
                    false
                } else {
                    logger.d("watch thinks it is paired, phone does not")
                    true
                }
            } else {
                if (device.isBonded()) {
                    logger.d("phone thinks it is paired, watch does not")
                    true
                } else {
                    logger.d("needs pairing")
                    true
                }
            }

            if (needToPair) {
                pairing.requestPairing(device, connectionStatus, connectivity.status)
            }

            if (ppogPacketSender is PpogClient) {
                // TODO do this better if it works
                // FIXME
                ppogPacketSender.init(device)
            }

            ppog.run(scope)
            return@withContext PebbleConnectionResult.Success
        }

    override suspend fun disconnect() {
        gattConnector.disconnect()
        gattServer?.unregisterDevice(transport)
    }

    override val disconnected = gattConnector.disconnected

    companion object {
        private val CONNECTIVITY_UPDATE_TIMEOUT = 10000L

        var gattServer: GattServer? = null

        fun init(config: LibPebbleConfig) {
            GlobalScope.launch {
                if (!config.bleConfig.reversedPPoG) {
                    check(gattServer == null)
                    gattServer = openGattServer(config.context)
                    gattServer?.addServices()
                    gattServer?.characteristicReadRequest?.collect {
                        Logger.d("sending meta response")
                        it.respond(SERVER_META_RESPONSE)
                    }
                }
            }
        }
    }
}

expect val SERVER_META_RESPONSE: ByteArray