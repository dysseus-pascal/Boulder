package io.rebble.libpebblecommon.connection

import kotlinx.coroutines.flow.SharedFlow

interface CompanionDevice {
    suspend fun registerDevice(identifier: PebbleIdentifier, uiContext: UIContext?): Boolean
    val companionAccessGranted: SharedFlow<Unit>
    val notificationAccessGranted: SharedFlow<Unit>
}