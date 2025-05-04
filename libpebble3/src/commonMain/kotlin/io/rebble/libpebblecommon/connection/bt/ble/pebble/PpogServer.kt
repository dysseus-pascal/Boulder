package io.rebble.libpebblecommon.connection.bt.ble.pebble

import io.rebble.libpebblecommon.connection.Transport
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.PPOGATT_DEVICE_CHARACTERISTIC_SERVER
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.PPOGATT_DEVICE_SERVICE_UUID_SERVER
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoGPacketSender
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattServerManager

class PpogServer(
    private val transport: Transport.BluetoothTransport.BleTransport,
    private val gattServerManager: GattServerManager,
) : PPoGPacketSender {
    override suspend fun sendPacket(packet: ByteArray): Boolean {
        return gattServerManager.sendData(
            transport = transport,
            serviceUuid = PPOGATT_DEVICE_SERVICE_UUID_SERVER,
            characteristicUuid = PPOGATT_DEVICE_CHARACTERISTIC_SERVER,
            data = packet
        )
    }
}