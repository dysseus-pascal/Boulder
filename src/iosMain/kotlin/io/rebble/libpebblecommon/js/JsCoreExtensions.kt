package io.rebble.libpebblecommon.io.rebble.libpebblecommon.js

import platform.Foundation.NSString
import platform.JavaScriptCore.JSContext
import platform.JavaScriptCore.objectForKeyedSubscript
import platform.JavaScriptCore.setObject

operator fun JSContext.set(key: String, value: Any?) {
    @Suppress("CAST_NEVER_SUCCEEDS")
    this.setObject(value, forKeyedSubscript = key as NSString)
}
operator fun JSContext.get(key: String): Any? {
    @Suppress("CAST_NEVER_SUCCEEDS")
    return this.objectForKeyedSubscript(key as NSString)
}