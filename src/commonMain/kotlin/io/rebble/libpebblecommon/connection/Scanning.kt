package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import com.juul.kable.ManufacturerData
import com.oldguy.common.getShortAt
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebbleBle
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebbleLeScanRecord.Companion.decodePebbleScanRecord
import io.rebble.libpebblecommon.connection.bt.ble.transport.BleScanner
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class BleScanResult(
    val name: String,
    val transport: Transport,
    val rssi: Int,
    val manufacturerData: ManufacturerData,
)

class RealScanning(
    private val watchManager: WatchManager,
    private val bleScanner: BleScanner,
) : Scanning {
    private var bleScanJob: Job? = null

    override suspend fun startBleScan() {
        // TODO add timeout
        Logger.d("startBleScan")
        bleScanJob?.cancel()
        val scanResults = bleScanner.scan("Pebble" /* TODO remove? */)
        bleScanJob = GlobalScope.launch {
            scanResults.collect {
                if (it.manufacturerData.code != PEBBLE_VENDOR_ID) {
                    return@collect
                }
                val pebbleScanRecord = it.manufacturerData.data.decodePebbleScanRecord()
                val device = RealBleDiscoveredPebbleDevice(
                    pebbleDevice = RealPebbleDevice(
                        name = it.name,
                        transport = it.transport,
                        watchManager = watchManager,
                    ),
                    pebbleScanRecord = pebbleScanRecord,
                )
                watchManager.addScanResult(device)
            }
        }
    }

    override suspend fun stopBleScan() {
        Logger.d("stopBleScan")
        bleScanJob?.cancel()
    }

    override suspend fun startClassicScan() {

    }

    override suspend fun stopClassicScan() {

    }

    companion object {
        val PEBBLE_VENDOR_ID = byteArrayOf(0x54, 0x01).getShortAt(0).toInt()
    }
}
