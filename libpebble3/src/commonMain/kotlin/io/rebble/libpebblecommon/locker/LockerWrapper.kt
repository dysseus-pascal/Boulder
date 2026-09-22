package io.rebble.libpebblecommon.locker

import androidx.compose.runtime.Immutable
import io.rebble.libpebblecommon.database.entity.CompanionApp
import io.rebble.libpebblecommon.metadata.WatchType
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
enum class AppType(val code: String) {
    Watchface("watchface"),
    Watchapp("watchapp"),
    /** A pbw that carries only a plugin (no watchapp binary): registered, never synced to a watch. */
    Plugin("plugin"),
    ;

    companion object {
        fun fromString(value: String): AppType? = entries.firstOrNull { it.code == value }

        /** Types with an app store, so home feeds are preloaded per-type. Plugins have no store. */
        val storeTypes = listOf(Watchface, Watchapp)
    }
}

enum class AppCapability(val code: String) {
    Health("health"),
    Location("location"),
    Timeline("timeline"),
    // Not included: "configurable" (separate field in database, doesn't require permission grant)
    ;

    companion object {
        fun fromString(values: List<String>?): List<AppCapability> = values?.mapNotNull { value ->
            entries.firstOrNull { it.code == value }
        } ?: emptyList()
    }
}

@Immutable
data class AppPlatform(
    val watchType: WatchType,
    val screenshotImageUrl: String? = null,
    val listImageUrl: String? = null,
    val iconImageUrl: String? = null,
    val description: String? = null,
)

data class AppProperties(
    val id: Uuid,
    val type: AppType,
    val title: String,
    val developerName: String,
    val developerId: String?,
    val platforms: List<AppPlatform>,
    val version: String?,
    val hearts: Int?,
    val category: String?,
    val iosCompanion: CompanionApp?,
    val androidCompanion: CompanionApp?,
    val order: Int,
    val sourceLink: String?,
    val storeId: String?,
    val capabilities: List<AppCapability>,
)

data class AppBasicProperties(
    val id: Uuid,
    val type: AppType,
    val title: String,
    val developerName: String,
)

@Immutable
sealed class LockerWrapper {
    abstract val properties: AppProperties

    data class NormalApp(
        override val properties: AppProperties,
        val sideloaded: Boolean,
        val configurable: Boolean,
        val sync: Boolean,
    ) : LockerWrapper()

    data class SystemApp(
        override val properties: AppProperties,
        val systemApp: SystemApps,
    ) : LockerWrapper()
}

fun LockerWrapper.findCompatiblePlatform(watchType: WatchType?): AppPlatform? {
    val useWatchType = watchType?.getBestVariant(properties.platforms.map { it.watchType.codename })
    return properties.platforms.firstOrNull { it.watchType == useWatchType }
}