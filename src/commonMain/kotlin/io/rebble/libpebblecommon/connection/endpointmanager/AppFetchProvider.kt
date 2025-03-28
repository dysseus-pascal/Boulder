package io.rebble.libpebblecommon.connection.endpointmanager

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.endpointmanager.putbytes.PutBytesSession
import io.rebble.libpebblecommon.disk.pbw.PbwApp
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.packets.AppFetchRequest
import io.rebble.libpebblecommon.packets.ObjectType
import io.rebble.libpebblecommon.services.AppFetchService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlin.math.roundToInt

class AppFetchProvider(
    private val testApp: PbwApp, //TODO: Fetch actual requested app from locker
    private val appFetchService: AppFetchService,
    private val putBytesSession: PutBytesSession
) {
    companion object {
        private val logger = Logger.withTag(AppFetchProvider::class.simpleName!!)
    }
    fun init(scope: CoroutineScope) {
        scope.async {
            appFetchService.receivedMessages.consumeEach {
                when (it) {
                    is AppFetchRequest -> {
                        val uuid = it.uuid.get()
                        val appId = it.appId.get()
                        try {
                            sendTestApp(appId)
                        } catch (e: Exception) {
                            logger.e(e) { "Failed to send test app" }
                        }
                    }
                }
            }
        }
    }

    private suspend fun sendTestApp(appId: UInt) {
        val testWatchType = WatchType.APLITE
        val manifest = testApp.getManifest(testWatchType)
        val binary = testApp.getBinaryFor(testWatchType)
        val resources = testApp.getResourcesFor(testWatchType)
        putBytesSession.currentSession.filter { it == null }.first()
        putBytesSession.beginAppSession(appId, manifest.application.size.toUInt(), ObjectType.APP_EXECUTABLE, binary)
            .flowOn(Dispatchers.IO)
            .collect {
                when (it) {
                    is PutBytesSession.SessionState.Open -> {
                        logger.d { "Opened PutBytes session for binary ${it.cookie}" }
                    }
                    is PutBytesSession.SessionState.Sending -> {
                        logger.d { "PutBytes progress: ${((it.totalSent.toFloat()/manifest.application.size)*100).roundToInt()}%" }
                    }
                }
            }
        logger.d { "Binary sent" }
        if (resources != null) {
            putBytesSession.beginAppSession(appId, manifest.resources!!.size.toUInt(), ObjectType.APP_RESOURCE, resources)
                .flowOn(Dispatchers.IO)
                .collect {
                    when (it) {
                        is PutBytesSession.SessionState.Open -> {
                            logger.d { "Opened PutBytes session for resources ${it.cookie}" }
                        }
                        is PutBytesSession.SessionState.Sending -> {
                            logger.d { "PutBytes progress: ${((it.totalSent.toFloat()/manifest.resources.size)*100).roundToInt()}%" }
                        }
                    }
                }
            logger.d { "Resources sent" }
        }
    }
}