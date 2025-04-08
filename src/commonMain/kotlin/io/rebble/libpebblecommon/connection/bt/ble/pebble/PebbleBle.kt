package io.rebble.libpebblecommon.connection.bt.ble.pebble

import co.touchlab.kermit.Logger
import io.ktor.utils.io.ByteChannel
import io.rebble.libpebblecommon.connection.LibPebbleConfig
import io.rebble.libpebblecommon.connection.PebbleConnectionResult
import io.rebble.libpebblecommon.connection.PebbleDevice
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
    private val pebbleDevice: PebbleDevice,
    private val scope: CoroutineScope,
    private val gattConnector: GattConnector,
) : TransportConnector {
    override suspend fun connect(): PebbleConnectionResult =
        withContext(Dispatchers.Main) {
            val transport = pebbleDevice.transport
            check(transport is BleTransport)

            val inboundPPoGBytesChannel = Channel<ByteArray>(capacity = 100)

            Logger.d("connect() roleReversal = ${config.bleConfig.roleReversal}")
            val ppogPacketSender: PPoGPacketSender = if (config.bleConfig.roleReversal) {
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
            )

            val device = gattConnector.connect()
            if (device == null) {
                Logger.d("pebbleble: null device")
                return@withContext PebbleConnectionResult.Failed("failed to connect")
            }
            val services = device.discoverServices()
            Logger.d("services = $services")

            val connectionParams = ConnectionParams(device, scope)
            if (!connectionParams.subscribeAndConfigure()) {
                // this can happen on some older firmwares (PRF?)
                Logger.i("error setting up connection params")
            }
            Logger.d("done connectionParams")

            val mtuParam = Mtu(device, scope)
            if (!mtuParam.subscribe()) {
                Logger.w("failed to subscribe to mtu")
            }
            Logger.d("subscribed mtu")

            scope.launch {
                mtuParam.mtu.collect { newMtu ->
                    Logger.d("newMtu = $newMtu")
                    ppog.updateMtu(newMtu)
                }
            }
            mtuParam.update(TARGET_MTU)
            Logger.d("done mtu update")

            val connectivity = ConnectivityWatcher(device, scope)
            if (!connectivity.subscribe()) {
                Logger.d("failed to subscribe to connectivity")
                return@withContext PebbleConnectionResult.Failed("failed to subscribe to connectivity")
            }
            Logger.d("subscribed connectivity d")
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
                    Logger.d("phone thinks it is paired, watch does not")
                    true
                } else {
                    Logger.d("needs pairing")
                    true
                }
            }

            if (needToPair) {
                val pairing = PebblePairing(
                    device,
                    config.context,
                    pebbleDevice,
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
        val transport = pebbleDevice.transport
        check(transport is BleTransport)
        gattServer?.unregisterDevice(transport)
    }

    override val disconnected = gattConnector.disconnected

    companion object {
        private val CONNECTIVITY_UPDATE_TIMEOUT = 10000L

        var gattServer: GattServer? = null

        fun init(config: LibPebbleConfig) {
            GlobalScope.launch {
                if (!config.bleConfig.roleReversal) {
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
