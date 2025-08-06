package io.rebble.libpebblecommon.connection

import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class DevConnectionManager(
    private val server: DevConnectionServer,
    private val identifier: PebbleIdentifier,
    scope: ConnectionCoroutineScope
): ConnectedPebble.DevConnection {
    override val devConnectionActive: StateFlow<Boolean> =
        server.activeDevice.map { it == identifier }.stateIn(
            scope,
            SharingStarted.Eagerly,
            false
        )
    override suspend fun startDevConnection() {
        server.startForDevice(identifier)
    }

    override suspend fun stopDevConnection() {
        server.stop()
    }
}