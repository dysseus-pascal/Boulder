package io.rebble.libpebblecommon.connection

import android.bluetooth.BluetoothAdapter
import android.companion.CompanionDeviceManager
import android.os.Build
import co.touchlab.kermit.Logger

private val logger = Logger.withTag("ConnectionFailureHandler.android")

actual fun AppContext.handleMtuGattError(identifier: PebbleIdentifier) {
    logger.i { "handleMtuGattError: unpair device" }
    unpairDevice(identifier)
}

actual fun AppContext.handleGattInsufficientAuth(identifier: PebbleIdentifier) {
    logger.i { "handleGattInsufficientAuth: unpair device" }
    unpairDevice(identifier)
}

actual fun AppContext.handleCreateBondFailed(identifier: PebbleIdentifier) {
    logger.i { "handleCreateBondFailed: unpair device" }
    unpairDevice(identifier)
}

private fun AppContext.unpairDevice(identifier: PebbleIdentifier) {
    if (identifier !is PebbleBleIdentifier) return
    val service = context.getSystemService(CompanionDeviceManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
        val association = service.myAssociations.firstOrNull {
            it.deviceMacAddress?.toString().equals(identifier.macAddress, ignoreCase = true)
        }
        val associationId = association?.id
        logger.v { "CompanionDeviceManager unpairDevice: associationId=$associationId" }
        if (associationId != null) {
            try {
                val result = service.removeBond(associationId)
                logger.d { "CompanionDeviceManager removeBond result=$result" }
                if (result) {
                    return
                }
            } catch (e: SecurityException) {
                logger.e(e) { "Error removing pairing using CompanionDeviceManager" }
            }
        }
    }

    // Resort to reflection hack
    logger.i { "Using reflection to remove bond" }
    try {
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(identifier.macAddress)
        if (device == null) {
            return
        }
        device::class.java.getMethod("removeBond").invoke(device)
    } catch (e: Exception) {
        logger.e(e) { "Error calling removeBond using reflection" }
    }
}
