package io.rebble.libpebblecommon.io.rebble.libpebblecommon.js

import android.webkit.JavascriptInterface
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.js.JSLocalStorageInterface
import io.rebble.libpebblecommon.js.JsRunner

class WebViewJSLocalStorageInterface(
    jsRunner: JsRunner,
    appContext: AppContext,
    private val evaluateJavascript: (String) -> Unit
): JSLocalStorageInterface(jsRunner, appContext) {

    override fun setLength(value: Int) {
        evaluateJavascript("localStorage.length = $value")
    }

    @JavascriptInterface
    override fun clear() {
        super.clear()
    }

    @JavascriptInterface
    override fun getItem(key: String): String? {
        return super.getItem(key)
    }

    @JavascriptInterface
    override fun key(index: Int): String? {
        return super.key(index)
    }

    @JavascriptInterface
    override fun removeItem(key: String) {
        super.removeItem(key)
    }

    @JavascriptInterface
    override fun setItem(key: String, value: String) {
        super.setItem(key, value)
    }
}