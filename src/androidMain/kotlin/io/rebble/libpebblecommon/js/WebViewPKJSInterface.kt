package io.rebble.libpebblecommon.js

import android.content.Context
import android.webkit.JavascriptInterface
import android.widget.Toast
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.packets.blobdb.buildNotificationItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlin.uuid.Uuid

class WebViewPKJSInterface(private val jsRunner: JsRunner, private val device: PebbleJSDevice, private val context: Context): PKJSInterface {
    companion object {
        private val logger = Logger.withTag(WebViewPKJSInterface::class.simpleName!!)
    }
    @JavascriptInterface
    override fun showSimpleNotificationOnPebble(title: String, notificationText: String) {
        runBlocking {
            device.sendNotification(
                buildNotificationItem(Uuid.random()) {
                    timestamp = Clock.System.now().epochSeconds.toUInt()
                    attributes {
                        title { title }
                        body { notificationText }
                    }
                }
            )
        }
    }

    @JavascriptInterface
    override fun getAccountToken(): String {
        //XXX: This is a blocking call, but it's fine because it's called from a WebView thread, maybe
        return runBlocking(Dispatchers.IO) {
            JsTokenUtil.getAccountToken(Uuid.parse(jsRunner.appInfo.uuid)) ?: ""
        }
    }

    @JavascriptInterface
    override fun getWatchToken(): String {
        return runBlocking(Dispatchers.IO) {
            JsTokenUtil.getWatchToken(
                uuid = Uuid.parse(jsRunner.appInfo.uuid),
                developerId = jsRunner.lockerEntry.appstoreData?.developerId,
                watchInfo = device.watchInfo
            )
        }
    }

    @JavascriptInterface
    override fun showToast(toast: String) {
        Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
    }

    @JavascriptInterface
    override fun showNotificationOnPebble(jsonObjectStringNotificationData: String) {
        TODO("Not yet implemented")
    }

    @JavascriptInterface
    override fun openURL(url: String): String {
        logger.d { "Opening URL" }
        jsRunner.loadUrl(url)
        return url
    }
}