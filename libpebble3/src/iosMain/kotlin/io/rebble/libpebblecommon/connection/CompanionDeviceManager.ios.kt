package io.rebble.libpebblecommon.connection

import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

actual fun createCompanionDeviceManager(libPebbleCoroutineScope: LibPebbleCoroutineScope): CompanionDevice {
    return object : CompanionDevice {
        override suspend fun registerDevice(
            transport: Transport,
            uiContext: UIContext?,
        ): Boolean {
            return true
        }

        override val companionAccessGranted: SharedFlow<Unit> = MutableSharedFlow()
        override val notificationAccessGranted: SharedFlow<Unit> = MutableSharedFlow()
    }
}