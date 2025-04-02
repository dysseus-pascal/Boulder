package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.dao.LockerEntryDao
import io.rebble.libpebblecommon.database.dao.LockerSyncStatusDao
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.database.entity.LockerEntryPlatform
import io.rebble.libpebblecommon.database.entity.LockerEntryWithPlatforms
import io.rebble.libpebblecommon.disk.pbw.PbwApp
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.packets.blobdb.AppMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.math.sin
import kotlin.uuid.Uuid

class Locker(
    private val config: LibPebbleConfig,
    private val watchManager: WatchManager,
    private val database: Database,
    private val lockerPBWCache: LockerPBWCache,
) {
    private val lockerEntryDao: LockerEntryDao = database.lockerEntryDao()
    private val lockerSyncStatusDao: LockerSyncStatusDao = database.lockerSyncStatusDao()
    companion object {
        private val logger = Logger.withTag(Locker::class.simpleName!!)
        val appCompatibilityMatrix = mapOf(
            WatchType.APLITE to listOf(WatchType.APLITE),
            WatchType.BASALT to listOf(WatchType.BASALT, WatchType.APLITE),
            WatchType.CHALK to listOf(WatchType.CHALK),
            WatchType.DIORITE to listOf(WatchType.DIORITE, WatchType.APLITE),
            WatchType.EMERY to listOf(
                WatchType.EMERY,
                WatchType.BASALT,
                WatchType.DIORITE,
                WatchType.APLITE
            )
        )
    }

    fun init(scope: CoroutineScope) {
        watchManager.watches.onEach { watches ->
            val watchesToSync = watches
                .filterIsInstance<ConnectedPebbleDevice>()
                .filter {
                    lockerSyncStatusDao.getForWatchIdentifier(
                        it.transport.identifier.asString
                    )?.lockerDirty != false // true or null
                }
            if (watchesToSync.isNotEmpty()) {
                val lockerEntries = lockerEntryDao.getAllWithPlatforms()
                watchesToSync.forEach { watch ->
                    syncToConnectedWatch(watch, lockerEntries)
                }
            }
        }.launchIn(scope)
    }

    private fun List<LockerEntryWithPlatforms>.filterSupportedEntries(watch: ConnectedPebbleDevice): List<Pair<LockerEntry, LockerEntryPlatform>> {
        val watchType = watch.watchInfo.platform?.watchType ?: run {
            logger.e { "Watch ${watch.name} is of unknown type, cannot sync locker" }
            return emptyList()
        }
        return mapNotNull {
            val compatiblePlatforms = appCompatibilityMatrix[watchType]!!
            val compatiblePlatform = it.platforms.firstOrNull { plat -> plat.watchType == watchType }
                ?: it.platforms.firstOrNull { plat -> compatiblePlatforms.contains(plat.watchType) }
            if (compatiblePlatform != null) {
                it.entry to compatiblePlatform
            } else {
                logger.w { "No compatible platform for ${it.entry.title} on watch ${watch.name}" }
                null
            }
        }
    }

    private suspend fun syncToConnectedWatch(watch: ConnectedPebbleDevice, entries: List<LockerEntryWithPlatforms>) {
        logger.i { "${watch.name} Beginning locker sync to watch" }
        val supportedEntries = entries.filterSupportedEntries(watch)
        if (supportedEntries.isEmpty()) {
            logger.i { "${watch.name} Nothing to sync" }
            return
        }
        supportedEntries.forEach { (entry, platform) ->
            val versionSplit = entry.version.split(".")
            val sdkVersionSplit = platform.sdkVersion.split(".")
            //TODO: Sync only if data has changed
            watch.insertLockerEntry(
                AppMetadata(
                    uuid = entry.id,
                    flags = platform.processInfoFlags.toUInt(),
                    icon = 0u, //TODO
                    appVersionMajor = versionSplit[0].toUByte(),
                    appVersionMinor = versionSplit[1].toUByte(),
                    sdkVersionMajor = sdkVersionSplit[0].toUByte(),
                    sdkVersionMinor = sdkVersionSplit[1].toUByte(),
                    appName = entry.title
                )
            )
        }
    }

    /**
     * Sideload an app to the watch.
     * This will insert the app into the locker database and optionally install it/launch it on the watch.
     * @param pbwApp The app to sideload.
     * @param loadOnWatch Whether to fully install the app on the watch (launch it). Defaults to true.
     */
    suspend fun sideloadApp(pbwApp: PbwApp, loadOnWatch: Boolean = true) {
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
                name = header.appName.get()
            )
        }
        lockerEntryDao.insertOrReplaceWithPlatforms(lockerEntry, platforms)
        if (loadOnWatch) {
            watchManager.watches.value.filterIsInstance<ConnectedPebbleDevice>().forEach {
                it.launchApp(uuid)
            }
        }
    }
}

expect fun getLockerPBWCacheDirectory(context: AppContext): Path

class StaticLockerPBWCache(context: AppContext): LockerPBWCache(context) {
    override suspend fun handleCacheMiss(appId: Uuid): Path? = null
}

abstract class LockerPBWCache(context: AppContext) {
    private val cacheDir = getLockerPBWCacheDirectory(context)

    protected fun pathForApp(appId: Uuid): Path {
        return Path(cacheDir, "$appId.pbw")
    }

    protected abstract suspend fun handleCacheMiss(appId: Uuid): Path?

    suspend fun getPBWFileForApp(appId: Uuid): Path {
        val pbwPath = pathForApp(appId)
        return if (SystemFileSystem.exists(pbwPath)) {
            pbwPath
        } else {
            handleCacheMiss(appId) ?: error("Failed to find PBW file for app $appId")
        }
    }

    fun addPBWFileForApp(appId: Uuid, source: Source) {
        val targetPath = pathForApp(appId)
        SystemFileSystem.sink(targetPath).use { sink ->
            source.transferTo(sink)
        }
    }
}