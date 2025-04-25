package io.rebble.libpebblecommon.notification

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.LibPebbleNotificationListenerConnection

actual fun initPlatformNotificationListener(
    appContext: AppContext,
    scope: LibPebbleCoroutineScope,
    libPebble: LibPebble
) {
    LibPebbleNotificationListenerConnection.init(appContext.context, scope, libPebble)
}