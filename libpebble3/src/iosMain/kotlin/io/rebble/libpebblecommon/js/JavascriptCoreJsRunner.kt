package io.rebble.libpebblecommon.js

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.js.JSCGeolocationInterface
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.js.JSCJSLocalStorageInterface
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import platform.JavaScriptCore.JSContext
import platform.JavaScriptCore.JSValue

class JavascriptCoreJsRunner(
    private val appContext: AppContext,
    private val libPebble: LibPebble,
    private val jsTokenUtil: JsTokenUtil,

    device: PebbleJSDevice,
    private val scope: CoroutineScope,
    appInfo: PbwAppInfo,
    lockerEntry: LockerEntry,
    jsPath: Path,
    urlOpenRequests: Channel<String>,
    private val pkjsBundleIdentifier: String = "coredevices.coreapp",
): JsRunner(appInfo, lockerEntry, jsPath, device, urlOpenRequests) {
    private var jsContext: JSContext? = null
    private val logger = Logger.withTag("JSCRunner-${appInfo.longName}")
    private var interfaces: List<RegisterableJsInterface>? = null

    private fun initInterfaces(jsContext: JSContext) {
        val instances = listOf(
            XMLHTTPRequestManager(scope, jsContext),
            JSTimeout(scope, jsContext),
            JSCPKJSInterface(this, device, libPebble, jsTokenUtil),
            JSCPrivatePKJSInterface(jsPath, this, device, scope, _outgoingAppMessages),
            JSCJSLocalStorageInterface(this, appContext, jsContext),
            JSCGeolocationInterface(scope, this)
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
        jsContext?.evalCatching(js, NSURL.fileURLWithPath(path))
    }

    private fun exceptionHandler(context: JSContext?, exception: JSValue?) {
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
        jsContext?.finalize()
        interfaces = null
        jsContext = null
    }

    private fun evaluateStandardLib() {
        evaluateInternalScript("XMLHTTPRequest")
        evaluateInternalScript("JSTimeout")
    }

    private fun setupNavigator() {
        jsContext?.set("navigator", mapOf(
            "userAgent" to "PKJS",
            "geolocation" to emptyMap<String, Any>()
        ))
    }

    override suspend fun start() {
        setupJsContext()
        setupNavigator()
        logger.d { "JS Context set up" }
        evaluateStandardLib()
        logger.d { "Standard lib scripts evaluated" }
        evaluateInternalScript("startup")
        logger.d { "Startup script evaluated" }
        loadAppJs(jsPath.toString())
    }

    override suspend fun stop() {
        logger.d { "Stopping JS Context" }
        tearDownJsContext()
        logger.d { "JS Context torn down" }
    }

    override suspend fun loadAppJs(jsUrl: String) {
        SystemFileSystem.source(Path(jsUrl)).buffered().use {
            val js = it.readString()
            jsContext?.evalCatching(js, NSURL.fileURLWithPath(jsUrl))
        }
        signalReady()
    }

    override suspend fun signalNewAppMessageData(data: String?): Boolean {
        jsContext?.evalCatching("globalThis.signalNewAppMessageData(${Json.encodeToString(data)})")
        return true
    }

    override suspend fun signalAppMessageAck(data: String?): Boolean {
        jsContext?.evalCatching("globalThis.signalAppMessageAck(${Json.encodeToString(data)})")
        return jsContext != null
    }

    override suspend fun signalAppMessageNack(data: String?): Boolean {
        jsContext?.evalCatching("globalThis.signalAppMessageNack(${Json.encodeToString(data)})")
        return jsContext != null
    }

    override suspend fun signalTimelineToken(callId: String, token: String) {
        val tokenJson = Json.encodeToString(mapOf("userToken" to token, "callId" to callId))
        jsContext?.evalCatching("globalThis.signalTimelineTokenSuccess($tokenJson)")
    }

    override suspend fun signalTimelineTokenFail(callId: String) {
        val tokenJson = Json.encodeToString(mapOf("userToken" to null, "callId" to callId))
        jsContext?.evalCatching("globalThis.signalTimelineTokenFailure($tokenJson)")
    }

    override suspend fun signalReady() {
        jsContext?.evalCatching("globalThis.signalReady()")
    }

    override suspend fun signalShowConfiguration() {
        jsContext?.evalCatching("globalThis.signalShowConfiguration()")
    }

    override suspend fun signalWebviewClosed(data: String?) {
        jsContext?.evalCatching("globalThis.signalWebviewClosedEvent(${Json.encodeToString(data)})")
    }

    override suspend fun eval(js: String) {
        jsContext?.evalCatching(js)
    }
}