package io.rebble.libpebblecommon.connection.endpointmanager

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.Transport
import io.rebble.libpebblecommon.database.dao.LockerEntryRealDao
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.disk.pbw.PbwApp
import io.rebble.libpebblecommon.js.PKJSApp
import io.rebble.libpebblecommon.js.PebbleJSDevice
import io.rebble.libpebblecommon.locker.Locker
import io.rebble.libpebblecommon.locker.LockerPBWCache
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.services.app.AppRunStateService
import io.rebble.libpebblecommon.services.appmessage.AppMessageService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach

class PKJSLifecycleManager(
    private val lockerPBWCache: LockerPBWCache,
    private val lockerEntryDao: LockerEntryRealDao,
    private val appRunStateService: AppRunStateService,
    private val appMessagesService: AppMessageService,
    private val locker: Locker,
    private val scope: ConnectionCoroutineScope
): ConnectedPebble.PKJS {
    companion object {
        private val logger = Logger.withTag(PKJSLifecycleManager::class.simpleName!!)
    }
    private lateinit var device: PebbleJSDevice
    private val runningApp: MutableStateFlow<PKJSApp?> = MutableStateFlow(null)
    override val currentPKJSSession: StateFlow<PKJSApp?> = runningApp.asStateFlow()

    private suspend fun handleAppStop() {
        runningApp.value?.stop()
        runningApp.value = null
    }

    private suspend fun handleNewRunningApp(lockerEntry: LockerEntry, scope: CoroutineScope) {
        try {
            val pbw = PbwApp(lockerPBWCache.getPBWFileForApp(lockerEntry.id, locker))
            if (runningApp.value != null) {
                logger.w { "App ${lockerEntry.id} is already running, stopping it before starting a new one" }
                runningApp.value?.stop()
            }
            if (!pbw.hasPKJS) {
                logger.v { "App ${lockerEntry.id} does not have PKJS" }
                runningApp.value = null
                return
            }

            val jsPath = lockerPBWCache.getPKJSFileForApp(lockerEntry.id)
            runningApp.value = PKJSApp(
                device,
                jsPath,
                pbw.info,
                lockerEntry,
            ).apply {
                start(scope)
            }
        } catch (e: Exception) {
            logger.e(e) { "Failed to init PKJS for app ${lockerEntry.id}: ${e.message}" }
            runningApp.value = null
            return
        }
    }

    fun init(transport: Transport, watchInfo: WatchInfo) {
        this.device = PebbleJSDevice(
            transport,
            watchInfo,
            appMessagesService
        )
        appRunStateService.runningApp.onEach {
            handleAppStop()
            if (it != null) {
                val lockerEntry = lockerEntryDao.getEntry(it)
                lockerEntry?.let { handleNewRunningApp(lockerEntry, scope) }
            }
        }.onCompletion {
            // Unsure if this is needed
            handleAppStop()
        }.launchIn(scope)
    }
}