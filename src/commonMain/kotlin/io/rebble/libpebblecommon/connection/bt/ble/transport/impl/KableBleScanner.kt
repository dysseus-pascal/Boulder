package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import com.juul.kable.Filter
import com.juul.kable.Identifier
import com.juul.kable.Scanner
import io.rebble.libpebblecommon.connection.BleDiscoveredPebbleDevice
import io.rebble.libpebblecommon.connection.PebbleBluetoothIdentifier
import io.rebble.libpebblecommon.connection.Transport
import io.rebble.libpebblecommon.connection.Transport.BluetoothTransport.BleTransport
import io.rebble.libpebblecommon.connection.bt.ble.transport.BleScanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

fun kableBleScanner(): BleScanner = KableBleScanner()

class KableBleScanner : BleScanner {
    override suspend fun scan(namePrefix: String): Flow<BleDiscoveredPebbleDevice> {
        return Scanner {
            filters {
                match {
                    name = Filter.Name.Prefix(namePrefix)
                }
            }
        }.advertisements
            .map {
                KableScanResult(
                    name = it.name ?: "",
                    fwVersion = "",
                    recoveryVersion = "",
                    serialNo = "",
                    rssi = 0,
                    transport = BleTransport(
                        identifier = it.identifier.asPebbleBluetoothIdentifier()
                    )
                )
            }
    }
}

expect fun Identifier.asPebbleBluetoothIdentifier(): PebbleBluetoothIdentifier

// FIXME don't define this here
class KableScanResult(
    override val name: String,
    override val fwVersion: String,
    override val recoveryVersion: String,
    override val serialNo: String,
    override val rssi: Int,
    override val transport: Transport
) : BleDiscoveredPebbleDevice {
    override suspend fun connect() {
        TODO("Not yet implemented")
    }

    override suspend fun disconnect() {
        TODO("Not yet implemented")
    }

}