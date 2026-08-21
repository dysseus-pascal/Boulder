package coredevices.libindex.device

import coredevices.haversine.KMPHaversineSatelliteManager
import kotlin.time.Duration

interface IndexSystem {
    suspend fun forceFailsafe(device: IndexDevice)
    suspend fun measureRSSI(device: IndexDevice, connectionTimeout: Duration): RSSIMeasurement
}

class RealIndexSystem(
    private val satelliteManager: KMPHaversineSatelliteManager
): IndexSystem {
    private val RSSI_SAMPLES = 20

    override suspend fun forceFailsafe(device: IndexDevice) {
        val device = requireNotNull(satelliteManager.getSatelliteById(device.identifier.asString))
        device.forceFailsafe()
    }

    override suspend fun measureRSSI(device: IndexDevice, connectionTimeout: Duration): RSSIMeasurement {
        val satellite = requireNotNull(satelliteManager.getSatelliteById(device.identifier.asString)) {
            "Index 01 is not connected"
        }
        val measurement = satellite.measureRSSI(RSSI_SAMPLES, connectionTimeout.inWholeSeconds.toUInt())
        return RSSIMeasurement(deviceRSSI = measurement.rxRSSI, phoneRSSI = measurement.phoneRSSI)
    }
}