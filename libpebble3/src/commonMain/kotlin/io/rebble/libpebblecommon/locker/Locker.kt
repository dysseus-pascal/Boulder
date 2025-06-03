package io.rebble.libpebblecommon.locker

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LockerApi
import io.rebble.libpebblecommon.connection.WatchConfig
import io.rebble.libpebblecommon.connection.WatchManager
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.database.entity.LockerEntryAppstoreData
import io.rebble.libpebblecommon.database.entity.LockerEntryPlatform
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.disk.pbw.PbwApp
import io.rebble.libpebblecommon.disk.pbw.toLockerEntry
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.web.LockerModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
    private val config: WatchConfig,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
) : LockerApi {
    private val lockerEntryDao = database.lockerEntryDao()

    companion object {
        private val logger = Logger.withTag(Locker::class.simpleName!!)
    }

    override suspend fun sideloadApp(pbwPath: Path) {
        sideloadApp(pbwApp = PbwApp(pbwPath), loadOnWatch = true)
    }

    override fun getLocker(): Flow<List<LockerWrapper>> = lockerEntryDao.getAllFlow()
        .map { entries ->
            SystemApps.entries.map { systemApp ->
                LockerWrapper.SystemApp(
                    properties = AppProperties(
                        id = systemApp.uuid,
                        type = systemApp.type,
                        title = systemApp.displayName,
                        developerName = "Pebble",
                        platforms = systemApp.compatiblePlatforms.map {
                            AppPlatform(
                                watchType = it,
                                screenshotImageUrl = null,
                                listImageUrl = null,
                                iconImageUrl = null,
                            )
                        },
                    ),
                    systemApp = systemApp,
                )
            } + entries.mapNotNull apps@{ app ->
                val type = AppType.fromString(app.type) ?: return@apps null
                LockerWrapper.NormalApp(
                    properties = AppProperties(
                        id = app.id,
                        type = type,
                        title = app.title,
                        developerName = app.developerName,
                        platforms = app.platforms.mapNotNull platforms@{
                            val platform = WatchType.fromCodename(it.name) ?: return@platforms null
                            AppPlatform(
                                watchType = platform,
                                screenshotImageUrl = it.screenshotImageUrl,
                                listImageUrl = it.listImageUrl,
                                iconImageUrl = it.iconImageUrl,
                            )
                        },
                    ),
                    sideloaded = app.sideloaded,
                    configurable = app.configurable,
                    sync = app.orderIndex < config.lockerSyncLimit,
                )
            }
        }

    override fun setAppOrder(id: Uuid, order: Int) {
        libPebbleCoroutineScope.launch {
            lockerEntryDao.setOrder(id, order, config.lockerSyncLimit)
        }
    }

    suspend fun getApp(uuid: Uuid): LockerEntry? = lockerEntryDao.getEntry(uuid)

    suspend fun update(locker: LockerModel) {
        logger.d("update: ${locker.applications.size}")
        // TODO delete deleted entries, don't insert unchanged entries, etc
        val toInsert = locker.applications.map { it.asEntity() }
        lockerEntryDao.insertOrReplaceAndOrder(toInsert, config.lockerSyncLimit)
    }

    /**
     * Sideload an app to the watch.
     * This will insert the app into the locker database and optionally install it/launch it on the watch.
     * @param pbwApp The app to sideload.
     * @param loadOnWatch Whether to fully install the app on the watch (launch it). Defaults to true.
     */
    suspend fun sideloadApp(pbwApp: PbwApp, loadOnWatch: Boolean) {
        logger.d { "Sideloading app ${pbwApp.info.longName}" }
        val lockerEntry = pbwApp.toLockerEntry()
        pbwApp.source().buffered().use {
            lockerPBWCache.addPBWFileForApp(lockerEntry.id, it)
        }

        lockerEntryDao.insertOrReplaceAndOrder(lockerEntry, config.lockerSyncLimit)
        if (loadOnWatch) {
            watchManager.watches.value.filterIsInstance<ConnectedPebbleDevice>().forEach {
                it.launchApp(lockerEntry.id)
            }
        }
    }
}

fun io.rebble.libpebblecommon.web.LockerEntry.asEntity(): LockerEntry {
    val uuid = Uuid.parse(uuid)
    return LockerEntry(
        id = uuid,
        version = version ?: "", // FIXME
        title = title,
        type = type,
        developerName = developer.name,
        configurable = isConfigurable,
        pbwVersionCode = pbw?.releaseId ?: "", // FIXME
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
        platforms = hardwarePlatforms.map { platform ->
            LockerEntryPlatform(
                lockerEntryId = uuid,
                sdkVersion = platform.sdkVersion,
                processInfoFlags = platform.pebbleProcessInfoFlags,
                name = platform.name,
                screenshotImageUrl = platform.images.screenshot,
                listImageUrl = platform.images.list,
                iconImageUrl = platform.images.icon,
                pbwIconResourceId = pbw?.iconResourceId ?: 0,
            )
        },
//        recordHashcode = TODO(),
//        deleted = TODO(),
    )
}

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
                SystemFileSystem.createDirectories(pkjsCacheDir, false)
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

    fun clearPKJSFileForApp(appId: Uuid) {
        val pkjsPath = pkjsPathForApp(appId)
        if (SystemFileSystem.exists(pkjsPath)) {
            SystemFileSystem.delete(pkjsPath)
        }
    }
}
