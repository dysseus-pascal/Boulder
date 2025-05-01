package io.rebble.libpebblecommon.io.rebble.libpebblecommon.js

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.js.JsRunner
import io.rebble.libpebblecommon.js.PebbleJSDevice
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import platform.Foundation.NSBundle
import platform.JavaScriptCore.JSContext
import platform.JavaScriptCore.JSValue

class JavascriptCoreJsRunner(
    appInfo: PbwAppInfo,
    lockerEntry: LockerEntry,
    jsPath: Path,
    device: PebbleJSDevice,
    private val scope: CoroutineScope,
    private val pkjsBundleIdentifier: String = "coredevices.coreapp",
): JsRunner(appInfo, lockerEntry, jsPath, device) {
    private var jsContext: JSContext? = null
    private val logger = Logger.withTag("JSCRunner-${appInfo.longName}")
    private var interfaces: List<RegisterableJsInterface>? = null

    private fun initInterfaces(jsContext: JSContext) {
        val instances = listOf(
            XMLHTTPRequestManager(scope, jsContext),
            JSCPKJSInterface(this, device),
            JSCPrivatePKJSInterface(jsPath, this, device, scope, _outgoingAppMessages)
        )
        instances.forEach { it.register(jsContext) }
        interfaces = instances
    }

    private fun evaluateInternalScript(filenameNoExt: String) {
        val bundle = NSBundle.bundleWithIdentifier(pkjsBundleIdentifier) ?: error("Bundle not found")
        val path = bundle.pathForResource(filenameNoExt, "js")
            ?: error("Startup script not found in bundle")
        val js = SystemFileSystem.source(Path(path)).buffered().use {
            it.readString()
        }
        jsContext?.evaluateScript(js)
    }

    private fun exceptionHandler(context: JSContext?, _exception: JSValue?) {
        val exception = jsContext?.exception()
        val decoded: Any? = when {
            exception == null -> null
            exception.isObject() -> exception.toDictionary()?.let { JSError.fromDictionary(it) }
            else -> exception.toString()
        }
        logger.d { "JS Exception: ${exception?.toObject()}" }
        logger.e { "JS Exception: $decoded" }
    }

    private fun setupJsContext() {
        val jsContext = JSContext()
        this.jsContext = jsContext
        initInterfaces(jsContext)
        jsContext.exceptionHandler = ::exceptionHandler
        jsContext.setName("PKJS: ${appInfo.longName}")
        jsContext.setInspectable(true)
    }

    private fun tearDownJsContext() {
        interfaces?.forEach { it.close() }
        interfaces = null
        jsContext = null
    }

    override suspend fun start() {
        setupJsContext()
        logger.d { "JS Context set up" }
        evaluateInternalScript("XMLHTTPRequest")
        logger.d { "XHR script evaluated" }
        evaluateInternalScript("startup")
        logger.d { "Startup script evaluated" }
        loadAppJs(jsPath.toString())
    }

    override suspend fun stop() {
        logger.d { "Stopping JS Context" }
        tearDownJsContext()
        logger.d { "JS Context torn down" }
    }

    override fun loadUrl(url: String) {
        logger.e { "TODO" }
    }

    override suspend fun loadAppJs(jsUrl: String) {
        SystemFileSystem.source(Path(jsUrl)).buffered().use {
            val js = it.readString()
            jsContext?.evaluateScript(js)
        }
        signalReady()
    }

    override suspend fun signalNewAppMessageData(data: String?): Boolean {
        jsContext?.globalObject?.invokeMethod("signalNewAppMessageData", listOf(data))
        return true
    }

    override suspend fun signalAppMessageAck(data: String?): Boolean {
        jsContext?.globalObject?.invokeMethod("signalAppMessageAck", listOf(data))
        return jsContext != null
    }

    override suspend fun signalAppMessageNack(data: String?): Boolean {
        jsContext?.globalObject?.invokeMethod("signalAppMessageNack", listOf(data))
        return jsContext != null
    }

    override suspend fun signalTimelineToken(callId: String, token: String) {
        val tokenJson = Json.encodeToString(mapOf("userToken" to token, "callId" to callId))
        jsContext?.globalObject?.invokeMethod("signalTimelineToken", listOf(tokenJson))
    }

    override suspend fun signalTimelineTokenFail(callId: String) {
        val tokenJson = Json.encodeToString(mapOf("userToken" to null, "callId" to callId))
        jsContext?.globalObject?.invokeMethod("signalTimelineToken", listOf(tokenJson))
    }

    override suspend fun signalReady() {
        jsContext?.evaluateScript("signalReady()")
    }
}