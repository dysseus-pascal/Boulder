package io.rebble.libpebblecommon.notification

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.LibPebbleNotificationListenerConnection
import kotlinx.coroutines.CoroutineScope

actual fun initPlatformNotificationListener(
    appContext: AppContext,
    scope: CoroutineScope,
    libPebble: LibPebble
) {
    LibPebbleNotificationListenerConnection.init(appContext.context, scope, libPebble)
}