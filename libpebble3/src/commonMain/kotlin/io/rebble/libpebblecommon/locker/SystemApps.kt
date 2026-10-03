package io.rebble.libpebblecommon.locker

import io.rebble.libpebblecommon.SystemAppIDs
import io.rebble.libpebblecommon.metadata.WatchType
import kotlin.uuid.Uuid

enum class SystemApps(
    val uuid: Uuid,
    val displayName: String,
    val type: AppType,
    val compatiblePlatforms: List<WatchType>,
    val defaultOrder: Int,
    // Boulder: der Untertitel der Kachel - die Zifferblaetter der Boulder-Firmware sind nicht von Pebble.
    val developerName: String = "Pebble",
) {
    // Apps
    // Boulder: alle eins tiefer als bei Gravel, damit Kieselstrasse ohne Gleichstand Platz hat.
    Settings(SystemAppIDs.SETTINGS_APP_UUID, "Settings", AppType.Watchapp, WatchType.entries, defaultOrder = -11),
    Music(SystemAppIDs.MUSIC_APP_UUID, "Music", AppType.Watchapp, WatchType.entries, defaultOrder = -10),
    Notifications(SystemAppIDs.NOTIFICATIONS_APP_UUID, "Notifications", AppType.Watchapp, WatchType.entries, defaultOrder = -9),
    Alarms(SystemAppIDs.ALARMS_APP_UUID, "Alarms", AppType.Watchapp, WatchType.entries, defaultOrder = -8),
    Weather(SystemAppIDs.WEATHER_APP_UUID, "Weather", AppType.Watchapp, ALL_EXCEPT_APLITE, defaultOrder = -7),
    Health(SystemAppIDs.HEALTH_APP_UUID, "Health", AppType.Watchapp, ALL_EXCEPT_APLITE, defaultOrder = -6),
    // Boulder: die Boulder-Firmware hat statt Workout Kieselsport.
    Kieselsport(SystemAppIDs.KIESELSPORT_APP_UUID, "Kieselsport", AppType.Watchapp, ALL_EXCEPT_APLITE, defaultOrder = -5),
    // Boulder: und die Navigation, die frueher als Store-App kam.
    Kieselstrasse(SystemAppIDs.KIESELSTRASSE_APP_UUID, "Kieselstrasse", AppType.Watchapp, ALL_EXCEPT_APLITE, defaultOrder = -4),
    Watchfaces(SystemAppIDs.WATCHFACES_APP_UUID, "Watchfaces", AppType.Watchapp, WatchType.entries, defaultOrder = -3),
    Timeline(SystemAppIDs.TIMELINE_MENU_ENTRY_UUID, "Timeline", AppType.Watchapp, WatchType.entries, defaultOrder = 100),
    // Faces
    Tictoc(SystemAppIDs.TICTOC_APP_UUID, "Tictoc", AppType.Watchface, WatchType.entries, defaultOrder = -2),
    Kickstart(SystemAppIDs.KICKSTART_APP_UUID, "Kickstart", AppType.Watchface, ALL_EXCEPT_APLITE, defaultOrder = -1),
    // Boulder: gibt es nur in der Boulder-Firmware fuer emery. Steht hinter Kickstart, weil
    // insertSystemApps ein spaeter hinzugekommenes Zifferblatt hinter seinen Vorgaenger setzt.
    TimeStyleBedrock(
        SystemAppIDs.TIMESTYLE_BEDROCK_APP_UUID, "TimeStyle Bedrock", AppType.Watchface, listOf(WatchType.EMERY),
        defaultOrder = 0, developerName = "Boulder",
    ),
}

private val ALL_EXCEPT_APLITE = WatchType.entries.filter {
    it != WatchType.APLITE
}