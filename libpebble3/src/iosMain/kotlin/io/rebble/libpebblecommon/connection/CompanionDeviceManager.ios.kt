package io.rebble.libpebblecommon.connection

import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

actual fun createCompanionDeviceManager(appContext: AppContext, libPebbleCoroutineScope: LibPebbleCoroutineScope): CompanionDevice {
    return object : CompanionDevice {
        override suspend fun registerDevice(transport: Transport) {
        }

        override val companionAccessGranted: SharedFlow<Unit> = MutableSharedFlow()
    }
}