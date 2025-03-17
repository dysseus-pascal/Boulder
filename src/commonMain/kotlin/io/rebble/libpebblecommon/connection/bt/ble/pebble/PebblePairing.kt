package io.rebble.libpebblecommon.connection.bt.ble.pebble

import co.touchlab.kermit.Logger
import com.oldguy.common.io.BitSet
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.BOND_BONDED
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.PROPERTY_WRITE
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.PAIRING_SERVICE_UUID
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.PAIRING_TRIGGER_CHARACTERISTIC
import io.rebble.libpebblecommon.connection.bt.ble.transport.ConnectedGattClient
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattWriteType
import io.rebble.libpebblecommon.connection.bt.createBond
import io.rebble.libpebblecommon.connection.bt.getBluetoothDevicePairEvents
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeout

class PebblePairing(val device: ConnectedGattClient, val context: AppContext, val scannedPebbleDevice: ScannedPebbleDevice) {
//    @Throws(IOException::class, SecurityException::class)
    suspend fun requestPairing(connectivityRecord: ConnectivityStatus) {
        Logger.d("Requesting pairing")
        Logger.d("Requesting pairing/services = ${device.services}")
        val pairingService = device.services?.firstOrNull { it.uuid.equals(PAIRING_SERVICE_UUID, ignoreCase = true) }
        check(pairingService != null) { "Pairing service not found" }
        val pairingTriggerCharacteristic = pairingService.characteristics.firstOrNull { it.uuid.equals(PAIRING_TRIGGER_CHARACTERISTIC, ignoreCase = true) }
        check(pairingTriggerCharacteristic != null) { "Pairing trigger characteristic not found" }

        val bondState = getBluetoothDevicePairEvents(context, scannedPebbleDevice.identifier)
        var needsExplicitBond = true

        // A writeable pairing trigger allows addr pinning
        val writeablePairTrigger = pairingTriggerCharacteristic.properties and PROPERTY_WRITE != 0
        if (writeablePairTrigger) {
            needsExplicitBond = connectivityRecord.supportsPinningWithoutSlaveSecurity
            val pairValue = makePairingTriggerValue(needsExplicitBond, autoAcceptFuturePairing = false, watchAsGattServer = false)
            val pinRes = device.writeCharacteristic(PAIRING_SERVICE_UUID, PAIRING_TRIGGER_CHARACTERISTIC, pairValue, GattWriteType.NoResponse)
            if (!pinRes) {
                Logger.e("Failed to request pinning")
                return
                // TODO this fails with gatt error 1 (INVALID_HANDLE) and I'm not sure why.
                //  OG pebble app didn't check for that result (and didn't log it ebcause of ppog spam)
                //  Pairing seems to work anyway... (but does it pin the address?)
            }
        }

        if (needsExplicitBond) {
            Logger.d("Explicit bond required")
            if (!createBond(scannedPebbleDevice)) {
                Logger.e("Failed to request create bond")
                return
            }
        }
        try {
            withTimeout(PENDING_BOND_TIMEOUT) {
                bondState.onEach { Logger.v("Bond state: ${it.bondState}") }.first { it.bondState == BOND_BONDED }
            }
        } catch (e: TimeoutCancellationException) {
            Logger.e("Failed to bond in time")
            return
        }
    }

    private fun makePairingTriggerValue(noSecurityRequest: Boolean, autoAcceptFuturePairing: Boolean, watchAsGattServer: Boolean): ByteArray {
        val value = BitSet(8)
        value[0] = true // pin address
        value[1] = noSecurityRequest
        value[2] = true // force security request
        value[3] = autoAcceptFuturePairing
        value[4] = watchAsGattServer
        val ret = value.toByteArray()
        return ret //byteArrayOf(.first())
    }

    companion object {
        private val PENDING_BOND_TIMEOUT = 60000L // Requires user interaction, so needs a longer timeout
    }
}
