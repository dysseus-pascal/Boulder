package io.rebble.libpebblecommon.connection

import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.flow.SharedFlow

expect fun createCompanionDeviceManager(libPebbleCoroutineScope: LibPebbleCoroutineScope): CompanionDevice

interface CompanionDevice {
    suspend fun registerDevice(transport: Transport, uiContext: UIContext?): Boolean
    val companionAccessGranted: SharedFlow<Unit>
    val notificationAccessGranted: SharedFlow<Unit>
}