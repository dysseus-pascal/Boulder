package io.rebble.libpebblecommon.connection

import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.flow.SharedFlow

expect fun createCompanionDeviceManager(appContext: AppContext, libPebbleCoroutineScope: LibPebbleCoroutineScope): CompanionDevice

interface CompanionDevice {
    suspend fun registerDevice(transport: Transport)
    val companionAccessGranted: SharedFlow<Unit>
}