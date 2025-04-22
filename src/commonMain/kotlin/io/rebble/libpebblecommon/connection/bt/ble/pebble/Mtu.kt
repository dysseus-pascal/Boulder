package io.rebble.libpebblecommon.connection.bt.ble.pebble

import co.touchlab.kermit.Logger
import com.oldguy.common.getUShortAt
import com.oldguy.common.io.Buffer.ByteOrder.LittleEndian
import com.oldguy.common.io.ByteBuffer
import io.rebble.libpebblecommon.connection.BleConfig
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.DEFAULT_MTU
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.MTU_CHARACTERISTIC
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.PAIRING_SERVICE_UUID
import io.rebble.libpebblecommon.connection.bt.ble.transport.ConnectedGattClient
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattWriteType.WithResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okio.ArrayIndexOutOfBoundsException

class Mtu(private val scope: CoroutineScope, private val bleConfig: BleConfig) {
    private val _mtu = MutableStateFlow(DEFAULT_MTU)
    val mtu: StateFlow<Int> = _mtu.asStateFlow()

    suspend fun subscribe(gattClient: ConnectedGattClient): Boolean {
        val flow = gattClient.subscribeToCharacteristic(PAIRING_SERVICE_UUID, MTU_CHARACTERISTIC)
            ?: return false
        scope.launch {
            flow.collect { mtuBytes ->
                mtuBytes.toUShortLittleEndian()?.toInt()?.let {
                    _mtu.value = it
                }
            }
        }
        Logger.d("about to read mtu")
        val currentMtu = gattClient.readCharacteristic(PAIRING_SERVICE_UUID, MTU_CHARACTERISTIC)
        Logger.d("read mtu: ${currentMtu?.joinToString()}")
        if (currentMtu != null) {
            currentMtu.toUShortLittleEndian()?.toInt()?.let {
                _mtu.value = it
            }
        }
        return true
    }

    suspend fun update(gattClient: ConnectedGattClient, mtu: Int) {
        if (bleConfig.useNativeMtu) {
            _mtu.value = gattClient.requestMtu(mtu)
            return
        }
        val buffer = ByteBuffer(ByteArray(2), order = LittleEndian)
        buffer.ushort = mtu.toUShort()
        buffer.flip()
        val bytes = buffer.getBytes()
//        Logger.v("update mtu bytes = ${bytes.joinToString()}")
        gattClient.writeCharacteristic(
            PAIRING_SERVICE_UUID,
            MTU_CHARACTERISTIC,
            bytes,
            WithResponse,
        )
    }
}

fun ByteArray.toUShortLittleEndian(): UShort? = try {
    toUByteArray().getUShortAt(0, littleEndian = true)
} catch (e: ArrayIndexOutOfBoundsException) {
    Logger.w("toUShortLittleEndian", e)
    null
}