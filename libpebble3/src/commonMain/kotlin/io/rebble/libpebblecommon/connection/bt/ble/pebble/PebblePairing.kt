package io.rebble.libpebblecommon.connection.bt.ble.pebble

import co.touchlab.kermit.Logger
import com.oldguy.common.io.BitSet
import io.rebble.libpebblecommon.BleConfigFlow
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.ConnectionFailureReason
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.BlePlatformConfig
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.BOND_BONDED
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.PROPERTY_WRITE
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.PAIRING_SERVICE_UUID
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.PAIRING_TRIGGER_CHARACTERISTIC
import io.rebble.libpebblecommon.connection.bt.ble.transport.ConnectedGattClient
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattWriteType
import io.rebble.libpebblecommon.connection.bt.createBond
import io.rebble.libpebblecommon.connection.bt.getBluetoothDevicePairEvents
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeout

class PebblePairing(
    val context: AppContext,
    val identifier: PebbleBleIdentifier,
    val config: BleConfigFlow,
    val blePlatformConfig: BlePlatformConfig,
) {
    suspend fun requestPairing(
        device: ConnectedGattClient,
        connectivityRecord: ConnectivityStatus,
        connectivity: Flow<ConnectivityStatus>,
    ): ConnectionFailureReason? {
        Logger.d("Requesting pairing")
        val pairingService =
            device.services?.firstOrNull { it.uuid == PAIRING_SERVICE_UUID }
        check(pairingService != null) { "Pairing service not found" }
        val pairingTriggerCharacteristic = pairingService.characteristics.firstOrNull {
            it.uuid == PAIRING_TRIGGER_CHARACTERISTIC
        }
        check(pairingTriggerCharacteristic != null) { "Pairing trigger characteristic not found" }

        val bondState = getBluetoothDevicePairEvents(context, identifier, connectivity)
        var needsExplicitBond = true
        val bleConfig = config.value

        // A writeable pairing trigger allows addr pinning
        val writeablePairTrigger = pairingTriggerCharacteristic.properties and PROPERTY_WRITE != 0
        if (writeablePairTrigger && blePlatformConfig.writeConnectivityTrigger) {
            needsExplicitBond = when {
                blePlatformConfig.pinAddress -> connectivityRecord.supportsPinningWithoutSlaveSecurity && blePlatformConfig.phoneRequestsPairing
                else -> blePlatformConfig.phoneRequestsPairing
            }
            val pairValue = makePairingTriggerValue(
                noSecurityRequest = needsExplicitBond,
                autoAcceptFuturePairing = false,
                watchAsGattServer = bleConfig.reversedPPoG,
                pinAddress = blePlatformConfig.pinAddress,
            )
            val writeRes = device.writeCharacteristic(
                PAIRING_SERVICE_UUID,
                PAIRING_TRIGGER_CHARACTERISTIC,
                pairValue,
                GattWriteType.WithResponse
            )
            if (!writeRes) {
                Logger.e("Failed to write to pairing trigger")
//                return
                // TODO this fails with gatt error 1 (INVALID_HANDLE) and I'm not sure why.
                //  OG pebble app didn't check for that result (and didn't log it ebcause of ppog spam)
                //  Pairing seems to work anyway... (but does it pin the address?)
            }
            Logger.d("wrote pairing trigger")
        } else {
            val readRes =
                device.readCharacteristic(PAIRING_SERVICE_UUID, PAIRING_TRIGGER_CHARACTERISTIC)
            if (readRes == null) {
                Logger.e("Failed to read pairing trigger")
                return ConnectionFailureReason.ReadPairingTrigger
            }
        }

        if (needsExplicitBond) {
            Logger.d("Explicit bond required")
            if (!createBond(identifier)) {
                Logger.e("Failed to request create bond")
                return ConnectionFailureReason.CreateBondFailed
            }
        }
        try {
            Logger.d("waiting for bond state...")
            withTimeout(PENDING_BOND_TIMEOUT) {
                bondState.onEach { Logger.v("Bond state: ${it.bondState}") }
                    .first { it.bondState == BOND_BONDED }
            }
            Logger.d("got bond state!")
        } catch (e: TimeoutCancellationException) {
            Logger.e("Failed to bond in time")
            return ConnectionFailureReason.PairingTimedOut
        }
        return null
    }

    private fun makePairingTriggerValue(
        pinAddress: Boolean,
        noSecurityRequest: Boolean,
        autoAcceptFuturePairing: Boolean,
        watchAsGattServer: Boolean
    ): ByteArray {
        Logger.d(
            "makePairingTriggerValue " +
                    "pinAddress=$pinAddress " +
                    "noSecurityRequest=$noSecurityRequest " +
                    "autoAcceptFuturePairing=$autoAcceptFuturePairing " +
                    "watchAsGattServer=$watchAsGattServer "
        )
        val value = BitSet(8)
        value[0] = pinAddress // pin address
        value[1] = noSecurityRequest
        value[2] = !noSecurityRequest // force security request
        value[3] = autoAcceptFuturePairing
        value[4] = watchAsGattServer
        val ret = value.toByteArray()
        return ret
    }

    companion object {
        private val PENDING_BOND_TIMEOUT =
            60000L // Requires user interaction, so needs a longer timeout
    }
}
