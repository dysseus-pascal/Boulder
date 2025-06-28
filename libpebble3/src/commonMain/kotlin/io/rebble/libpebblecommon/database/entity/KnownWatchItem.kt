package io.rebble.libpebblecommon.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.rebble.libpebblecommon.connection.PebbleSocketIdentifier
import io.rebble.libpebblecommon.connection.Transport
import io.rebble.libpebblecommon.connection.Transport.BluetoothTransport.BleTransport
import io.rebble.libpebblecommon.connection.Transport.BluetoothTransport.BtClassicTransport
import io.rebble.libpebblecommon.connection.Transport.SocketTransport
import io.rebble.libpebblecommon.connection.asPebbleBluetoothIdentifier
import io.rebble.libpebblecommon.database.MillisecondInstant
import io.rebble.libpebblecommon.database.entity.TransportType.BluetoothClassic
import io.rebble.libpebblecommon.database.entity.TransportType.BluetoothLe
import io.rebble.libpebblecommon.database.entity.TransportType.Socket

@Entity
data class KnownWatchItem (
    @PrimaryKey val transportIdentifier: String,
    val transportType: TransportType,
    val name: String,
    val runningFwVersion: String,
    val serial: String,
    val connectGoal: Boolean,
    val lastConnected: MillisecondInstant? = null,
    val watchType: String? = null,
)

enum class TransportType {
    BluetoothLe,
    BluetoothClassic,
    Socket,
}

fun KnownWatchItem.transport(): Transport = when (transportType) {
    BluetoothLe -> BleTransport(transportIdentifier.asPebbleBluetoothIdentifier(), name)
    BluetoothClassic -> BtClassicTransport(transportIdentifier.asPebbleBluetoothIdentifier(), name)
    Socket -> SocketTransport(PebbleSocketIdentifier(transportIdentifier), name)
}

fun Transport.type(): TransportType = when (this) {
    is BleTransport -> BluetoothLe
    is BtClassicTransport -> BluetoothClassic
    is SocketTransport -> Socket
}