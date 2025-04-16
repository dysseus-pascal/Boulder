package io.rebble.libpebblecommon.notification

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.CoroutineScope

actual fun initPlatformNotificationListener(
    appContext: AppContext,
    scope: CoroutineScope,
    libPebble: LibPebble
) {
}