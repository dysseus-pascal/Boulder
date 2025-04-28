package io.rebble.libpebblecommon.js

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Message
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json


class WebViewJsRunner(
    val context: Context,
    device: PebbleJSDevice,
    val scope: CoroutineScope,
    appInfo: PbwAppInfo,
    lockerEntry: LockerEntry,
    jsPath: Path,
    libPebble: LibPebble,
): JsRunner(appInfo, lockerEntry, jsPath, device) {

    companion object {
        const val API_NAMESPACE = "Pebble"
        const val PRIVATE_API_NAMESPACE = "_$API_NAMESPACE"
        const val STARTUP_URL = "file:///android_asset/webview_startup.html"
        private val logger = Logger.withTag(WebViewJsRunner::class.simpleName!!)
    }

    private var webView: WebView? = null
    private val initializedLock = Object()
    private val publicJsInterface = WebViewPKJSInterface(this, device, context, libPebble)
    private val privateJsInterface = WebViewPrivatePKJSInterface(this, device, scope, _outgoingAppMessages)
    private val interfaces = setOf(
            Pair(API_NAMESPACE, publicJsInterface),
            Pair(PRIVATE_API_NAMESPACE, privateJsInterface)
    )

    private val webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            return true
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            logger.d { "Page finished loading: $url" }
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            super.onReceivedError(view, request, error)
            logger.e {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    "Error loading page: ${error?.errorCode} ${error?.description}"
                } else {
                    "Error loading page: ${error?.toString()}"
                }
            }
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            super.onReceivedSslError(view, handler, error)
            logger.e { "SSL error loading page: ${error?.primaryError}" }
            handler?.cancel()
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            if (isForbidden(request?.url)) {
                return object : WebResourceResponse("text/plain", "utf-8", null) {
                    override fun getStatusCode(): Int {
                        return 403
                    }

                    override fun getReasonPhrase(): String {
                        return "Forbidden"
                    }
                }
            } else {
                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    private fun isForbidden(url: Uri?): Boolean {
        return if (url == null) {
            logger.w { "Blocking null URL" }
            true
        } else if (url.scheme?.uppercase() != "FILE") {
            false
        } else if (url.path?.uppercase() == jsPath.toString().uppercase()) {
            false
        } else {
            logger.w { "Blocking access to file: ${url.path}" }
            true
        }
    }

    private val chromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            consoleMessage?.let {
                logger.d { "WebView: ${it.message()}" }
            }
            return false
        }

        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            return false
        }

        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            return false
        }

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            return false
        }

        override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            return false
        }

        override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
            return false
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            //Stub
        }

        override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
            return false
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            logger.d { "Geolocation permission requested for $origin" }
            callback?.invoke(origin, true, false)
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            request?.deny()
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun init() = withContext(Dispatchers.Main) {
        webView = WebView(context).also {
            it.setWillNotDraw(true)
            val settings = it.settings
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false

            //TODO: use WebViewAssetLoader instead
            settings.allowUniversalAccessFromFileURLs = true
            settings.allowFileAccessFromFileURLs = true

            settings.databaseEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            it.clearCache(true)

            interfaces.forEach { (namespace, jsInterface) ->
                it.addJavascriptInterface(jsInterface, namespace)
            }
            webView?.webViewClient = webViewClient
            webView?.webChromeClient = chromeClient
        }
    }


    override suspend fun start() {
        synchronized(initializedLock) {
            check(webView == null) { "WebviewJsRunner already started" }
        }
        try {
            init()
        } catch (e: Exception) {
            synchronized(initializedLock) {
                webView = null
            }
            throw e
        }
        check(webView != null) { "WebView not initialized" }
        logger.d { "WebView initialized" }
        loadApp(jsPath.toString())
    }

    override suspend fun stop() {
        //TODO: Close config screens

        withContext(Dispatchers.Main) {
            interfaces.forEach { (namespace, _) ->
                webView?.removeJavascriptInterface(namespace)
            }
            webView?.loadUrl("about:blank")
            webView?.stopLoading()
            webView?.clearHistory()
            webView?.removeAllViews()
            webView?.clearCache(true)
            webView?.destroy()
        }
        synchronized(initializedLock) {
            webView = null
        }
    }

    override fun loadUrl(url: String) {
        TODO()
    }

    private suspend fun loadApp(url: String) {
        check(webView != null) { "WebView not initialized" }
        withContext(Dispatchers.Main) {
            webView?.loadUrl(
                    Uri.parse(STARTUP_URL).buildUpon()
                            .appendQueryParameter("params", "{\"loadUrl\": \"$url\"}")
                            .build()
                            .toString()
            )
        }
    }

    override suspend fun loadAppJs(jsUrl: String) {
        webView?.let { webView ->
            if (jsUrl.isNullOrBlank() || !jsUrl.endsWith(".js")) {
                logger.e { "loadUrl passed to loadAppJs empty or invalid" }
                return
            }

            withContext(Dispatchers.Main) {
                webView.loadUrl("javascript:loadScript('$jsUrl')")
            }
        } ?: error("WebView not initialized")
    }

    override suspend fun signalTimelineToken(callId: String, token: String) {
        val tokenJson = Json.encodeToString(mapOf("userToken" to token, "callId" to callId))
        withContext(Dispatchers.Main) {
            webView?.loadUrl("javascript:signalTimelineTokenSuccess('${Uri.encode(tokenJson)}')")
        }
    }

    override suspend fun signalTimelineTokenFail(callId: String) {
        val tokenJson = Json.encodeToString(mapOf("userToken" to null, "callId" to callId))
        withContext(Dispatchers.Main) {
            webView?.loadUrl("javascript:signalTimelineTokenFailure('${Uri.encode(tokenJson)}')")
        }
    }

    override suspend fun signalReady() {
        val readyDeviceIds = listOf(device.transport.identifier.asString)
        val readyJson = Json.encodeToString(readyDeviceIds)
        withContext(Dispatchers.Main) {
            webView?.loadUrl("javascript:signalReady(${Uri.encode(readyJson)})")
        }
    }

    override suspend fun signalNewAppMessageData(data: String?): Boolean {
        withContext(Dispatchers.Main) {
            webView?.loadUrl("javascript:signalNewAppMessageData(${Uri.encode("'" + (data ?: "null") + "'")})")
        }
        return true
    }

    override suspend fun signalAppMessageAck(data: String?): Boolean {
        withContext(Dispatchers.Main) {
            webView?.loadUrl("javascript:signalAppMessageAck(${Uri.encode("'" + (data ?: "null") + "'")})")
        }
        return true
    }

    override suspend fun signalAppMessageNack(data: String?): Boolean {
        withContext(Dispatchers.Main) {
            webView?.loadUrl("javascript:signalAppMessageNack(${Uri.encode("'" + (data ?: "null") + "'")})")
        }
        return true
    }
}