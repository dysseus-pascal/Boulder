package io.rebble.libpebblecommon.connection.bt.ble.pebble

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.BleConfigFlow
import io.rebble.libpebblecommon.connection.PebbleConnectionResult
import io.rebble.libpebblecommon.connection.Transport.BluetoothTransport.BleTransport
import io.rebble.libpebblecommon.connection.TransportConnector
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.TARGET_MTU
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoG
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoGPacketSender
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoGStream
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattConnector
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattServerManager
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class PebbleBle(
    private val config: BleConfigFlow,
    private val transport: BleTransport,
    private val scope: ConnectionCoroutineScope,
    private val gattConnector: GattConnector,
    private val ppog: PPoG,
    private val ppogPacketSender: PPoGPacketSender,
    private val pPoGStream: PPoGStream,
    private val connectionParams: ConnectionParams,
    private val mtuParam: Mtu,
    private val connectivity: ConnectivityWatcher,
    private val pairing: PebblePairing,
    private val gattServerManager: GattServerManager,
) : TransportConnector {
    private val logger = Logger.withTag("PebbleBle/${transport.identifier.asString}")

    override suspend fun connect(): PebbleConnectionResult {
        logger.d("connect() reversedPPoG = ${config.value.reversedPPoG}")
        if (!config.value.reversedPPoG) {
            if (!gattServerManager.registerDevice(transport, pPoGStream.inboundPPoGBytesChannel)) {
                return PebbleConnectionResult.Failed("failed to register with gatt server")
            }
        }

        val device = gattConnector.connect()
        if (device == null) {
            logger.d("pebbleble: null device")
            return PebbleConnectionResult.Failed("failed to connect")
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
            return PebbleConnectionResult.Failed("failed to subscribe to connectivity")
        }
        logger.d("subscribed connectivity d")
        val connectionStatus = withTimeoutOrNull(CONNECTIVITY_UPDATE_TIMEOUT) {
            connectivity.status.first()
        }
        if (connectionStatus == null) {
            logger.d("failed to get connection status")
            return PebbleConnectionResult.Failed("failed to get connection status")
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

        ppog.run()
        return PebbleConnectionResult.Success
    }

    override suspend fun disconnect() {
        ppog.close()
        gattConnector.disconnect()
        gattServerManager.unregisterDevice(transport)
    }

    override val disconnected = gattConnector.disconnected

    companion object {
        private val CONNECTIVITY_UPDATE_TIMEOUT = 10000L
    }
}

expect val SERVER_META_RESPONSE: ByteArray