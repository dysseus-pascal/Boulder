package coredevices.libindex.device

import co.touchlab.kermit.Logger
import coredevices.haversine.KMPHaversineSatellite
import coredevices.haversine.KMPHaversineSatelliteManager
import coredevices.libindex.IndexDevices
import coredevices.libindex.Rings
import coredevices.libindex.database.BasePreferences
import coredevices.libindex.di.LibIndexCoroutineScope
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.BOND_NONE
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

class IndexDeviceRepository(
    private val satelliteManager: KMPHaversineSatelliteManager,
    private val scope: LibIndexCoroutineScope,
    private val deviceFactory: IndexDeviceFactory,
    private val prefs: BasePreferences,
    private val context: AppContext
): Rings {
    private val _rings = MutableStateFlow(emptyList<IndexDevice>())
    override val rings: IndexDevices = _rings

    companion object {
        private val logger = Logger.withTag("IndexDeviceRepository")
    }

    fun update(indexDevice: IndexDevice) {
        _rings.update { prev ->
            val existingIdx = prev.indexOfFirst { indexDevice.identifier.asString.equals(it.identifier.asString, ignoreCase = true) }
            if (existingIdx != -1) {
                prev
                    .toMutableList()
                    .apply { set(existingIdx, indexDevice) }
            } else {
                prev + indexDevice
            }
        }
    }

    private fun updateRing(satellite: KMPHaversineSatellite) {
        _rings.update { prev ->
            val existingIdx = prev.indexOfFirst { satellite.id.equals(it.identifier.asString, ignoreCase = true) }
            val existing = if (existingIdx != -1) prev[existingIdx] as? KnownIndexDevice else null
            if (existingIdx != -1 && existing != null) {
                prev
                    .toMutableList()
                    .apply {
                        set(
                            existingIdx,
                            deviceFactory.create(
                                identifier = existing.identifier,
                                name = existing.name,
                                isPaired = true,
                                satellite = satellite,
                                satelliteState = satellite.state.value!!,
                            )
                        )
                    }
            } else {
                prev
            }
        }
    }

    fun init() {
        prefs.ringPaired.filterNotNull().flatMapLatest {
            getBluetoothDevicePairEvents(context, IndexIdentifier(it))
        }.onEach {
            logger.d { "Received bond state change for paired ring ${it.device.asString}, bondState=${it.bondState}, unbondReason=${it.unbondReason}" }
            if (it.bondState == BOND_NONE) {
                logger.d { "Paired ring ${it.device.asString} was unpaired, clearing paired state" }
                prefs.setRingPaired(null)
            }
        }
        prefs.ringPaired
            .runningFold<String?, Pair<String?, String?>>(null to null) { (_, prev), new -> prev to new }
            .drop(1)
            .onEach { (old, new) ->
                logger.d { "Paired ring changed from $old to $new" }
                // remove old if it isnt the same as new, otherwise keep it to avoid UI flickering
                if (old != null && old != new) {
                    logger.d { "Removing old from list" }
                    _rings.update { prev ->
                        prev.filterNot { it.identifier.asString.equals(old, ignoreCase = true) }
                    }
                }
                if (new != null) {
                    logger.d { "Adding new to list" }
                    _rings.update { prev ->
                        // replace if exists and is a DiscoveredIndexDevice, otherwise add
                        val existing = prev.indexOfFirst { it.identifier.asString.equals(new, ignoreCase = true) }
                        val known = deviceFactory.create(
                            identifier = IndexIdentifier(new),
                            name = "Index 01",
                            isPaired = true,
                        )
                        if (existing != -1 && prev[existing] is DiscoveredIndexDevice) {
                            prev
                                .toMutableList()
                                .apply { set(existing, known) }
                        } else {
                            prev + known
                        }
                    }
                }
            }.launchIn(scope)
        satelliteManager.lastRing
            .onEach {
                if (it != null) {
                    // Wait for state to be non-null, just in case
                    withTimeoutOrNull(1.seconds) {
                        it.state.filterNotNull().first()
                    } ?: return@onEach

                    updateRing(it)
                }
            }.launchIn(scope)
    }

    fun addScanResult(result: IndexScanResult) {
        _rings.update { prev ->
            val existingIdx = prev.indexOfFirst { it.identifier.asString.equals(result.identifier.asString, ignoreCase = true) }
            val existing = existingIdx.takeIf { it != -1 }?.let { prev[it] }
            if (existing is DiscoveredIndexDevice) {
                prev
                    .toMutableList()
                    .apply {
                        set(
                            existingIdx,
                            deviceFactory.create(
                                identifier = result.identifier,
                                name = result.name,
                                scanResult = result,
                                pairingState = existing.pairingState,
                            )
                        )
                    }
            } else if (existing == null) {
                prev + deviceFactory.create(
                    identifier = result.identifier,
                    name = result.name,
                    scanResult = result,
                )
            } else { // existing is a KnownIndexDevice, ignore scan result
                prev
            }
        }
    }

    fun clearScanResults() {
        _rings.update { prev ->
            prev.filterNot { it is DiscoveredIndexDevice }
        }
    }
}

data class IndexScanResult(
    val identifier: IndexIdentifier,
    val name: String,
    val rssi: Int,
)