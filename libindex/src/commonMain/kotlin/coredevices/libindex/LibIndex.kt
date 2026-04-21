package coredevices.libindex

import coredevices.libindex.device.IndexDevice
import coredevices.libindex.device.IndexDeviceRepository
import coredevices.libindex.device.RealScanning
import kotlinx.coroutines.flow.StateFlow

interface LibIndex : Scanning, Rings {
    fun init()
}

typealias IndexDevices = StateFlow<List<IndexDevice>>

interface Scanning {
    val isScanning: StateFlow<Boolean>
    fun startScan()
    fun stopScan()
}

interface Rings {
    val rings: IndexDevices
}

class RealLibIndex(
    private val scanning: RealScanning,
    private val deviceRepo: IndexDeviceRepository
): LibIndex, Scanning by scanning, Rings by deviceRepo {
    override fun init() {
        deviceRepo.init()
    }
}