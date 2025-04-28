package io.rebble.libpebblecommon.connection

expect fun createCompanionDeviceManager(appContext: AppContext): CompanionDevice

interface CompanionDevice {
    suspend fun registerDevice(transport: Transport)
}