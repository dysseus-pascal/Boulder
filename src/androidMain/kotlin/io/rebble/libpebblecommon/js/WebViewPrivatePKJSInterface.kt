package io.rebble.libpebblecommon.js

import android.net.Uri
import android.webkit.JavascriptInterface
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

class WebViewPrivatePKJSInterface(
    private val jsRunner: WebViewJsRunner,
    private val device: PebbleJSDevice,
    private val scope: CoroutineScope,
    private val outgoingAppMessages: MutableSharedFlow<Pair<CompletableDeferred<Byte>, String>>
): PrivatePKJSInterface {

    companion object {
        private val logger = Logger.withTag(WebViewPrivatePKJSInterface::class.simpleName!!)
    }

    @JavascriptInterface
    override fun privateLog(message: String) {
        logger.v { "privateLog: $message" }
    }

    @JavascriptInterface
    override fun logInterceptedSend() {
        logger.v { "logInterceptedSend" }
    }

    @JavascriptInterface
    override fun logInterceptedRequest() {
        logger.v { "logInterceptedRequest" }
    }

    @JavascriptInterface
    override fun getVersionCode(): Int {
        logger.v { "getVersionCode" }
        TODO("Not yet implemented")
    }

    @JavascriptInterface
    override fun logLocationRequest() {
        logger.v { "logLocationRequest" }
    }

    @JavascriptInterface
    override fun getTimelineTokenAsync(): String {
        val uuid = Uuid.parse(jsRunner.appInfo.uuid)
        //TODO: Get token from locker or sandbox token if app is sideloaded
        jsRunner.scope.launch {
            jsRunner.signalTimelineTokenFail(uuid.toString())
        }
        return uuid.toString()
    }

    @JavascriptInterface
    fun startupScriptHasLoaded(url: String) {
        logger.v { "Startup script has loaded: $url" }
        val uri = Uri.parse(url)
        val params = uri.getQueryParameter("params")
        scope.launch {
            jsRunner.loadAppJs(params)
        }
    }

    @JavascriptInterface
    fun privateFnLocalStorageWrite(key: String, value: String) {
        logger.v { "privateFnLocalStorageWrite" }
        TODO("Not yet implemented")
    }

    @JavascriptInterface
    fun privateFnLocalStorageRead(key: String): String {
        logger.v { "privateFnLocalStorageRead" }
        TODO("Not yet implemented")
    }

    @JavascriptInterface
    fun privateFnLocalStorageReadAll(): String {
        logger.v { "privateFnLocalStorageReadAll" }
        return "{}"
    }

    @JavascriptInterface
    fun privateFnLocalStorageReadAll_AtPreregistrationStage(baseUriReference: String): String {
        logger.v { "privateFnLocalStorageReadAll_AtPreregistrationStage" }
        return privateFnLocalStorageReadAll()
    }

    @JavascriptInterface
    fun signalAppScriptLoadedByBootstrap() {
        logger.v { "signalAppScriptLoadedByBootstrap" }
        scope.launch {
            jsRunner.signalReady()
        }
    }

    @JavascriptInterface
    fun sendAppMessageString(jsonAppMessage: String): Int {
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

    @JavascriptInterface
    fun getActivePebbleWatchInfo(): String {
        val info = device.watchInfo
        return Json.encodeToString(ActivePebbleWatchInfo.fromWatchInfo(info))
    }

    @JavascriptInterface
    fun privateFnConfirmReadySignal(success: Boolean) {
        logger.v { "privateFnConfirmReadySignal($success)" }
        //TODO: signalShowConfiguration() if needed
    }
}