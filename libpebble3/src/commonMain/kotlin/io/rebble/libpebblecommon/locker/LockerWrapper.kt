package io.rebble.libpebblecommon.locker

import io.rebble.libpebblecommon.metadata.WatchType
import kotlin.uuid.Uuid

enum class AppType(val code: String) {
    Watchface("watchface"),
    Watchapp("watchapp"),
    ;

    companion object {
        fun fromString(value: String): AppType? = entries.firstOrNull { it.code == value }
    }
}

data class AppPlatform(
    val watchType: WatchType,
    val screenshotImageUrl: String? = null,
    val listImageUrl: String? = null,
    val iconImageUrl: String? = null,
)

data class AppProperties(
    val id: Uuid,
    val type: AppType,
    val title: String,
    val developerName: String,
    val platforms: List<AppPlatform>,
)

sealed class LockerWrapper {
    abstract val properties: AppProperties

    data class NormalApp(
        override val properties: AppProperties,
        val sideloaded: Boolean,
        val configurable: Boolean,
    ) : LockerWrapper()

    data class SystemApp(
        override val properties: AppProperties,
        val systemApp: SystemApps,
    ) : LockerWrapper()
}

fun LockerWrapper.findCompatiblePlatform(watchType: WatchType): AppPlatform? {
    return properties.platforms.firstOrNull { it.watchType == watchType } ?:
    properties.platforms.firstOrNull { watchType.getCompatibleAppVariants().contains(it.watchType) }
}