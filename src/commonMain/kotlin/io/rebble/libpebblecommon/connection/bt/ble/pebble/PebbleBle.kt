package io.rebble.libpebblecommon.connection.bt.ble.pebble

import co.touchlab.kermit.Logger
import io.ktor.utils.io.ByteChannel
import io.rebble.libpebblecommon.connection.LibPebbleConfig
import io.rebble.libpebblecommon.connection.PebbleConnectionResult
import io.rebble.libpebblecommon.connection.PebbleDevice
import io.rebble.libpebblecommon.connection.Transport
import io.rebble.libpebblecommon.connection.Transport.BluetoothTransport.BleTransport
import io.rebble.libpebblecommon.connection.TransportConnector
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.DEFAULT_MTU
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.GATT_SERVICES
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.MAX_RX_WINDOW
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.MAX_TX_WINDOW
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.SERVER_META_RESPONSE
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.TARGET_MTU
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.PPOGATT_DEVICE_CHARACTERISTIC_SERVER
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.PPOGATT_DEVICE_SERVICE_UUID_SERVER
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoG
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoGPacketSender
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattConnector
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattServer
import io.rebble.libpebblecommon.connection.bt.ble.transport.openGattServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class PebbleBle(
    private val config: LibPebbleConfig,
    private val transport: BleTransport,
    private val scope: CoroutineScope,
    private val gattConnector: GattConnector,
) : TransportConnector {
    private val logger = Logger.withTag("PebbleBle/${transport.identifier.asString}")

    override suspend fun connect(): PebbleConnectionResult =
        withContext(Dispatchers.Main) {
            val inboundPPoGBytesChannel = Channel<ByteArray>(capacity = 100)

            logger.d("connect() reversedPPoG = ${config.bleConfig.reversedPPoG}")
            val ppogPacketSender: PPoGPacketSender = if (config.bleConfig.reversedPPoG) {
                PpogClient(inboundPPoGBytesChannel, scope)
            } else {
                val gs = gattServer
                check(gs != null)
                gs.registerDevice(transport, inboundPPoGBytesChannel)
                object : PPoGPacketSender {
                    override suspend fun sendPacket(packet: ByteArray): Boolean {
                        return gattServer?.sendData(
                            transport = transport,
                            serviceUuid = PPOGATT_DEVICE_SERVICE_UUID_SERVER,
                            characteristicUuid = PPOGATT_DEVICE_CHARACTERISTIC_SERVER,
                            data = packet
                        ) ?: false
                    }
                }
            }

            val inboundPPChannel = ByteChannel()
            val outboundPPChannel = Channel<ByteArray>(capacity = 100)

            val ppog = PPoG(
                inboundPPBytes = inboundPPChannel,
                outboundPPBytes = outboundPPChannel,
                inboundPacketData = inboundPPoGBytesChannel,
                pPoGPacketSender = ppogPacketSender,
                initialMtu = DEFAULT_MTU,
                desiredTxWindow = MAX_TX_WINDOW,
                desiredRxWindow = MAX_RX_WINDOW,
                bleConfig = config.bleConfig,
            )

            val device = gattConnector.connect()
            if (device == null) {
                logger.d("pebbleble: null device")
                return@withContext PebbleConnectionResult.Failed("failed to connect")
            }
            val services = device.discoverServices()
            logger.d("services = $services")

            val connectionParams = ConnectionParams(device, scope)
            if (!connectionParams.subscribeAndConfigure()) {
                // this can happen on some older firmwares (PRF?)
                logger.i("error setting up connection params")
            }
            logger.d("done connectionParams")

            val mtuParam = Mtu(device, scope)
            if (!mtuParam.subscribe()) {
                logger.w("failed to subscribe to mtu")
            }
            logger.d("subscribed mtu")

            scope.launch {
                mtuParam.mtu.collect { newMtu ->
                    logger.d("newMtu = $newMtu")
                    ppog.updateMtu(newMtu)
                }
            }
            mtuParam.update(TARGET_MTU)
            logger.d("done mtu update")

            val connectivity = ConnectivityWatcher(device, scope)
            if (!connectivity.subscribe()) {
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
                val pairing = PebblePairing(
                    device,
                    config.context,
                    transport,
                    connectivity.status,
                    config.bleConfig,
                )
                pairing.requestPairing(connectionStatus)
            }

            if (ppogPacketSender is PpogClient) {
                // TODO do this better if it works
                ppogPacketSender.init(device)
            }

            ppog.run(scope)
            return@withContext PebbleConnectionResult.Success(
                inboundPPBytes = inboundPPChannel,
                outboundPPBytes = outboundPPChannel,
            )
            // TODO update PPoG with new MTU whenever we get it
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
                    gattServer?.addServices(GATT_SERVICES)
                    gattServer?.characteristicReadRequest?.collect {
                        Logger.d("sending meta response")
                        it.respond(SERVER_META_RESPONSE)
                    }
                }
            }
        }
    }
}
