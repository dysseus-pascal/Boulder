package io.rebble.libpebblecommon.connection.endpointmanager

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.LibPebbleConfigFlow
import io.rebble.libpebblecommon.connection.BuiltInCompanions
import io.rebble.libpebblecommon.connection.CompanionApp
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.connection.pkjsBestaetigt
import io.rebble.libpebblecommon.database.dao.LockerEntryRealDao
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.disk.pbw.PbwApp
import io.rebble.libpebblecommon.js.CompanionAppDevice
import io.rebble.libpebblecommon.js.PKJSApp
import io.rebble.libpebblecommon.locker.Locker
import io.rebble.libpebblecommon.locker.LockerPBWCache
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.services.app.AppRunStateService
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import io.rebble.libpebblecommon.services.appmessage.AppMessageService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

class CompanionAppLifecycleManager(
    private val lockerPBWCache: LockerPBWCache,
    private val lockerEntryDao: LockerEntryRealDao,
    private val appRunStateService: AppRunStateService,
    private val appMessagesService: AppMessageService,
    private val locker: Locker,
    private val connectionScope: ConnectionCoroutineScope,
    private val libPebbleConfigFlow: LibPebbleConfigFlow,
    private val libpebbleCoroutineScope: LibPebbleCoroutineScope,
    // Boulder: fest eingebaute Begleiter (Kieselsport u. a., siehe BuiltInCompanions).
    private val builtInCompanions: BuiltInCompanions,
): ConnectedPebble.PKJS, ConnectedPebble.CompanionAppControl {
    companion object {
        private val logger = Logger.withTag(CompanionAppLifecycleManager::class.simpleName!!)
    }

    private lateinit var device: CompanionAppDevice

    private var activeAppScope: CoroutineScope = CoroutineScope(Job().also { it.cancel() })

    private val runningApps: MutableStateFlow<List<CompanionApp>> = MutableStateFlow(emptyList())
    @Deprecated("Use more generic currentCompanionAppSession instead and cast if necessary")
    override val currentPKJSSession: StateFlow<PKJSApp?> = PKJSStateFlow(runningApps)

    override val currentCompanionAppSessions: StateFlow<List<CompanionApp>>
        get() = runningApps.asStateFlow()

    private suspend fun handleAppStop() {
        activeAppScope.cancel()
        runningApps.value.forEach { app ->
            // Don't error out if one app fails to stop
            try {
                app.stop()
            } catch (e: Exception) {
                logger.e(e) { "Error stopping companion app: ${e.message}" }
            }
        }
        runningApps.value = emptyList()
    }

    private suspend fun handleNewRunningApp(lockerEntry: LockerEntry) {
        try {
            val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
                logger.e(throwable) { "Unhandled exception in CompanionAppLifecycleManager-${lockerEntry.id}: ${throwable.message}" }
            }
            activeAppScope = connectionScope +
                    Job() +
                    CoroutineName("CompanionAppLifecycleManager-${lockerEntry.id}") +
                    exceptionHandler

            val pbw = PbwApp(lockerPBWCache.getPBWFileForApp(lockerEntry.id, lockerEntry.version, locker))
            if (runningApps.value.isNotEmpty()) {
                logger.w { "App ${lockerEntry.id} is already running, stopping it before starting a new one" }
                runningApps.value.forEach { it.stop() }
            }

            val newApps = createCompanionApps(pbw, lockerEntry)
            runningApps.value = newApps

            // Boulder: begrenzter Eingang mit Log statt BUFFERED, das still verwarf (BoulderBegleiterEingang.kt).
            val appIncomingChannels = newApps.map { begleiterEingang(lockerEntry.id) }

            newApps.zip(appIncomingChannels).forEach { (app, channel) ->
                app.start(channel.receiveAsFlow())
            }

            activeAppScope.launch {
                device.inboundAppMessages(lockerEntry.id).collect { message ->
                    for (channel in appIncomingChannels) {
                        channel.reicheWeiter(message, lockerEntry.id)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "Failed to init Companion app for app ${lockerEntry.id}: ${e.message}" }
            handleAppStop()
            return
        }
    }

    // Boulder: wie handleNewRunningApp, aber fuer einen Begleiter aus BuiltInCompanions.
    private suspend fun handleBuiltInApp(uuid: Uuid, app: CompanionApp) {
        try {
            val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
                logger.e(throwable) { "Unhandled exception in built-in companion $uuid: ${throwable.message}" }
            }
            activeAppScope = connectionScope +
                    Job() +
                    CoroutineName("BuiltInCompanion-$uuid") +
                    exceptionHandler
            runningApps.value = listOf(app)
            val channel = begleiterEingang(uuid)
            app.start(channel.receiveAsFlow())
            activeAppScope.launch {
                device.inboundAppMessages(uuid).collect { channel.reicheWeiter(it, uuid) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "Failed to start built-in companion for $uuid: ${e.message}" }
            handleAppStop()
        }
    }

    private fun createCompanionApps(
        pbw: PbwApp,
        lockerEntry: LockerEntry
    ): List<CompanionApp> {
        return buildList {
            // Boulder: ein eingebauter Begleiter neben PebbleKit JS nimmt den Platz des Wegs zu
            // Android-Apps ein (BuiltInCompanions.createBeside). Er entsteht ZUERST: ob PebbleKit JS
            // bestaetigt, haengt an ihm (BestaetigtSelbst).
            val daneben = builtInCompanions.createBeside(lockerEntry.id, device, pkjsRunning = pbw.hasPKJS)
            val pkjsApp = if (pbw.hasPKJS) {
                val jsPath = lockerPBWCache.getPKJSFileForApp(lockerEntry.id, lockerEntry.version)
                PKJSApp(
                    device,
                    jsPath,
                    pbw.info,
                    lockerEntry,
                    connectionScope,
                    bestaetigt = pkjsBestaetigt(daneben),
                )
            } else null
            pkjsApp?.let { add(it) }
            if (daneben != null) {
                add(daneben)
            } else if (libPebbleConfigFlow.value.watchConfig.appMessageToMultipleCompanions || pkjsApp == null) {
                createPlatformSpecificCompanionAppControl(
                    device = device,
                    appInfo = pbw.info,
                    pkjsRunning = pkjsApp != null,
                    legacyPebbleKitEnabled = libPebbleConfigFlow.value.watchConfig.enableLegacyPebbleKit,
                    connectionCoroutineScope = connectionScope,
                    libPebbleCoroutineScope = libpebbleCoroutineScope,
                )?.let {
                    add(it)
                }
            }
        }
    }

    fun init(identifier: PebbleIdentifier, watchInfo: WatchInfo) {
        this.device = CompanionAppDevice(
            identifier,
            watchInfo,
            appMessagesService
        )
        appRunStateService.runningApp.onEach {
            handleAppStop()
            // Boulder: ein eingebauter Begleiter geht vor - auch fuer Systemapps wie Kieselsport,
            // die keinen Eintrag mit PBW im Locker haben. Dann gibt es keinen zweiten Weg ueber den
            // Locker, und keine App bekommt dieselbe Nachricht zweimal.
            val eingebaut = it?.let { uuid -> builtInCompanions.create(uuid, device) }
            if (it != null && eingebaut != null) {
                handleBuiltInApp(it, eingebaut)
            } else if (it != null) {
                val lockerEntry = lockerEntryDao.getEntry(it)
                lockerEntry?.let {
                    if (!it.systemApp) {
                        handleNewRunningApp(lockerEntry)
                    }
                }
            }
        }.onCompletion {
            // Unsure if this is needed
            handleAppStop()
        }.launchIn(connectionScope)
    }
}

/**
 * Hack to keep backwards compatibilty with the old ConnectedPebble.PKJS interface. It creates a state flow that only
 * exposes PKJSApp instances
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
class PKJSStateFlow(private val runningAppStateFlow: StateFlow<List<CompanionApp>>): StateFlow<PKJSApp?> {
    override val value: PKJSApp?
        get() = runningAppStateFlow.value.filterIsInstance<PKJSApp>().firstOrNull()
    override val replayCache: List<PKJSApp?>
        get() = runningAppStateFlow.replayCache.map { it.filterIsInstance<PKJSApp>().firstOrNull() }

    override suspend fun collect(collector: FlowCollector<PKJSApp?>): Nothing {
        runningAppStateFlow.map { it.filterIsInstance<PKJSApp>().firstOrNull() }.collect(collector)
        throw IllegalStateException("This collect should never stop because parent is a state flow")
    }
}

expect fun createPlatformSpecificCompanionAppControl(
    device: CompanionAppDevice,
    appInfo: PbwAppInfo,
    pkjsRunning: Boolean,
    legacyPebbleKitEnabled: Boolean,
    libPebbleCoroutineScope: LibPebbleCoroutineScope,
    connectionCoroutineScope: ConnectionCoroutineScope,
): CompanionApp?
