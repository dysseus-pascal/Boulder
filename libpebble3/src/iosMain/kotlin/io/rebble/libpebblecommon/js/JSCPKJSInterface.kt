package io.rebble.libpebblecommon.io.rebble.libpebblecommon.js

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.js.JsRunner
import io.rebble.libpebblecommon.js.PKJSInterface
import io.rebble.libpebblecommon.js.PebbleJSDevice
import platform.JavaScriptCore.JSContext

class JSCPKJSInterface(jsRunner: JsRunner, device: PebbleJSDevice, libPebble: LibPebble) :
    PKJSInterface(jsRunner, device, libPebble), RegisterableJsInterface {
    private val logger = Logger.withTag("JSCPKJSInterface")
    override fun showToast(toast: String) {
        //TODO: Implement showToast for JSCPKJSInterface
        logger.e { "showToast() not implemented" }
    }

    override fun register(jsContext: JSContext) {
        jsContext["Pebble"] = mapOf(
            "showSimpleNotificationOnPebble" to this::showNotificationOnPebble,
            "getAccountToken" to this::getAccountToken,
            "getWatchToken" to this::getWatchToken,
            "showToast" to this::showToast,
            "showNotificationOnPebble" to this::showNotificationOnPebble,
            "openURL" to this::openURL
        )
    }

    override fun close() {
        // No-op
    }
}