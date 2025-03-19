package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import com.juul.kable.Filter
import com.juul.kable.Identifier
import com.juul.kable.Scanner
import io.rebble.libpebblecommon.connection.BleDiscoveredPebbleDevice
import io.rebble.libpebblecommon.connection.PebbleBluetoothIdentifier
import io.rebble.libpebblecommon.connection.RealBleDiscoveredPebbleDevice
import io.rebble.libpebblecommon.connection.RealPebbleDevice
import io.rebble.libpebblecommon.connection.Transport
import io.rebble.libpebblecommon.connection.Transport.BluetoothTransport.BleTransport
import io.rebble.libpebblecommon.connection.WatchManager
import io.rebble.libpebblecommon.connection.bt.ble.transport.BleScanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

fun kableBleScanner(watchManager: WatchManager): BleScanner = KableBleScanner(watchManager)

class KableBleScanner(
    private val watchManager: WatchManager,
) : BleScanner {
    override suspend fun scan(namePrefix: String): Flow<BleDiscoveredPebbleDevice> {
        return Scanner {
            filters {
                match {
                    name = Filter.Name.Prefix(namePrefix)
                }
            }
        }.advertisements
            .map {
                RealBleDiscoveredPebbleDevice(
                    // TODO populate everything
                    pebbleDevice = RealPebbleDevice(
                        name = it.name ?: "",
                        transport = BleTransport(
                            identifier = it.identifier.asPebbleBluetoothIdentifier()
                        ),
                        watchManager = watchManager,
                    ),
                    fwVersion = "",
                    recoveryVersion = "",
                    serialNo = "",
                    rssi = 0,
                )
            }
    }
}

expect fun Identifier.asPebbleBluetoothIdentifier(): PebbleBluetoothIdentifier
