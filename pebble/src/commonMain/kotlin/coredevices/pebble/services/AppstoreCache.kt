package coredevices.pebble.services

import co.touchlab.kermit.Logger
import coredevices.database.AppstoreSource
import io.ktor.util.sha1
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.util.getTempFilePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class AppstoreCache(
    private val appContext: AppContext,
) {
    private val appCacheDir = getTempFilePath(appContext, "locker_cache")
    private val categoryCacheDir = getTempFilePath(appContext, "category_cache")
    private val logger = Logger.withTag("AppstoreCache")
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    companion object {
        private val STORE_APP_CACHE_AGE = 4.hours
    }

    suspend fun readApp(
        id: String,
        parameters: Map<String, String>,
        source: AppstoreSource,
    ): StoreAppResponse? = withContext(Dispatchers.Default) {
        val cacheFile = cacheFileForApp(id, parameters, source)
        try {
            if (SystemFileSystem.exists(cacheFile)) {
                SystemFileSystem.source(cacheFile).buffered().use {
                    val cached: CachedStoreAppResponse = json.decodeFromString(it.readString())
                    if (Clock.System.now() - cached.lastUpdated < STORE_APP_CACHE_AGE) {
                        return@withContext cached.response
                    }
                }
            }
        } catch (e: Exception) {
            logger.w(e) { "Failed to read cached appstore app for $id" }
        }
        return@withContext null
    }

    suspend fun writeApp(
        app: StoreAppResponse,
        parameters: Map<String, String>,
        source: AppstoreSource,
    ) = withContext(Dispatchers.Default) {
        val id = app.data.firstOrNull()?.id ?: return@withContext
        val cacheFile = cacheFileForApp(id, parameters, source)
        try {
            SystemFileSystem.sink(cacheFile).buffered().use {
                val toCache = CachedStoreAppResponse(
                    response = app,
                    lastUpdated = Clock.System.now(),
                )
                it.writeString(json.encodeToString(toCache))
            }
        } catch (e: Exception) {
            logger.w(e) { "Failed to write cached appstore app for ${app.data.firstOrNull()?.id}" }
        }
    }

    private fun createAppCacheDir() {
        try {
            if (!SystemFileSystem.exists(appCacheDir)) {
                SystemFileSystem.createDirectories(appCacheDir, false)
            }
        } catch (e: Exception) {
            logger.e(e) { "Failed to create cache directory: $appCacheDir" }
        }
    }

    private fun calculateAppCacheKey(
        id: String,
        parameters: Map<String, String>,
        source: AppstoreSource,
    ): String {
        val data = source.url + id + parameters.entries.sortedBy { it.key }
            .joinToString(separator = "&") { "${it.key}=${it.value}" }
        val hash = sha1(data.encodeToByteArray())
        return hash.toHexString()
    }

    private fun cacheFileForApp(
        id: String,
        parameters: Map<String, String>,
        source: AppstoreSource,
    ): Path {
        createAppCacheDir()
        val hash = calculateAppCacheKey(id, parameters, source)
        return Path(appCacheDir, "$hash.json")
    }

    suspend fun writeCategories(
        categories: List<StoreCategory>,
        type: AppType,
        source: AppstoreSource,
    ) = withContext(Dispatchers.Default) {
        val cacheFile = cacheFileForCategories(type, source)
        try {
            SystemFileSystem.sink(cacheFile).buffered().use {
                it.writeString(Json.encodeToString(categories))
            }
        } catch (e: Exception) {
            logger.w(e) { "Failed to write cached categories for type ${type.code}" }
        }
    }

    suspend fun readCategories(
        type: AppType,
        source: AppstoreSource,
    ): List<StoreCategory>? = withContext(Dispatchers.Default) {
        val cacheFile = cacheFileForCategories(type, source)
        try {
            if (SystemFileSystem.exists(cacheFile)) {
                SystemFileSystem.source(cacheFile).buffered().use {
                    val cachedJson = it.readString()
                    val cachedCategories: List<StoreCategory> = Json.decodeFromString(cachedJson)
                    if (cachedCategories.isNotEmpty()) {
                        return@withContext cachedCategories
                    }
                }
            }
        } catch (e: Exception) {
            logger.w(e) { "Failed to read cached categories for type ${type.code}" }
        }
        return@withContext null
    }

    private fun cacheFileForCategories(appType: AppType, source: AppstoreSource): Path {
        createCategoryCacheDir()
        val hash = calculateCategoryCacheKey(appType, source)
        return Path(categoryCacheDir, "$hash.json")
    }

    private fun calculateCategoryCacheKey(appType: AppType, source: AppstoreSource): String {
        val data = source.url + appType.code
        val hash = sha1(data.encodeToByteArray())
        return hash.toHexString()
    }

    private fun createCategoryCacheDir() {
        try {
            if (!SystemFileSystem.exists(categoryCacheDir)) {
                SystemFileSystem.createDirectories(categoryCacheDir, false)
            }
        } catch (e: Exception) {
            logger.e(e) { "Failed to create category cache directory: $categoryCacheDir" }
        }
    }
}

@Serializable
private data class CachedStoreAppResponse(
    val response: StoreAppResponse,
    val lastUpdated: Instant
)
