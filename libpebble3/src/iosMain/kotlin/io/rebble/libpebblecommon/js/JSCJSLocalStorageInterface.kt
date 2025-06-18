package io.rebble.libpebblecommon.io.rebble.libpebblecommon.js

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.js.JSLocalStorageInterface
import io.rebble.libpebblecommon.js.JsRunner
import io.rebble.libpebblecommon.js.RegisterableJsInterface
import io.rebble.libpebblecommon.js.get
import io.rebble.libpebblecommon.js.set
import platform.JavaScriptCore.JSContext
import platform.JavaScriptCore.JSValue

class JSCJSLocalStorageInterface(
    jsRunner: JsRunner,
    appContext: AppContext
): JSLocalStorageInterface(jsRunner, appContext), RegisterableJsInterface {
    private lateinit var localStorage: JSValue
    override fun register(jsContext: JSContext) {
        jsContext["localStorage"] = mapOf(
            "getItem" to this::getItem,
            "setItem" to this::setItem,
            "removeItem" to this::removeItem,
            "clear" to this::clear,
            "key" to this::key
        )
        localStorage = jsContext["localStorage"]!!
        setLength(getLength())
    }

    override fun setLength(value: Int) {
        localStorage["length"] = value
    }

    override fun close() {
        TODO("Not yet implemented")
    }

}