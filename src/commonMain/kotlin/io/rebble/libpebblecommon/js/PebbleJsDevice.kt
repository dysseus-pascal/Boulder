package io.rebble.libpebblecommon.js

import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.ConnectedWatchInfo
import io.rebble.libpebblecommon.connection.Transport

interface PebbleJsDevice:
    ConnectedWatchInfo,
    ConnectedPebble.Notifications,
    ConnectedPebble.AppMessages
{
    val transport: Transport
}