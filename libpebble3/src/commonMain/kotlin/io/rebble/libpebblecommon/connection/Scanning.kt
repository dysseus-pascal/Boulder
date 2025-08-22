package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import com.juul.kable.ManufacturerData
import com.oldguy.common.getShortAt
import io.rebble.libpebblecommon.connection.bt.BluetoothState
import io.rebble.libpebblecommon.connection.bt.BluetoothStateProvider
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebbleLeScanRecord
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebbleLeScanRecord.Companion.decodePebbleScanRecord
import io.rebble.libpebblecommon.connection.bt.ble.transport.BleScanner
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

data class BleScanResult(
    val identifier: PebbleBleIdentifier,
    val name: String,
    val rssi: Int,
    val manufacturerData: ManufacturerData,
)

data class PebbleScanResult(
    val identifier: PebbleBleIdentifier,
    val name: String,
    val rssi: Int,
    val leScanRecord: PebbleLeScanRecord?,
)

class RealScanning(
    private val watchConnector: WatchConnector,
    private val bleScanner: BleScanner,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
    private val bluetoothStateProvider: BluetoothStateProvider,
) : Scanning {
    private var bleScanJob: Job? = null
    override val bluetoothEnabled: StateFlow<BluetoothState> = bluetoothStateProvider.state
    private val _isBleScanning = MutableStateFlow(false)
    override val isScanningBle: StateFlow<Boolean> = _isBleScanning.asStateFlow()

    override fun startBleScan() {
        Logger.d("startBleScan")
        bleScanJob?.cancel()
        watchConnector.clearScanResults()
        val scanResults = bleScanner.scan()
        _isBleScanning.value = true
        bleScanJob = libPebbleCoroutineScope.launch {
            launch {
                delay(BLE_SCANNING_TIMEOUT)
                stopBleScan()
            }
            scanResults.collect {
                if (it.manufacturerData.code !in VENDOR_IDS) {
                    return@collect
                }
                val pebbleScanRecord = it.manufacturerData.data.decodePebbleScanRecord()
                val device = PebbleScanResult(
                    identifier = it.identifier,
                    name = it.name,
                    rssi = it.rssi,
                    leScanRecord = pebbleScanRecord,
                )
                watchConnector.addScanResult(device)
            }
        }
    }

    override fun stopBleScan() {
        Logger.d("stopBleScan")
        bleScanJob?.cancel()
        _isBleScanning.value = false
    }

    override fun startClassicScan() {

    }

    override fun stopClassicScan() {

    }

    companion object {
        val PEBBLE_VENDOR_ID = byteArrayOf(0x54, 0x01).getShortAt(0).toInt()
        val CORE_VENDOR_ID = byteArrayOf(0xEA.toByte(), 0x0E).getShortAt(0).toInt()
        val VENDOR_IDS = listOf(PEBBLE_VENDOR_ID, CORE_VENDOR_ID)
        private val BLE_SCANNING_TIMEOUT = 30.seconds    }
}
