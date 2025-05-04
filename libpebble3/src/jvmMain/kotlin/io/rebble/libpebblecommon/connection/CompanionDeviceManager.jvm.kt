package io.rebble.libpebblecommon.connection

actual fun createCompanionDeviceManager(appContext: AppContext): CompanionDevice {
    return object : CompanionDevice {
        override suspend fun registerDevice(transport: Transport) {
        }
    }
}