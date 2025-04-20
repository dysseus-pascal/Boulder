package io.rebble.libpebblecommon.web

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.Locker
import io.rebble.libpebblecommon.connection.RequestSync
import io.rebble.libpebblecommon.connection.WebServices
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * This will manage scheduling background tasks etc. For now, it just triggers stuff to happen
 * immediately (in a way that might be killed if we're not in the foreground etc).
 */
class WebSyncManager(
    private val webServices: WebServices,
    private val locker: Locker,
) : RequestSync {
    private val logger = Logger.withTag("WebSyncManager")

    override fun requestLockerSync() {
        GlobalScope.launch {
            // TODO probably don't want the logic in this class
            val response = webServices.fetchLocker()
            if (response == null) {
                logger.i("locker response is null")
                return@launch
            }
            locker.update(response)
        }
    }
}
