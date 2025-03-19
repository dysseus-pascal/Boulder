package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import com.juul.kable.Filter
import com.juul.kable.Identifier
import com.juul.kable.Scanner
import io.rebble.libpebblecommon.connection.BleScanResult
import io.rebble.libpebblecommon.connection.PebbleBluetoothIdentifier
import io.rebble.libpebblecommon.connection.Transport.BluetoothTransport.BleTransport
import io.rebble.libpebblecommon.connection.bt.ble.transport.BleScanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

fun kableBleScanner(): BleScanner = KableBleScanner()

class KableBleScanner : BleScanner {
    override suspend fun scan(namePrefix: String?): Flow<BleScanResult> {
        return Scanner {
            filters {
                match {
//                    if (namePrefix != null) {
//                        name = Filter.Name.Prefix(namePrefix)
//                    }
                }
            }
        }.advertisements
            .mapNotNull {
                val name = it.name ?: return@mapNotNull null
                val manufacturerData = it.manufacturerData ?: return@mapNotNull null
                BleScanResult(
                    name = name,
                    transport = BleTransport(
                        identifier = it.identifier.asPebbleBluetoothIdentifier(),
                    ),
                    rssi = it.rssi,
                    manufacturerData = manufacturerData
                )
            }
    }
}

expect fun Identifier.asPebbleBluetoothIdentifier(): PebbleBluetoothIdentifier
