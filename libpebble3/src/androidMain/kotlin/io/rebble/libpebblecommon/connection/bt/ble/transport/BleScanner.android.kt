//package io.rebble.libpebblecommon.ble.transport
//
//import android.bluetooth.BluetoothAdapter
//import android.bluetooth.le.ScanCallback
//import android.bluetooth.le.ScanResult
//import co.touchlab.kermit.Logger
//import io.rebble.libpebblecommon.ble.pebble.ScannedPebbleDevice
//import kotlinx.coroutines.channels.awaitClose
//import kotlinx.coroutines.flow.Flow
//import kotlinx.coroutines.flow.callbackFlow
//
//actual fun libpebbleBleScanner(): BleScanner = LibpebbleBleScanner()
//
//class LibpebbleBleScanner : BleScanner {
//    override suspend fun scan(namePrefix: String): Flow<ScannedPebbleDevice> {
//        val adapter = BluetoothAdapter.getDefaultAdapter()
//        val scanner = adapter.bluetoothLeScanner
//        return callbackFlow {
//            val callback = object : ScanCallback() {
//                override fun onScanResult(callbackType: Int, result: ScanResult) {
//                    val address = result.device.address
//                    val name = result.device.name
////                    Logger.d("scanresult: address=${result.device.address} name=${result.device.name}")
//                    if (address == null || name == null) {
//                        return
//                    }
//                    if (name.startsWith(namePrefix)) {
////                        scanner.stopScan(this)
//                        trySend(ScannedPebbleDevice(address))
//                        channel.close()
//                    }
//                }
//
//                override fun onBatchScanResults(results: MutableList<ScanResult>?) {
//                    TODO("not implemented")
//                }
//
//                override fun onScanFailed(errorCode: Int) {
//                    Logger.e("onScanFailed: $errorCode")
//                    channel.close()
//                }
//            }
//            scanner.startScan(callback)
//            awaitClose { scanner.stopScan(callback) }
//        }
//
//    }
//}
