package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.dao.LockerEntryDao
import io.rebble.libpebblecommon.database.dao.LockerSyncStatusDao
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.database.entity.LockerEntryAppstoreData
import io.rebble.libpebblecommon.database.entity.LockerEntryPlatform
import io.rebble.libpebblecommon.database.entity.LockerEntryWithPlatforms
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.disk.pbw.PbwApp
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.packets.blobdb.AppMetadata
import io.rebble.libpebblecommon.web.LockerModel
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class Locker(
    private val watchManager: WatchManager,
    database: Database,
    private val lockerPBWCache: LockerPBWCache,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
) : LockerApi {
    private val lockerEntryDao: LockerEntryDao = database.lockerEntryDao()
    private val lockerSyncStatusDao: LockerSyncStatusDao = database.lockerSyncStatusDao()

    companion object {
        private val logger = Logger.withTag(Locker::class.simpleName!!)
    }

    private val lockerSyncQueue = Channel<List<ConnectedPebbleDevice>>(Channel.RENDEZVOUS)

    sealed class LockerSyncState {
        data object Idle : LockerSyncState()
        data object Syncing : LockerSyncState()
    }

    private val lockerSyncStatus = MutableStateFlow<LockerSyncState>(LockerSyncState.Idle)

    fun init() {
        watchManager.watches.debounce(1.seconds).onEach { watches ->
            requestLockerResync()
        }.launchIn(libPebbleCoroutineScope)
        libPebbleCoroutineScope.launch {
            lockerSyncQueue.consumeEach { watches ->
                syncLockerToWatches(watches)
            }
        }
    }

    override suspend fun sideloadApp(pbwPath: Path) {
        sideloadApp(pbwApp = PbwApp(pbwPath), loadOnWatch = true)
    }

    override fun getLocker(): Flow<List<LockerEntryWithPlatforms>> =
        lockerEntryDao.getAllWithPlatformsFlow()

    suspend fun getApp(uuid: Uuid): LockerEntry? = lockerEntryDao.get(uuid)

    suspend fun update(locker: LockerModel) {
        logger.d("update: ${locker.applications.size}")
        locker.applications.forEach { entry ->
            val entity = entry.asEntity()
            val platforms = entry.hardwarePlatforms.map { platform ->
                LockerEntryPlatform(
                    lockerEntryId = entity.id,
                    sdkVersion = platform.sdkVersion,
                    processInfoFlags = platform.pebbleProcessInfoFlags,
                    name = platform.name,
                    screenshotImageUrl = platform.images.screenshot,
                    listImageUrl = platform.images.list,
                    iconImageUrl = platform.images.icon,
                )
            }
            lockerEntryDao.insertOrReplaceWithPlatforms(entity, platforms)
        }
    }

    private suspend fun requestLockerResync() {
        logger.d { "Requesting locker resync" }
        lockerSyncQueue.send(watchManager.watches.value.filterIsInstance<ConnectedPebbleDevice>())
    }

    private suspend fun syncLockerToWatches(watches: List<ConnectedPebbleDevice>) {
        val watchesToSync = watches
            .filter {
                lockerSyncStatusDao.getForWatchIdentifier(
                    it.transport.identifier.asString
                )?.lockerDirty != false // true or null
            }
        if (watchesToSync.isNotEmpty()) {
            lockerSyncStatus.value = LockerSyncState.Syncing
            val lockerEntries = lockerEntryDao.getAllWithPlatforms()
            try {
                watchesToSync.forEach { watch ->
                    syncToConnectedWatch(watch, lockerEntries)
                    lockerSyncStatusDao.markNotDirty(watch.transport.identifier.asString)
                }
            } finally {
                lockerSyncStatus.value = LockerSyncState.Idle
            }
        }
    }

    private fun List<LockerEntryWithPlatforms>.filterSupportedEntries(watch: ConnectedPebbleDevice): List<Pair<LockerEntry, LockerEntryPlatform>> {
        val watchType = watch.watchInfo.platform.watchType ?: run {
            logger.e { "Watch ${watch.name} is of unknown type, cannot sync locker" }
            return emptyList()
        }
        return mapNotNull {
            val compatiblePlatforms = watchType.getCompatibleAppVariants()
            val compatiblePlatform =
                it.platforms.firstOrNull { plat -> plat.watchType == watchType }
                    ?: it.platforms.firstOrNull { plat -> compatiblePlatforms.contains(plat.watchType) }
            if (compatiblePlatform != null) {
                it.entry to compatiblePlatform
            } else {
                logger.w { "No compatible platform for ${it.entry.title} on watch ${watch.name}" }
                null
            }
        }
    }

    private suspend fun syncToConnectedWatch(
        watch: ConnectedPebbleDevice,
        entries: List<LockerEntryWithPlatforms>
    ) {
        logger.i { "${watch.name} Beginning locker sync to watch" }
        val supportedEntries = entries.filterSupportedEntries(watch)
        if (supportedEntries.isEmpty()) {
            logger.i { "${watch.name} Nothing to sync" }
            return
        }
        supportedEntries.forEach { (entry, platform) ->
            val newData = entry.asMetaData(platform)
            if (newData == null) {
                logger.w("Couldn't transform app: $entry")
                return@forEach
            }
            if (watch.isLockerEntryNew(newData)) {
                watch.insertLockerEntry(newData)
            }
        }
    }

    private suspend fun waitForLockerSyncIdle() {
        lockerSyncStatus.drop(1).filterIsInstance<LockerSyncState.Idle>().first()
    }

    /**
     * Sideload an app to the watch.
     * This will insert the app into the locker database and optionally install it/launch it on the watch.
     * @param pbwApp The app to sideload.
     * @param loadOnWatch Whether to fully install the app on the watch (launch it). Defaults to true.
     */
    suspend fun sideloadApp(pbwApp: PbwApp, loadOnWatch: Boolean) {
        logger.d { "Sideloading app ${pbwApp.info.longName}" }
        val uuid = Uuid.parse(pbwApp.info.uuid)
        pbwApp.source().buffered().use {
            lockerPBWCache.addPBWFileForApp(uuid, it)
        }
        val lockerEntry = pbwApp.info.toLockerEntry()
        val platforms = pbwApp.info.targetPlatforms.mapNotNull {
            val watchType = WatchType.fromCodename(it) ?: run {
                logger.w { "Unknown watch type in pbw while processing sideload request: $it" }
                return@mapNotNull null
            }
            val header = pbwApp.getBinaryHeaderFor(watchType)
            LockerEntryPlatform(
                lockerEntryId = uuid,
                sdkVersion = "${header.sdkVersionMajor.get()}.${header.sdkVersionMinor.get()}",
                processInfoFlags = header.flags.get().toInt(),
                name = watchType.codename,
            )
        }
        // Offload app so we force reinstall
        watchManager.watches.value
            .filterIsInstance<ConnectedPebbleDevice>()
            .forEach { watch ->
                watch.offloadApp(uuid)
            }
        lockerEntryDao.insertOrReplaceWithPlatforms(lockerEntry, platforms)
        val isSynced = libPebbleCoroutineScope.async { waitForLockerSyncIdle() }
        requestLockerResync()
        isSynced.await()
        if (loadOnWatch) {
            watchManager.watches.value.filterIsInstance<ConnectedPebbleDevice>().forEach {
                it.launchApp(uuid)
            }
        }
    }
}

fun io.rebble.libpebblecommon.web.LockerEntry.asEntity(): LockerEntry = LockerEntry(
    id = Uuid.parse(uuid),
    version = version ?: "", // FIXME
    title = title,
    type = type,
    developerName = developer.name,
    configurable = isConfigurable,
    pbwVersionCode = pbw?.releaseId ?: "", // FIXME
    pbwIconResourceId = pbw?.iconResourceId ?: 0, // FIXME
    sideloaded = false,
    appstoreData = LockerEntryAppstoreData(
        hearts = hearts,
        developerId = developer.id,
        timelineEnabled = isTimelineEnabled,
        removeLink = links.remove,
        shareLink = links.share,
        pbwLink = pbw?.file ?: "", // FIXME
        userToken = userToken,
    ),
)

expect fun getLockerPBWCacheDirectory(context: AppContext): Path

class StaticLockerPBWCache(
    context: AppContext,
    private val httpClient: HttpClient,
) :
    LockerPBWCache(context) {
    override suspend fun handleCacheMiss(appId: Uuid, locker: Locker): Path? {
        val pbwPath = pathForApp(appId)
        val pbwUrl = locker.getApp(appId)?.appstoreData?.pbwLink ?: return null
        return withTimeoutOrNull(5.seconds) {
            val response = httpClient.get(pbwUrl)
            if (!response.status.isSuccess()) {
                Logger.i("http call failed: $response")
                return@withTimeoutOrNull null
            }
            SystemFileSystem.sink(pbwPath).use { sink ->
                response.bodyAsChannel().readRemaining().transferTo(sink)
            }
            pbwPath
        }
    }
}

abstract class LockerPBWCache(context: AppContext) {
    private val cacheDir = getLockerPBWCacheDirectory(context)
    private val pkjsCacheDir = Path(getLockerPBWCacheDirectory(context), "pkjs")

    protected fun pathForApp(appId: Uuid): Path {
        return Path(cacheDir, "$appId.pbw")
    }

    protected fun pkjsPathForApp(appId: Uuid): Path {
        return Path(pkjsCacheDir, "$appId.js")
    }

    protected abstract suspend fun handleCacheMiss(appId: Uuid, locker: Locker): Path?

    suspend fun getPBWFileForApp(appId: Uuid, locker: Locker): Path {
        val pbwPath = pathForApp(appId)
        return if (SystemFileSystem.exists(pbwPath)) {
            pbwPath
        } else {
            handleCacheMiss(appId, locker) ?: error("Failed to find PBW file for app $appId")
        }
    }

    fun addPBWFileForApp(appId: Uuid, source: Source) {
        val targetPath = pathForApp(appId)
        SystemFileSystem.sink(targetPath).use { sink ->
            source.transferTo(sink)
        }
    }

    fun getPKJSFileForApp(appId: Uuid): Path {
        val pkjsPath = pkjsPathForApp(appId)
        val appPath = pathForApp(appId)
        return when {
            SystemFileSystem.exists(pkjsPath) -> pkjsPath
            SystemFileSystem.exists(appPath) -> {
                val pbwApp = PbwApp(pathForApp(appId))
                pbwApp.getPKJSFile().use { source ->
                    SystemFileSystem.sink(pkjsPath).use { sink ->
                        source.transferTo(sink)
                    }
                }
                pkjsPath
            }
            else -> error("Failed to find PBW file for app $appId while extracting JS")
        }
    }
}

private val APP_VERSION_REGEX = Regex("(\\d+)\\.(\\d+)(:?-.*)?")

fun LockerEntry.asMetaData(platform: LockerEntryPlatform): AppMetadata? {
    val appVersionMatch = APP_VERSION_REGEX.find(version)
    val appVersionMajor = appVersionMatch?.groupValues?.getOrNull(1) ?: return null
    val appVersionMinor = appVersionMatch.groupValues.getOrNull(2) ?: return null
    val sdkVersionMatch = APP_VERSION_REGEX.find(platform.sdkVersion)
    val sdkVersionMajor = sdkVersionMatch?.groupValues?.getOrNull(1) ?: return null
    val sdkVersionMinor = sdkVersionMatch.groupValues.getOrNull(2) ?: return null
    return AppMetadata(
        uuid = id,
        flags = platform.processInfoFlags.toUInt(),
        icon = 0u, //TODO
        appVersionMajor = appVersionMajor.toUByte(),
        appVersionMinor = appVersionMinor.toUByte(),
        sdkVersionMajor = sdkVersionMajor.toUByte(),
        sdkVersionMinor = sdkVersionMinor.toUByte(),
        appName = title
    )
}