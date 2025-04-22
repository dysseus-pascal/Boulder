package io.rebble.libpebblecommon.services.app

import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.AppRunStateMessage
import io.rebble.libpebblecommon.services.ProtocolService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class AppRunStateService(
    private val protocolHandler: PebbleProtocolHandler,
    private val scope: ConnectionCoroutineScope,
) : ProtocolService,
    ConnectedPebble.AppRunState {
    private val _runningApp = MutableStateFlow<Uuid?>(null)
    override val runningApp: StateFlow<Uuid?> = _runningApp

    override suspend fun launchApp(uuid: Uuid) {
        protocolHandler.send(AppRunStateMessage.AppRunStateStart(uuid))
    }

    suspend fun stopApp(uuid: Uuid) {
        protocolHandler.send(AppRunStateMessage.AppRunStateStop(uuid))
    }

    fun init() {
        scope.launch {
            protocolHandler.inboundMessages.collect { packet ->
                when (packet) {
                    is AppRunStateMessage.AppRunStateStart ->
                        _runningApp.value = packet.uuid.get()

                    is AppRunStateMessage.AppRunStateStop ->
                        _runningApp.value = null
                }
            }
        }
    }
}