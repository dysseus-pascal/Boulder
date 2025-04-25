package io.rebble.libpebblecommon.io.rebble.libpebblecommon.js

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.js.JsRunner
import io.rebble.libpebblecommon.js.PebbleJSDevice
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import platform.JavaScriptCore.JSContext

class JavascriptCoreJsRunner(
    appInfo: PbwAppInfo,
    lockerEntry: LockerEntry,
    jsPath: Path,
    device: PebbleJSDevice
): JsRunner(appInfo, lockerEntry, jsPath, device) {
    private val jsContext = JSContext()
    private val logger = Logger.withTag("JSCRunner-${appInfo.longName}")

    private fun setupJsContext() {
        val log = { message: String ->
            logger.d { "JS: $message" }
        }
        jsContext["log"] = log
        jsContext.evaluateScript("print('Test')")
    }

    override suspend fun start() {
        setupJsContext()
        val js = SystemFileSystem.source(jsPath).buffered().use {
            it.readString()
        }
        jsContext.evaluateScript(js)
    }

    override suspend fun stop() {

    }

    override fun loadUrl(url: String) {
        logger.e { "TODO" }
    }

    override suspend fun signalNewAppMessageData(data: String?): Boolean {
        logger.e { "TODO" }
        return false
    }

    override suspend fun signalAppMessageAck(data: String?): Boolean {
        logger.e { "TODO" }
        return false
    }

    override suspend fun signalAppMessageNack(data: String?): Boolean {
        logger.e { "TODO" }
        return false
    }
}