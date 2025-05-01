package io.rebble.libpebblecommon.js

import co.touchlab.kermit.Logger
import io.rebble.cobble.shared.data.js.ActivePebbleWatchInfo
import io.rebble.cobble.shared.data.js.fromWatchInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

abstract class PrivatePKJSInterface(
    protected val jsRunner: JsRunner,
    private val device: PebbleJSDevice,
    protected val scope: CoroutineScope,
    private val outgoingAppMessages: MutableSharedFlow<Pair<CompletableDeferred<Byte>, String>>
) {
    companion object {
        private val logger = Logger.withTag(PrivatePKJSInterface::class.simpleName!!)
    }

    open fun privateLog(message: String) {
        logger.v { "privateLog: $message" }
    }

    open fun logInterceptedSend() {
        logger.v { "logInterceptedSend" }
    }

    open fun getVersionCode(): Int {
        logger.v { "getVersionCode" }
        TODO("Not yet implemented")
    }

    open fun getTimelineTokenAsync(): String {
        val uuid = Uuid.parse(jsRunner.appInfo.uuid)
        //TODO: Get token from locker or sandbox token if app is sideloaded
        scope.launch {
            jsRunner.signalTimelineTokenFail(uuid.toString())
        }
        return uuid.toString()
    }

    abstract fun startupScriptHasLoaded(data: String?)

    open fun signalAppScriptLoadedByBootstrap() {
        logger.v { "signalAppScriptLoadedByBootstrap" }
        scope.launch {
            jsRunner.signalReady()
        }
    }

    open fun sendAppMessageString(jsonAppMessage: String): Int {
        logger.v { "sendAppMessageString" }
        val completable = CompletableDeferred<Byte>()
        if (!outgoingAppMessages.tryEmit(Pair(completable, jsonAppMessage))) {
            logger.e { "Failed to emit outgoing AppMessage" }
            error("Failed to emit outgoing AppMessage")
        }
        return runBlocking {
            withTimeout(10.seconds) {
                completable.await().toInt()
            }
        }
    }

    open fun getActivePebbleWatchInfo(): String {
        val info = device.watchInfo
        return Json.encodeToString(ActivePebbleWatchInfo.fromWatchInfo(info))
    }

    open fun privateFnConfirmReadySignal(success: Boolean) {
        logger.v { "privateFnConfirmReadySignal($success)" }
        //TODO: signalShowConfiguration() if needed
    }
}