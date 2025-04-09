package io.rebble.libpebblecommon.services.app

import com.benasher44.uuid.uuidFrom
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.packets.AppRunStateMessage
import io.rebble.libpebblecommon.services.ProtocolService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.uuid.Uuid

class AppRunStateService(private val protocolHandler: PebbleProtocolHandler) : ProtocolService,
    ConnectedPebble.AppRunState {
    val _runningApp = MutableStateFlow<Uuid?>(null)
    val runningApp: StateFlow<Uuid?> = _runningApp

    override suspend fun launchApp(uuid: Uuid) {
        TODO("Not yet implemented")
    }

    suspend fun stopApp(uuid: Uuid) {
        protocolHandler.send(AppRunStateMessage.AppRunStateStop(uuidFrom(uuid.toString())))
    }

    fun init(scope: CoroutineScope) {
        scope.async {
            protocolHandler.inboundMessages.collect { packet ->
                when (packet) {
                    is AppRunStateMessage.AppRunStateStart ->
                        _runningApp.value = Uuid.parse(packet.uuid.toString())

                    is AppRunStateMessage.AppRunStateStop ->
                        _runningApp.value = null
                }
            }
        }
    }
}