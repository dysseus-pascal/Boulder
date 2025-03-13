package io.rebble.libpebblecommon.ble.pebble

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.ble.pebble.LEConstants.GATT_SERVICES
import io.rebble.libpebblecommon.ble.pebble.LEConstants.SERVER_META_RESPONSE
import io.rebble.libpebblecommon.ble.transport.impl.bleScanner
import io.rebble.libpebblecommon.ble.transport.impl.gattConnector
import io.rebble.libpebblecommon.ble.transport.GattServer
import io.rebble.libpebblecommon.ble.transport.openGattServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

expect class AppContext

data class BleConfig(
    val roleReversal: Boolean,
    val context: AppContext,
)

//expect
class PebbleBle(
    val config: BleConfig,
) {
    var gattServer: GattServer? = null

    suspend fun init() {
        val scope = CoroutineScope(coroutineContext)
        if (!config.roleReversal) {
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

    suspend fun connect(scannedPebbleDevice: ScannedPebbleDevice, scope: CoroutineScope) {
        val connector = gattConnector(scannedPebbleDevice)
        val device = connector.connect()
        if (device == null) {
            Logger.d("null device")
            return
        }
        val services = device.discoverServices()
        Logger.d("services = $services")

        // TODO sub connection params

        val connectivity = ConnectivityWatcher(device)
        if (!connectivity.subscribe()) {
            Logger.d("failed to subscribe to connectivity")
            // TODO disconnect
            return
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
    }

    suspend fun scan(): Flow<ScannedPebbleDevice> {
        return bleScanner().scan(namePrefix = "Pebble")
    }

    companion object {
        private val CONNECTIVITY_UPDATE_TIMEOUT = 10000L
    }
}
