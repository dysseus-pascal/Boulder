package io.rebble.libpebblecommon.js

import io.rebble.libpebblecommon.packets.blobdb.buildNotificationItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlin.uuid.Uuid

abstract class PKJSInterface(protected val jsRunner: JsRunner, protected val device: PebbleJSDevice) {
    open fun showSimpleNotificationOnPebble(title: String, notificationText: String) {
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

    /**
     * Get account token
     * Sideloaded apps: hash of token and app UUID
     * Appstore apps: hash of token and developer ID
     * //TODO: offline token
     */
    open fun getAccountToken(): String {
        //XXX: This is a blocking call, but it's fine because it's called from a WebView thread, maybe
        return runBlocking(Dispatchers.IO) {
            JsTokenUtil.getAccountToken(Uuid.parse(jsRunner.appInfo.uuid)) ?: ""
        }
    }

    /**
     * Get token of the watch for storing settings
     * Sideloaded apps: hash of watch serial and app UUID
     * Appstore apps: hash of watch serial and developer ID
     */
    open fun getWatchToken(): String {
        return runBlocking(Dispatchers.IO) {
            JsTokenUtil.getWatchToken(
                uuid = Uuid.parse(jsRunner.appInfo.uuid),
                developerId = jsRunner.lockerEntry.appstoreData?.developerId,
                watchInfo = device.watchInfo
            )
        }
    }

    abstract fun showToast(toast: String)

    open fun showNotificationOnPebble(jsonObjectStringNotificationData: String) {
        TODO("Not yet implemented")
    }

    /**
     * Open a URL e.g. configuration page
     */
    open fun openURL(url: String): String {
        jsRunner.loadUrl(url)
        return url
    }
}