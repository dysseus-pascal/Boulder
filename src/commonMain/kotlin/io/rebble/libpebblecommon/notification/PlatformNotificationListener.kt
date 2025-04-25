package io.rebble.libpebblecommon.notification

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope

expect fun initPlatformNotificationListener(
    appContext: AppContext,
    scope: LibPebbleCoroutineScope,
    libPebble: LibPebble
)