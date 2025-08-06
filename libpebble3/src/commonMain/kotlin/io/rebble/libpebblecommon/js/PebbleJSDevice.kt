package io.rebble.libpebblecommon.js

import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.services.WatchInfo

class PebbleJSDevice(
    val identifier: PebbleIdentifier,
    val watchInfo: WatchInfo,
    appMessages: ConnectedPebble.AppMessages,
): ConnectedPebble.AppMessages by appMessages