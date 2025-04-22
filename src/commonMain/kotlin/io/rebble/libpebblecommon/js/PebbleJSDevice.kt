package io.rebble.libpebblecommon.js

import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.Transport
import io.rebble.libpebblecommon.services.WatchInfo

class PebbleJSDevice(
    val transport: Transport,
    val watchInfo: WatchInfo,
    notifications: ConnectedPebble.Notifications,
    appMessages: ConnectedPebble.AppMessages,
): ConnectedPebble.Notifications by notifications, ConnectedPebble.AppMessages by appMessages