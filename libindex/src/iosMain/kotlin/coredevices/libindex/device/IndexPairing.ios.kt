package coredevices.libindex.device

import co.touchlab.kermit.Logger
import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.BOND_BONDED
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlin.uuid.Uuid

private val haversineUuid = Uuid.parse("607B5C9B-3700-4E94-F44A-2DF900BCB0C3")
private val telestoDataChannel = Uuid.parse("DAAD3D52-237C-90A7-B54B-8854A134D801")

actual suspend fun createBond(
    context: AppContext,
    identifier: IndexIdentifier
): Boolean {
    val peripheral = Peripheral(Uuid.parse(identifier.asPlatformAddress))
    peripheral.connect()
    val read = peripheral.services.first()?.firstOrNull { it.serviceUuid == haversineUuid }?.let { service ->
        service.characteristics.firstOrNull { it.characteristicUuid == telestoDataChannel }?.let { char ->
            try {
                peripheral.write(char, byteArrayOf(0x00), writeType = WriteType.WithResponse)
                true
            } catch (e: Exception) {
                false
            }
        } ?: false
    } ?: false
    peripheral.disconnect()
    if (!read) {
        Logger.e { "createBond() failed to read from characteristic, pairing may not have succeeded" }
    }
    return read
}

actual fun getBluetoothDevicePairEvents(
    context: AppContext,
    identifier: IndexIdentifier
): Flow<BluetoothDevicePairEvent> {
    return flowOf(BluetoothDevicePairEvent(identifier, bondState = BOND_BONDED, unbondReason = null))
}