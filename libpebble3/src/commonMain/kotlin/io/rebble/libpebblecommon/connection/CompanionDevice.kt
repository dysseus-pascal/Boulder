package io.rebble.libpebblecommon.connection

import kotlinx.coroutines.flow.SharedFlow

interface CompanionDevice {
    suspend fun registerDevice(transport: Transport, uiContext: UIContext?): Boolean
    val companionAccessGranted: SharedFlow<Unit>
    val notificationAccessGranted: SharedFlow<Unit>
}