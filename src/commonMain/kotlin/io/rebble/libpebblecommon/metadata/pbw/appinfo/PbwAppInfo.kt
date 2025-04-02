package io.rebble.libpebblecommon.metadata.pbw.appinfo

import io.rebble.libpebblecommon.database.entity.LockerEntry
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class PbwAppInfo(
    val uuid: String,
    val shortName: String,
    val longName: String = "",
    val companyName: String = "",
    val versionCode: Long = -1,
    val versionLabel: String,
    val appKeys: Map<String, Int> = emptyMap(),
    val capabilities: List<String> = emptyList(),
    val resources: Resources,
    val sdkVersion: String = "3",
    // If list of target platforms is not present, pbw is legacy applite app
    val targetPlatforms: List<String> = listOf("aplite"),
    val watchapp: Watchapp = Watchapp()
) {
    fun toLockerEntry(): LockerEntry {
        return LockerEntry(
            id = Uuid.parse(uuid),
            version = versionLabel,
            title = longName.ifBlank { shortName },
            type = if (watchapp.watchface) "watchface" else "watchapp",
            developerName = companyName,
            configurable = capabilities.any { it == "configurable" },
            pbwVersionCode = versionCode.toString(),
            pbwIconResourceId = 0,
            sideloaded = true
        )
    }
}