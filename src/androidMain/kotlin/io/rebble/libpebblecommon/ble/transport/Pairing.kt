package io.rebble.libpebblecommon.ble.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.IntentFilter
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.ble.pebble.AppContext
import io.rebble.libpebblecommon.ble.pebble.ScannedPebbleDevice
import io.rebble.libpebblecommon.util.asFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

actual fun isBonded(scannedPebbleDevice: ScannedPebbleDevice): Boolean {
    val adapter = BluetoothAdapter.getDefaultAdapter()
    val macAddress = scannedPebbleDevice.device.identifier.toString()
    val device = adapter.getRemoteDevice(macAddress)
    try {
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            return true
        }
        // Sometimes getBondState() lies - check if it says false
        val bondedDevices = adapter.bondedDevices.toSet().filterNotNull()
        if (bondedDevices.any { macAddress.equals(it.address, ignoreCase = true) }) {
            return true
        }
    } catch (e: SecurityException) {
        Logger.e("error checking bond state")
    }
    // TODO null or something?
    return false
}

actual fun getBluetoothDevicePairEvents(context: AppContext, address: String): Flow<BluetoothDevicePairEvent> {
    return IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED).asFlow(context.context)
        .map {
            BluetoothDevicePairEvent(
                it.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)!!,
                it.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE),
                it.getIntExtra("android.bluetooth.device.extra.REASON", -1).takeIf { it != -1 }
            )
        }
        .filter {
            it.address == address
        }
}

actual fun createBond(scannedPebbleDevice: ScannedPebbleDevice): Boolean {
    Logger.d("createBond()")
    val adapter = BluetoothAdapter.getDefaultAdapter()
    val macAddress = scannedPebbleDevice.device.identifier.toString()
    val device = adapter.getRemoteDevice(macAddress)
    return try {
        device.createBond()
    } catch (e: SecurityException) {
        Logger.e("failed to create bond", e)
        false
    }
}
