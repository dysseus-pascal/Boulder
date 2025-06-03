package io.rebble.libpebblecommon.locker

import io.rebble.libpebblecommon.SystemAppIDs.CALENDAR_APP_UUID
import io.rebble.libpebblecommon.metadata.WatchType
import kotlin.uuid.Uuid

enum class SystemApps(
    val uuid: Uuid,
    val displayName: String,
    val type: AppType,
    val compatiblePlatforms: List<WatchType>,
) {
    Calendar(CALENDAR_APP_UUID, "Calendar", AppType.Watchapp, WatchType.entries),
}