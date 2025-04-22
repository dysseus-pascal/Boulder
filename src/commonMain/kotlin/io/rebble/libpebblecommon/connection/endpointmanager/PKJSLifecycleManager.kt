package io.rebble.libpebblecommon.connection.endpointmanager

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.LockerPBWCache
import io.rebble.libpebblecommon.connection.Transport
import io.rebble.libpebblecommon.database.dao.LockerEntryDao
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.disk.pbw.PbwApp
import io.rebble.libpebblecommon.js.PKJSApp
import io.rebble.libpebblecommon.js.PebbleJSDevice
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.services.app.AppRunStateService
import io.rebble.libpebblecommon.services.appmessage.AppMessageService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach

class PKJSLifecycleManager(
    private val appContext: AppContext,
    private val lockerPBWCache: LockerPBWCache,
    private val lockerEntryDao: LockerEntryDao,
    private val appRunStateService: AppRunStateService,
    private val notificationManager: NotificationManager,
    private val appMessagesService: AppMessageService,
    private val scope: CoroutineScope
) {
    companion object {
        private val logger = Logger.withTag(PKJSLifecycleManager::class.simpleName!!)
    }
    private lateinit var device: PebbleJSDevice
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
                device,
                jsPath,
                pbw.info,
                lockerEntry
            ).apply {
                start(scope)
            }
        } catch (e: Exception) {
            logger.e(e) { "Failed to init PKJS for app ${lockerEntry.id}" }
            return
        }
    }

    fun init(transport: Transport, watchInfo: WatchInfo) {
        this.device = PebbleJSDevice(
            transport,
            watchInfo,
            notificationManager,
            appMessagesService
        )
        appRunStateService.runningApp.onEach {
            handleAppStop()
            if (it != null) {
                val lockerEntry = lockerEntryDao.get(it)
                lockerEntry?.let { handleNewRunningApp(lockerEntry, scope) }
            }
        }.onCompletion {
            // Unsure if this is needed
            handleAppStop()
        }.launchIn(scope)
    }
}