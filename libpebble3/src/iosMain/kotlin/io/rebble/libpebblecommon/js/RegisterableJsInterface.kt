package io.rebble.libpebblecommon.js

import platform.JavaScriptCore.JSContext

interface RegisterableJsInterface: AutoCloseable {
    fun register(jsContext: JSContext)
}