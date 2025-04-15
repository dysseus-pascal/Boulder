package io.rebble.libpebblecommon.connection

import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.LibPebbleNotificationListenerConnection
import kotlinx.coroutines.GlobalScope

actual fun LibPebble.initPlatform(config: LibPebbleConfig) {
    LibPebbleNotificationListenerConnection.init(config.context.context, GlobalScope, this)
}