package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

class WatchManager(
    private val pebbleConnector: PebbleConnector,
) {
    private val scanResults = MutableStateFlow<Map<Transport, PebbleDevice>>(emptyMap())
    private val knownDevices = MutableStateFlow<Map<Transport, PebbleDevice>>(emptyMap())
    private val _watches = MutableStateFlow<List<PebbleDevice>>(emptyList())
    val watches: StateFlow<List<PebbleDevice>> = _watches

    fun init() {
        GlobalScope.async {
            scanResults.combine(knownDevices) { scanResults, knownDevices ->
                // Known device takes priority over scan result for the same device
                scanResults.toMutableMap().apply {
                    putAll(knownDevices)
                }.values.toList()
            }.collect {
                _watches.value = it
            }
        }
    }

    fun addScanResult(device: PebbleDevice) {
        Logger.d("addScanResult: $device")
        scanResults.value = scanResults.value.toMutableMap().apply {
            put(device.transport, device)
        }
    }

    fun updateDeviceState(device: PebbleDevice) {
        Logger.d("updateDeviceState: $device")
        knownDevices.value = knownDevices.value.toMutableMap().apply {
            put(device.transport, device)
        }
    }

    fun connectTo(pebbleDevice: PebbleDevice) {
        val existingDevice = knownDevices.value[pebbleDevice.transport]
        if (existingDevice != null && existingDevice is ActiveDevice) {
            Logger.d("Already connecting to $pebbleDevice")
            return
        }
        val scope = GlobalScope // TODO scope for each device, tear it down on disconnect
        pebbleConnector.connect(pebbleDevice, scope, ::updateDeviceState)
    }
}
