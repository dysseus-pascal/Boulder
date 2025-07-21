package io.rebble.libpebblecommon.js

import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.io.files.Path

abstract class JsRunner(
    val appInfo: PbwAppInfo,
    val lockerEntry: LockerEntry,
    val jsPath: Path,
    val device: PebbleJSDevice,
    private val urlOpenRequests: Channel<String>,
) {
    abstract suspend fun start()
    abstract suspend fun stop()
    abstract suspend fun loadAppJs(jsUrl: String)
    abstract suspend fun signalNewAppMessageData(data: String?): Boolean
    abstract suspend fun signalAppMessageAck(data: String?): Boolean
    abstract suspend fun signalAppMessageNack(data: String?): Boolean
    abstract suspend fun signalTimelineToken(callId: String, token: String)
    abstract suspend fun signalTimelineTokenFail(callId: String)
    abstract suspend fun signalReady()
    abstract suspend fun signalShowConfiguration()
    abstract suspend fun signalWebviewClosed(data: String?)
    abstract suspend fun eval(js: String)
    suspend fun loadUrl(url: String) {
        urlOpenRequests.send(url)
    }

    protected val _outgoingAppMessages = MutableSharedFlow<Pair<CompletableDeferred<Byte>, String>>(extraBufferCapacity = 1)
    val outgoingAppMessages = _outgoingAppMessages.asSharedFlow()
}