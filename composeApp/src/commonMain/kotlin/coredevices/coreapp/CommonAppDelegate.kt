package coredevices.coreapp

import co.touchlab.kermit.Logger
import com.mmk.kmpnotifier.notification.NotifierManager
import com.russhwolf.settings.Settings
import coredevices.CoreBackgroundSync
import coredevices.ExperimentalDevices
import coredevices.analytics.AnalyticsBackend
import coredevices.analytics.CoreAnalytics
import coredevices.analytics.setUser
import coredevices.coreapp.api.BugReports
import coredevices.coreapp.push.PushMessaging
import coredevices.coreapp.ui.screens.SHOWN_ONBOARDING
import coredevices.coreapp.util.AppUpdate
import coredevices.firestore.UsersDao
import coredevices.pebble.PebbleAppDelegate
import coredevices.pebble.account.FirestoreKnownWatchesSync
import coredevices.pebble.account.FirestoreLocker
import coredevices.pebble.account.PermissionDecisionSync
import coredevices.pebble.account.PermissionSettingsSync
import coredevices.pebble.health.PlatformHealthSync
import coredevices.pebble.services.PebbleAccountProvider
import coredevices.pebble.weather.WeatherFetcher
import coredevices.util.CommonBuildKonfig
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigHolder
import coredevices.util.DoneInitialOnboarding
import coredevices.util.emailOrNull
import coredevices.util.models.CactusSTTMode
import coredevices.util.sync.SyncManager
import coredevices.util.transcription.CactusModelPathProvider
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

internal const val STT_UPDATE_NOTIFIED_VERSION_KEY = "stt_update_notified_version"
internal const val STT_MODE_BEFORE_UPDATE_KEY = "stt_mode_before_update"
internal val STT_UPDATE_NOTIFICATION_ID = "stt_update_notif".hashCode()

class CommonAppDelegate(
    private val pushMessaging: PushMessaging,
    private val bugReports: BugReports,
    private val settings: Settings,
    private val doneInitialOnboarding: DoneInitialOnboarding,
    private val analyticsBackend: AnalyticsBackend,
    private val coreAnalytics: CoreAnalytics,
    private val pebbleAppDelegate: PebbleAppDelegate,
    private val appUpdate: AppUpdate,
    private val weatherFetcher: WeatherFetcher,
    private val experimentalDevices: ExperimentalDevices,
    private val coreConfigHolder: CoreConfigHolder,
    private val appContext: AppContext,
    private val usersDao: UsersDao,
    private val pebbleAccountProvider: PebbleAccountProvider,
    private val firestoreLocker: FirestoreLocker,
    private val firestoreKnownWatchesSync: FirestoreKnownWatchesSync,
    private val permissionSettingsSync: PermissionSettingsSync,
    private val permissionDecisionSync: PermissionDecisionSync,
    private val libPebble: LibPebble,
    private val platformHealthSync: PlatformHealthSync,
    private val syncManager: SyncManager,
) : CoreBackgroundSync {
    private val logger = Logger.withTag("CommonAppDelegate")
    private val syncInProgress = MutableStateFlow(false)
    // Boulder: Stundenjob und Nachholen entscheiden darunter, wer holt - sonst beide.
    private val nachholSperre = Mutex()

    private fun initCactus() {
        val modelProvider = try {
            org.koin.mp.KoinPlatform.getKoin().get<CactusModelPathProvider>()
        } catch (e: Exception) {
            logger.w(e) { "Cactus model provider not available" }
            return
        }
        try {
            modelProvider.initTelemetry()
        } catch (e: Exception) {
            logger.w(e) { "Cactus telemetry init skipped" }
        }
        try {
            val incompatible = modelProvider.getIncompatibleModels()
            if (incompatible.isNotEmpty()) {
                logger.d { "Incompatible models found: $incompatible" }
                val currentMode = coreConfigHolder.config.value.sttConfig.mode
                if (currentMode != CactusSTTMode.RemoteOnly && !settings.hasKey(STT_MODE_BEFORE_UPDATE_KEY)) {
                    settings.putInt(STT_MODE_BEFORE_UPDATE_KEY, currentMode.id)
                }
                coreConfigHolder.update(
                    coreConfigHolder.config.value.copy(
                        sttConfig = coreConfigHolder.config.value.sttConfig.copy(
                            mode = coreConfigHolder.config.value.sttConfig.mode
                                .takeUnless { it.usesLocalCactus() } ?: CactusSTTMode.RemoteOnly,
                            modelName = null,
                        )
                    )
                )
                incompatible.filter { it != CommonBuildKonfig.CACTUS_STT_MODEL }.forEach {
                    try {
                        modelProvider.deleteModel(it)
                    } catch (e: Exception) {
                        logger.w(e) { "Failed to delete incompatible model $it" }
                    }
                }
                val sttVersion = "${CommonBuildKonfig.CACTUS_STT_WEIGHTS_VERSION}:${CommonBuildKonfig.CACTUS_STT_ENG_WEIGHTS_VERSION}"
                if (settings.getStringOrNull(STT_UPDATE_NOTIFIED_VERSION_KEY) != sttVersion) {
                    settings.putString(STT_UPDATE_NOTIFIED_VERSION_KEY, sttVersion)
                    NotifierManager.getLocalNotifier().notify(
                        STT_UPDATE_NOTIFICATION_ID,
                        "Offline voice recognition",
                        "We've improved offline voice recognition. Open the app to update the model."
                    )
                }
            } else if (settings.hasKey(STT_MODE_BEFORE_UPDATE_KEY)) {
                val saved = CactusSTTMode.fromId(settings.getInt(STT_MODE_BEFORE_UPDATE_KEY, CactusSTTMode.RemoteOnly.id))
                settings.remove(STT_MODE_BEFORE_UPDATE_KEY)
                if (coreConfigHolder.config.value.sttConfig.mode == CactusSTTMode.RemoteOnly) {
                    coreConfigHolder.update(
                        coreConfigHolder.config.value.copy(
                            sttConfig = coreConfigHolder.config.value.sttConfig.copy(
                                mode = saved,
                                modelName = CommonBuildKonfig.CACTUS_STT_MODEL,
                            )
                        )
                    )
                }
            }
        } catch (e: Exception) {
            logger.w(e) { "Cactus incompatible model check skipped" }
        }
    }

    private fun oneTimeSetLockerOrderMode() {
        GlobalScope.launch {
            val key = "HAS_DONE_ONE_OFF_WATCHFACE_ORDER_SETTING"
            if (!settings.hasKey(key)) {
                val config = libPebble.config.value
                libPebble.updateConfig(
                    config.copy(
                        watchConfig = config.watchConfig.copy(
                            orderWatchfacesByLastUsed = true,
                        )
                    )
                )
                settings.putBoolean(key, true)
            }
        }
    }

    fun init() {
        usersDao.init()
        GlobalScope.launch(Dispatchers.Default) {
            usersDao.initUserDevToken(pebbleAccountProvider.get().devToken.value)
        }
        GlobalScope.launch(Dispatchers.Default) {
            Firebase.auth.currentUser?.emailOrNull?.let {
                analyticsBackend.setUser(email = it)
            }
        }
        initCactus()
        pushMessaging.init()
        bugReports.init()
        GlobalScope.launch(Dispatchers.Default) {
            weatherFetcher.init()
            withContext(Dispatchers.Main) {
                experimentalDevices.init()
            }
        }
        firestoreLocker.init()
        firestoreKnownWatchesSync.init()
        permissionSettingsSync.init()
        permissionDecisionSync.init()
        oneTimeSetLockerOrderMode()
        platformHealthSync.startAutoSync(GlobalScope)
        GlobalScope.launch(Dispatchers.Default) {
            syncManager.resume()
        }
        // Boulder: liest die Verbindung nur (watches) - BLE und Reconnect bleiben unberuehrt.
        GlobalScope.launch {
            nachholenBeimVerbinden(
                verbunden = libPebble.watches.map { uhren -> uhren.any { it is ConnectedPebbleDevice } },
                beiFehler = { logger.e(it) { "holeNach failed" } },
            ) { holeNach() }
        }
        if (settings.getBoolean(SHOWN_ONBOARDING, false)) {
            doneInitialOnboarding.onDoneInitialOnboarding()
        }
    }

    override suspend fun doBackgroundSync(scope: CoroutineScope, force: Boolean) {
        if (!syncInProgress.compareAndSet(false, true)) {
            logger.d { "Skipping background sync - already in progress" }
            return
        }
        // Use the background runtime window even if the sync intervals below haven't elapsed
        experimentalDevices.onBackgroundSync()
        val now = Clock.System.now()
        val config = coreConfigHolder.config.value
        val lastFullSync =
            Instant.fromEpochMilliseconds(settings.getLong(KEY_LAST_FULL_SYNC_MS, 0L))
        val lastPartialSync =
            Instant.fromEpochMilliseconds(settings.getLong(KEY_LAST_PARTIAL_SYNC_MS, 0L))
        // 0.9× slack absorbs scheduler/timer jitter
        val doFullSync =
            force || (now - lastFullSync) >= config.regularSyncInterval * 0.9
        val doPartialSync =
            doFullSync || (now - lastPartialSync) >= config.weatherSyncInterval * 0.9
        logger.d { "doBackgroundSync: doFullSync=$doFullSync doPartialSync=$doPartialSync" }
        if (!doPartialSync) {
            syncInProgress.value = false
            return
        }
        try {
            if (doFullSync) {
                settings.putLong(KEY_LAST_FULL_SYNC_MS, now.toEpochMilliseconds())
            }
            settings.putLong(KEY_LAST_PARTIAL_SYNC_MS, now.toEpochMilliseconds())
            // Boulder: Wetter, Locker und Hearts nur mit Uhr - ohne spart das ~0,1-0,3 mAh
            // je Stunde, holeNach holt sie beim Verbinden. Der 6-h-Teil hat einen eigenen
            // Zeitstempel, damit er nach dem Nachholen nicht gleich wieder laeuft.
            val mitUhr = force || uhrVerbunden()
            val arbeit = nachholSperre.withLock {
                if (mitUhr) settings.putLong(KEY_WETTER_MS, now.toEpochMilliseconds())
                val arbeit = mitUhr &&
                    (force || faellig(settings.getLong(KEY_ARBEIT_MS, 0L), now, config.regularSyncInterval))
                if (arbeit) settings.putLong(KEY_ARBEIT_MS, now.toEpochMilliseconds())
                arbeit
            }
            val jobs = buildList {
                if (mitUhr) {
                    add(
                        scope.launch {
                            weatherFetcher.fetchWeather(scope)
                        }
                    )
                }
                add(
                    scope.launch {
                        platformHealthSync.sync()
                        libPebble.requestHealthData()
                    }
                )
                if (arbeit) {
                    add(scope.launch {
                        pebbleAppDelegate.performBackgroundWork(scope)
                    })
                }
                if (doFullSync) {
                    add(scope.launch {
                        coreAnalytics.processHeartbeat()
                    })
                    add(scope.launch {
                        appUpdate.updateAvailable.value
                    })
                }
            }
            jobs.joinAll()
        } finally {
            syncInProgress.value = false
        }
        logger.d { "doBackgroundSync / finished doFullSync=$doFullSync" }
    }

    private fun uhrVerbunden(): Boolean = libPebble.watches.value.any { it is ConnectedPebbleDevice }

    /** Boulder: beim Verbinden nachholen, was ohne Uhr liegen blieb - nur wenn faellig. */
    private suspend fun holeNach() {
        val config = coreConfigHolder.config.value
        val now = Clock.System.now()
        val (wetter, arbeit) = nachholSperre.withLock {
            val wetter = faellig(settings.getLong(KEY_WETTER_MS, 0L), now, config.weatherSyncInterval)
            val arbeit = faellig(settings.getLong(KEY_ARBEIT_MS, 0L), now, config.regularSyncInterval)
            if (wetter) settings.putLong(KEY_WETTER_MS, now.toEpochMilliseconds())
            if (arbeit) settings.putLong(KEY_ARBEIT_MS, now.toEpochMilliseconds())
            wetter to arbeit
        }
        if (!wetter && !arbeit) return
        logger.d { "holeNach: wetter=$wetter arbeit=$arbeit" }
        coroutineScope {
            if (wetter) launch { weatherFetcher.fetchWeather(this) }
            if (arbeit) launch { pebbleAppDelegate.performBackgroundWork(this) }
        }
    }

    override suspend fun timeSinceLastSync(): Duration {
        val now = Clock.System.now()
        val lastFullSync = Instant.fromEpochMilliseconds(settings.getLong(KEY_LAST_FULL_SYNC_MS, 0L))
        return now - lastFullSync
    }

    override fun updateFullSyncPeriod(interval: Duration) {
        coreConfigHolder.update(
            coreConfigHolder.config.value.copy(
                regularSyncInterval = interval,
            )
        )
    }

    override fun updateWeatherSyncPeriod(interval: Duration) {
        coreConfigHolder.update(
            coreConfigHolder.config.value.copy(
                weatherSyncInterval = interval,
            )
        )
        rescheduleBgRefreshTask(appContext, coreConfigHolder.config.value)
    }
}

expect fun rescheduleBgRefreshTask(appContext: AppContext, coreConfig: CoreConfig)

private const val KEY_LAST_FULL_SYNC_MS = "last_full_sync_time_ms"
private const val KEY_LAST_PARTIAL_SYNC_MS = "last_partial_sync_time_ms"

// Boulder: wann Wetter bzw. der 6-h-Teil zuletzt wirklich liefen.
private const val KEY_WETTER_MS = "boulder_letztes_wetter_ms"
private const val KEY_ARBEIT_MS = "boulder_letzte_hintergrundarbeit_ms"

/** Boulder: mit demselben Spiel (0,9) wie die Takte oben. */
internal fun faellig(zuletztMs: Long, jetzt: Instant, intervall: Duration): Boolean =
    jetzt - Instant.fromEpochMilliseconds(zuletztMs) >= intervall * 0.9

/**
 * Boulder: startet [nachholen] bei jedem Wechsel auf "verbunden" in einem eigenen Job.
 * Haengt eines (Locker-Sync ohne Antwort), nimmt der Beobachter das naechste Verbinden
 * trotzdem an. Anders als im SyncWorker faengt hier sonst niemand: ein Fehler
 * (Geocoder, SQLite, HTTP) im GlobalScope beendete die ganze App.
 */
internal suspend fun nachholenBeimVerbinden(
    verbunden: Flow<Boolean>,
    beiFehler: (Exception) -> Unit,
    nachholen: suspend () -> Unit,
) = coroutineScope {
    verbunden.distinctUntilChanged().filter { it }.collect {
        launch {
            try {
                nachholen()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                beiFehler(e)
            }
        }
    }
}
