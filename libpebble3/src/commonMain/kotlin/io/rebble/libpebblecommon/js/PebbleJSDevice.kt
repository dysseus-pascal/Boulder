package io.rebble.libpebblecommon.js

import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.Transport
import io.rebble.libpebblecommon.services.WatchInfo

class PebbleJSDevice(
    val transport: Transport,
    val watchInfo: WatchInfo,
    appMessages: ConnectedPebble.AppMessages,
): ConnectedPebble.AppMessages by appMessages