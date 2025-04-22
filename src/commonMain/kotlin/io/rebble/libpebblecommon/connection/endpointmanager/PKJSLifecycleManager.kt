package io.rebble.libpebblecommon.connection.endpointmanager

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.Locker
import io.rebble.libpebblecommon.connection.LockerPBWCache
import io.rebble.libpebblecommon.database.dao.LockerEntryDao
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.disk.pbw.PbwApp
import io.rebble.libpebblecommon.js.PKJSApp
import io.rebble.libpebblecommon.js.PebbleJsDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class PKJSLifecycleManager(
    private val appContext: AppContext,
    private val locker: Locker,
    private val lockerPBWCache: LockerPBWCache,
    private val lockerEntryDao: LockerEntryDao,
    private val appRunState: ConnectedPebble.AppRunState,
    private val device: PebbleJsDevice
) {
    companion object {
        private val logger = Logger.withTag(PKJSLifecycleManager::class.simpleName!!)
    }
    private var runningApp: PKJSApp? = null

    private suspend fun handleAppStop() {
        runningApp?.stop()
    }

    private suspend fun handleNewRunningApp(lockerEntry: LockerEntry, scope: CoroutineScope) {
        try {
            val pbw = PbwApp(lockerPBWCache.getPBWFileForApp(lockerEntry.id))
            if (!pbw.hasPKJS) {
                logger.v { "App ${lockerEntry.id} does not have PKJS" }
                return
            }

            val jsPath = lockerPBWCache.getPKJSFileForApp(lockerEntry.id)
            runningApp = PKJSApp(
                appContext,
                jsPath,
                pbw.info,
                lockerEntry
            ).apply {
                start(device, scope)
            }
        } catch (e: Exception) {
            logger.e(e) { "Failed to init PKJS for app ${lockerEntry.id}" }
            return
        }
    }

    fun init(scope: CoroutineScope) {
        appRunState.runningApp.onEach {
            handleAppStop()
            if (it != null) {
                val lockerEntry = lockerEntryDao.get(it)
                lockerEntry?.let { handleNewRunningApp(lockerEntry, scope) }
            }
        }.launchIn(scope)
    }
}