package io.rebble.libpebblecommon.connection.endpointmanager

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.Locker
import io.rebble.libpebblecommon.connection.LockerPBWCache
import io.rebble.libpebblecommon.connection.endpointmanager.putbytes.PutBytesSession
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.disk.pbw.PbwApp
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.packets.AppFetchRequest
import io.rebble.libpebblecommon.packets.AppFetchResponseStatus
import io.rebble.libpebblecommon.packets.ObjectType
import io.rebble.libpebblecommon.services.AppFetchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class AppFetchProvider(
    private val pbwCache: LockerPBWCache,
    private val appFetchService: AppFetchService,
    private val putBytesSession: PutBytesSession,
    private val scope: ConnectionCoroutineScope,
    private val locker: Locker,
) {
    companion object {
        private val logger = Logger.withTag(AppFetchProvider::class.simpleName!!)
    }
    fun init(watchType: WatchType) {
        scope.launch {
            appFetchService.receivedMessages.consumeEach {
                when (it) {
                    is AppFetchRequest -> {
                        val uuid = it.uuid.get()
                        logger.d { "Got app fetch request for $uuid" }
                        val appId = it.appId.get()
                        val app = try {
                            PbwApp(pbwCache.getPBWFileForApp(uuid, locker))
                        } catch (e: Exception) {
                            logger.e(e) { "Failed to get app for uuid $uuid" }
                            appFetchService.sendResponse(AppFetchResponseStatus.NO_DATA)
                            return@consumeEach
                        }
                        // Remove PKJS cached file, so we get an updated version if there is one
                        pbwCache.clearPKJSFileForApp(uuid)
                        sendApp(app, appId, watchType)
                    }
                }
            }
        }
    }

    private suspend fun sendApp(app: PbwApp, appId: UInt, watchType: WatchType) {
        val variant = watchType.getBestVariant(app.info.targetPlatforms) ?: run {
            logger.e { "No compatible variant found for ${app.info.targetPlatforms}" }
            appFetchService.sendResponse(AppFetchResponseStatus.NO_DATA)
            return
        }
        appFetchService.sendResponse(AppFetchResponseStatus.START)
        val manifest = app.getManifest(variant)
        val binary = app.getBinaryFor(variant)
        val resources = app.getResourcesFor(variant)
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

                    is PutBytesSession.SessionState.Finished -> Unit
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

                        is PutBytesSession.SessionState.Finished -> Unit
                    }
                }
            logger.d { "Resources sent" }
        }
    }
}