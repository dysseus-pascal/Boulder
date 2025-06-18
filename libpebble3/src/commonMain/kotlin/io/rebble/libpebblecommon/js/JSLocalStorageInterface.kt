package io.rebble.libpebblecommon.js

import com.russhwolf.settings.Settings
import com.russhwolf.settings.get
import io.rebble.libpebblecommon.connection.AppContext

internal expect fun createJSSettings(appContext: AppContext, id: String): Settings

abstract class JSLocalStorageInterface(
    jsRunner: JsRunner,
    appContext: AppContext,
) {
    private val settings = createJSSettings(appContext, jsRunner.appInfo.uuid)

    abstract fun setLength(value: Int)
    fun getLength(): Int = settings.keys.size

    open fun getItem(key: String): String? = settings[key]

    open fun setItem(key: String, value: String) {
        settings.putString(key, value)
        setLength(settings.keys.size)
    }

    open fun removeItem(key: String) {
        settings.remove(key)
        setLength(settings.keys.size)
    }

    open fun clear() {
        settings.clear()
        setLength(0)
    }

    open fun key(index: Int): String? = settings.keys.elementAtOrNull(index)
}