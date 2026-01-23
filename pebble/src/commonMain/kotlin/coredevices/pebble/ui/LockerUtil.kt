package coredevices.pebble.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import co.touchlab.kermit.Logger
import coredevices.database.AppstoreSource
import coredevices.database.AppstoreSourceDao
import coredevices.pebble.Platform
import coredevices.pebble.account.FirestoreLocker
import coredevices.pebble.account.FirestoreLockerEntry
import coredevices.pebble.rememberLibPebble
import coredevices.pebble.services.StoreApplication
import coredevices.pebble.services.StoreCategory
import coredevices.pebble.services.StoreSearchResult
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigFlow
import io.rebble.libpebblecommon.database.entity.CompanionApp
import io.rebble.libpebblecommon.locker.AppPlatform
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.locker.LockerWrapper
import io.rebble.libpebblecommon.locker.SystemApps
import io.rebble.libpebblecommon.locker.findCompatiblePlatform
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.web.LockerEntryCompanionApp
import io.rebble.libpebblecommon.web.LockerEntryCompatibility
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

const val LOCKER_UI_LOAD_LIMIT = 100
private val logger = Logger.withTag("LockerUtil")

@Composable
private fun firestoreLockerContents(coreConfig: CoreConfig): List<FirestoreLockerEntry>? {
    val firestoreLocker: FirestoreLocker = koinInject()
    val firestoreLockerContents by produceState<List<FirestoreLockerEntry>?>(null, coreConfig.useNativeAppStore) {
        if (coreConfig.useNativeAppStore) {
            value = firestoreLocker.readLocker()
        }
    }
    return firestoreLockerContents
}

@Composable
private fun appstoreSources(): List<AppstoreSource>? {
    val appstoreSourceDao: AppstoreSourceDao = koinInject()
    val appstoreSources by appstoreSourceDao.getAllSources().collectAsState(null)
    return appstoreSources
}

private fun LockerWrapper.findStoreSource(
    firestoreLockerContents: List<FirestoreLockerEntry>?,
    appstoreSources: List<AppstoreSource>?,
    coreConfig: CoreConfig,
): AppstoreSource? {
    if (!coreConfig.useNativeAppStore) {
        return null
    }
    val firestoreEntry = firestoreLockerContents?.find { entry ->
        entry.uuid == properties.id
    } ?: return null
    return appstoreSources?.find { source ->
        source.url == firestoreEntry.appstoreSource
    }
}

@Composable
fun loadLockerEntries(type: AppType, searchQuery: String, watchType: WatchType): List<CommonApp>? {
    val libPebble = rememberLibPebble()
    val lockerQuery = remember(
        type,
        searchQuery,
    ) {
        libPebble.getLocker(
            type = type,
            searchQuery = searchQuery,
            limit = LOCKER_UI_LOAD_LIMIT,
        )
    }
    val entries by lockerQuery.collectAsState(null)
    val coreConfigFlow: CoreConfigFlow = koinInject()
    val coreConfig by coreConfigFlow.flow.collectAsState()
    val appstoreSources = appstoreSources()
    val firestoreLockerContents = firestoreLockerContents(coreConfig)
    if (entries == null || appstoreSources == null) {
        return null
    }
    return remember(entries, watchType, appstoreSources, firestoreLockerContents, coreConfig) {
        entries?.map {
            val appstoreSource = it.findStoreSource(firestoreLockerContents, appstoreSources, coreConfig)
            it.asCommonApp(watchType, appstoreSource, null)
        }
    }
}

@Composable
fun loadLockerEntry(uuid: Uuid?, watchType: WatchType): CommonApp? {
    if (uuid == null) {
        return null
    }
    val libPebble = rememberLibPebble()
    val lockerEntry by libPebble.getLockerApp(uuid).collectAsState(null)
    val coreConfigFlow: CoreConfigFlow = koinInject()
    val coreConfig by coreConfigFlow.flow.collectAsState()
    val appstoreSources = appstoreSources()
    val firestoreLockerContents = firestoreLockerContents(coreConfig)
    if (lockerEntry == null || appstoreSources == null) {
        return null
    }
    return remember(lockerEntry, watchType, appstoreSources, firestoreLockerContents, coreConfig) {
        val appstoreSource = lockerEntry?.findStoreSource(firestoreLockerContents, appstoreSources, coreConfig)
        logger.v { "appstoreSource = $appstoreSource" }
        lockerEntry?.asCommonApp(watchType, appstoreSource, null)
    }
}

data class CommonApp(
    val title: String,
    val developerName: String,
    val uuid: Uuid,
    val androidCompanion: CompanionApp?,
    val commonAppType: CommonAppType,
    val type: AppType,
    val category: String?,
    val version: String?,
    val listImageUrl: String?,
    val screenshotImageUrl: String?,
    val isCompatible: Boolean,
    val isNativelyCompatible: Boolean,
    val hearts: Int?,
    val description: String?,
    val developerId: String?,
    val categorySlug: String?,
    val storeId: String?,
    val sourceLink: String?,
    val appstoreSource: AppstoreSource?,
)

interface CommonAppTypeLocal {
    val order: Int
}

sealed class CommonAppType {
    data class Locker(
        val sideloaded: Boolean,
        val configurable: Boolean,
        val sync: Boolean,
        override val order: Int,
    ) : CommonAppType(), CommonAppTypeLocal

    data class Store(
        val storeApp: StoreApplication?,
        val storeSource: AppstoreSource,
    ) : CommonAppType()

    data class System(
        val app: SystemApps,
        override val order: Int,
    ) : CommonAppType(), CommonAppTypeLocal
}

fun LockerWrapper.asCommonApp(watchType: WatchType?, appstoreSource: AppstoreSource?, categories: List<StoreCategory>?): CommonApp {
    val compatiblePlatform = findCompatiblePlatform(watchType)
    return CommonApp(
        title = properties.title,
        developerName = properties.developerName,
        uuid = properties.id,
        androidCompanion = properties.androidCompanion,
        commonAppType = when (this) {
            is LockerWrapper.NormalApp -> CommonAppType.Locker(
                sideloaded = sideloaded,
                configurable = configurable,
                sync = sync,
                order = properties.order,
            )

            is LockerWrapper.SystemApp -> CommonAppType.System(
                app = systemApp,
                order = properties.order,
            )
        },
        type = properties.type,
        category = properties.category,
        version = properties.version,
        listImageUrl = compatiblePlatform?.listImageUrl,
        screenshotImageUrl = compatiblePlatform?.screenshotImageUrl,
        isCompatible = compatiblePlatform.isCompatible(),
        hearts = when (this) {
            is LockerWrapper.NormalApp -> properties.hearts
            is LockerWrapper.SystemApp -> null
        },
        description = compatiblePlatform?.description,
        isNativelyCompatible = when (this) {
            is LockerWrapper.NormalApp -> {
                val nativelyCompatible = when (watchType) {
                    // Emery is the only platform where "compatible" apps can be used but are
                    // "suboptimal" (need scaling). Enable flagging that.
                    WatchType.EMERY, WatchType.GABBRO -> properties.platforms.any { it.watchType == watchType }
                    else -> true
                }
                nativelyCompatible
            }

            is LockerWrapper.SystemApp -> true
        },
        developerId = properties.developerId,
        categorySlug = categories?.firstOrNull { it.name == properties.category }?.slug,
        storeId = properties.storeId,
        sourceLink = properties.sourceLink,
        appstoreSource = appstoreSource,
    )
}

fun StoreApplication.asCommonApp(watchType: WatchType, platform: Platform, source: AppstoreSource, categories: List<StoreCategory>?): CommonApp? {
    val appType = AppType.fromString(type)
    if (appType == null) {
        logger.w { "StoreApplication.asCommonApp() unknown type: $type" }
        return null
    }
    return CommonApp(
        title = title,
        developerName = author,
        uuid = Uuid.parse(uuid),
        androidCompanion = companions.android?.asCompanionApp(),
        commonAppType = CommonAppType.Store(
            storeSource = source,
            storeApp = this,
        ),
        type = appType,
        category = category,
        version = latestRelease.version,
        listImageUrl = listImage.values.firstOrNull(),
        screenshotImageUrl = screenshotImages.firstOrNull()?.values?.firstOrNull(),
        isCompatible = compatibility.isCompatible(watchType, platform),
        hearts = hearts,
        description = description,
        isNativelyCompatible = when (watchType) {
            // Emery is the only platform where "compatible" apps can be used but are
            // "suboptimal" (need scaling). Enable flagging that.
            WatchType.EMERY, WatchType.GABBRO -> {
                when {
                    // If store doesn't report binary info, mark as compatible
                    hardwarePlatforms == null -> true
                    // If store has binary info, only natively compatible if there is a matching binary
                    else ->hardwarePlatforms.any { it.name == watchType.codename && it.pebbleProcessInfoFlags != null }
                }
            }
            else -> true
        },
        storeId = id,
        developerId = developerId,
        sourceLink = this.source,
        categorySlug = categories?.firstOrNull { it.id == categoryId }?.slug,
        appstoreSource = source,
    )
}

fun StoreSearchResult.asCommonApp(watchType: WatchType, platform: Platform, source: AppstoreSource): CommonApp? {
    val appType = AppType.fromString(type)
    if (appType == null) {
        logger.w { "StoreApplication.asCommonApp() unknown type: $type" }
        return null
    }
    return CommonApp(
        title = title,
        developerName = author,
        uuid = Uuid.parse(uuid),
        androidCompanion = null,
        commonAppType = CommonAppType.Store(storeSource = source, storeApp = null),
        type = appType,
        category = category,
        version = null,
        listImageUrl = listImage,
        // TODO add fallback hardwarePlatforms
//        screenshotImageUrl = assetCollections.find { it.hardwarePlatform == watchType.codename }?.screenshots?.firstOrNull() ?: screenshotImages.firstOrNull(),
        screenshotImageUrl = screenshotImages.firstOrNull(),
        isCompatible = compatibility.isCompatible(watchType, platform),
        hearts = hearts,
        description = description,
        isNativelyCompatible = true, // TODO (but OK for now)
        storeId = id,
        developerId = null,
        sourceLink = null,
        categorySlug = null,
        appstoreSource = source,
    )
}

fun CommonApp.showOnMainLockerScreen(): Boolean = when (commonAppType) {
    is CommonAppType.Locker, is CommonAppType.Store -> true
    // Don't show system apps here (they'd always take up all the horizontal space). Show system
    // watchfaces.
    is CommonAppType.System -> type == AppType.Watchface
}

fun CommonApp.isSynced(): Boolean = when (commonAppType) {
    is CommonAppType.Locker -> commonAppType.sync
    is CommonAppType.Store -> false
    is CommonAppType.System -> true
}

fun LockerWrapper.isSynced(): Boolean = when (this) {
    is LockerWrapper.NormalApp -> sync
    is LockerWrapper.SystemApp -> true
}

fun AppPlatform?.isCompatible(): Boolean = this != null

fun LockerEntryCompanionApp.asCompanionApp(): CompanionApp = CompanionApp(
    id = id,
    icon = icon,
    name = name,
    url = url,
    required = required,
    pebblekitVersion = pebblekitVersion,
)

fun LockerEntryCompatibility.isCompatible(watchType: WatchType, platform: Platform): Boolean {
    if (platform == Platform.IOS && !ios.supported) return false
    if (platform == Platform.Android && !android.supported) return false
    val appVariants = buildSet {
        if (aplite.supported) add(WatchType.APLITE)
        if (basalt.supported) add(WatchType.BASALT)
        if (chalk.supported) add(WatchType.CHALK)
        if (diorite.supported) add(WatchType.DIORITE)
        if (emery.supported) add(WatchType.EMERY)
        if (flint?.supported == true) add(WatchType.FLINT)
        if (gabbro?.supported == true) add(WatchType.GABBRO)
    }
    return watchType.getCompatibleAppVariants().intersect(appVariants).isNotEmpty()
}
