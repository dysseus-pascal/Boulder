package io.rebble.libpebblecommon.ble.pebble

import co.touchlab.kermit.Logger
import com.oldguy.common.io.BitSet
import io.rebble.libpebblecommon.ble.pebble.LEConstants.BOND_BONDED
import io.rebble.libpebblecommon.ble.pebble.LEConstants.PROPERTY_WRITE
import io.rebble.libpebblecommon.ble.pebble.LEConstants.UUIDs.PAIRING_SERVICE_UUID
import io.rebble.libpebblecommon.ble.pebble.LEConstants.UUIDs.PAIRING_TRIGGER_CHARACTERISTIC
import io.rebble.libpebblecommon.ble.transport.ConnectedGattClient
import io.rebble.libpebblecommon.ble.transport.createBond
import io.rebble.libpebblecommon.ble.transport.getBluetoothDevicePairEvents
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException

class PebblePairing(val device: ConnectedGattClient, val context: AppContext, val scannedPebbleDevice: ScannedPebbleDevice) {
//    @Throws(IOException::class, SecurityException::class)
    suspend fun requestPairing(connectivityRecord: ConnectivityStatus) {
        Logger.d("Requesting pairing")
        val pairingService = device.services?.firstOrNull { it.uuid.equals(PAIRING_SERVICE_UUID, ignoreCase = true) }
        check(pairingService != null) { "Pairing service not found" }
        val pairingTriggerCharacteristic = pairingService.characteristics.firstOrNull { it.uuid.equals(PAIRING_TRIGGER_CHARACTERISTIC, ignoreCase = true) }
        check(pairingTriggerCharacteristic != null) { "Pairing trigger characteristic not found" }

        val bondState = getBluetoothDevicePairEvents(context, scannedPebbleDevice.device.identifier.toString())
        var needsExplicitBond = true

        // A writeable pairing trigger allows addr pinning
        val writeablePairTrigger = pairingTriggerCharacteristic.properties and PROPERTY_WRITE != 0
        if (writeablePairTrigger) {
            needsExplicitBond = connectivityRecord.supportsPinningWithoutSlaveSecurity
            val pairValue = makePairingTriggerValue(needsExplicitBond, autoAcceptFuturePairing = false, watchAsGattServer = false)
            if (!device.writeCharacteristic(PAIRING_SERVICE_UUID, PAIRING_TRIGGER_CHARACTERISTIC, pairValue)) {
                throw IOException("Failed to request pinning")
            }
        }

        if (needsExplicitBond) {
            Logger.d("Explicit bond required")
            if (!createBond(scannedPebbleDevice)) {
                throw IOException("Failed to request create bond")
            }
        }
        try {
            withTimeout(PENDING_BOND_TIMEOUT) {
                bondState.onEach { Logger.v("Bond state: ${it.bondState}") }.first { it.bondState == BOND_BONDED }
            }
        } catch (e: TimeoutCancellationException) {
            throw IOException("Failed to bond in time")
        }
    }

    private fun makePairingTriggerValue(noSecurityRequest: Boolean, autoAcceptFuturePairing: Boolean, watchAsGattServer: Boolean): ByteArray {
        val value = BitSet(8)
        value[0] = true
        value[1] = noSecurityRequest
        value[2] = true
        value[3] = autoAcceptFuturePairing
        value[4] = watchAsGattServer
        val ret = value.toByteArray()
        Logger.d("makePairingTriggerValue: $ret")
        return ret //byteArrayOf(.first())
    }

    companion object {
        private val PENDING_BOND_TIMEOUT = 60000L // Requires user interaction, so needs a longer timeout
    }
}
