package io.rebble.libpebblecommon.connection.bt.ble.pebble

import co.touchlab.kermit.Logger
import com.juul.kable.GattStatusException
import io.rebble.libpebblecommon.connection.bt.ble.transport.ConnectedGattClient
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class BatteryWatcher(
    private val scope: ConnectionCoroutineScope,
)  {
    private val logger = Logger.withTag("BatteryWatcher")
    private val _battery = MutableStateFlow<Int?>(null)
    val batteryLevel: StateFlow<Int?> = _battery.asStateFlow()

    suspend fun subscribe(gattClient: ConnectedGattClient) {
        val batterySub = gattClient.subscribeToCharacteristic(BATTERY_SERVICE_UUID, BATTERY_LEVEL_CHARACTERISTIC_UUID)
        if (batterySub == null) {
            logger.e("batterySub is null")
            return
        }
        scope.launch {
            // There is some weird timing thing where this throws a GATT error "Authorization is
            // insufficient", if done too early.
            delay(2.seconds)
            if (!batterySub.collectBatteryChanges()) {
                delay(10.seconds)
                logger.i { "retrying batterySub.collectBatteryChanges()" }
                if (!batterySub.collectBatteryChanges()) {
                    logger.w { "failed to subscribe to battery changes after retry" }
                }
            }
        }
        val batteryLevel = try {
            gattClient.readCharacteristic(BATTERY_SERVICE_UUID, BATTERY_LEVEL_CHARACTERISTIC_UUID)?.get(0)?.toInt()
        } catch (e: GattStatusException) {
            logger.e(e) { "failed to read battery level" }
            null
        }
        logger.v { "initial batteryLevel = $batteryLevel" }
        batteryLevel?.let { _battery.value = it }
    }

    private suspend fun Flow<ByteArray>.collectBatteryChanges(): Boolean {
        try {
            collect {
                _battery.value =
                    it[0].toInt().also { logger.v { "battery level changed: $it" } }
            }
            return true
        } catch (e: GattStatusException) {
            // Android
            logger.e(e) { "batterySub.collect ${e.message}" }
            return false
        } catch (e: IOException) {
            // iOS
            logger.e(e) { "batterySub.collect ${e.message}" }
            return false
        }
    }

    companion object {
        private const val BLUETOOTH_BASE_UUID_POSTFIX = "0000-1000-8000-00805F9B34FB"
        private val BATTERY_SERVICE_UUID: Uuid = Uuid.parse("0000180F-$BLUETOOTH_BASE_UUID_POSTFIX")
        private val BATTERY_LEVEL_CHARACTERISTIC_UUID: Uuid = Uuid.parse("00002A19-$BLUETOOTH_BASE_UUID_POSTFIX")
    }
}