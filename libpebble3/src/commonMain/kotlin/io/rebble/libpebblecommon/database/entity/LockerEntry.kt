package io.rebble.libpebblecommon.database.entity

import androidx.room.Embedded
import coredev.BlobDatabase
import coredev.GenerateRoomEntity
import io.rebble.libpebblecommon.database.dao.BlobDbItem
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.packets.blobdb.AppMetadata
import io.rebble.libpebblecommon.structmapper.SUUID
import io.rebble.libpebblecommon.structmapper.StructMapper
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@GenerateRoomEntity(primaryKey = "id", databaseId = BlobDatabase.App)
data class LockerEntry(
    val id: Uuid,
    val version: String,
    val title: String,
    val type: String,
    val developerName: String,
    val configurable: Boolean,
    val pbwVersionCode: String,
    val sideloaded: Boolean = false,
    @Embedded val appstoreData: LockerEntryAppstoreData? = null,
    val platforms: List<LockerEntryPlatform>,
) : BlobDbItem {
    override fun key(): UByteArray = SUUID(StructMapper(), id).toBytes()

    override fun value(platform: WatchType): UByteArray? {
        return asMetadata(platform)?.toBytes()
    }

    override fun recordHashCode(): Int = hashCode()
}

fun LockerEntry.asMetadata(platform: WatchType): AppMetadata? {
    val compatiblePlatforms = platform.getCompatibleAppVariants().map { it.codename }
    val entryPlatform = platforms.firstOrNull { it.name in compatiblePlatforms } ?: return null
    val appVersionMatch = APP_VERSION_REGEX.find(version)
    val appVersionMajor = appVersionMatch?.groupValues?.getOrNull(1) ?: return null
    val appVersionMinor = appVersionMatch.groupValues.getOrNull(2) ?: return null
    val sdkVersionMatch = APP_VERSION_REGEX.find(entryPlatform.sdkVersion)
    val sdkVersionMajor = sdkVersionMatch?.groupValues?.getOrNull(1) ?: return null
    val sdkVersionMinor = sdkVersionMatch.groupValues.getOrNull(2) ?: return null
    return AppMetadata(
        uuid = id,
        flags = entryPlatform.processInfoFlags.toUInt(),
        icon = entryPlatform.pbwIconResourceId.toUInt(),
        appVersionMajor = appVersionMajor.toUByte(),
        appVersionMinor = appVersionMinor.toUByte(),
        sdkVersionMajor = sdkVersionMajor.toUByte(),
        sdkVersionMinor = sdkVersionMinor.toUByte(),
        appName = title
    )
}

data class LockerEntryAppstoreData(
    val hearts: Int,
    val developerId: String,
    val timelineEnabled: Boolean,
    val removeLink: String,
    val shareLink: String,
    val pbwLink: String,
    val userToken: String,
)

@Serializable
data class LockerEntryPlatform(
    val lockerEntryId: Uuid,
    val sdkVersion: String,
    val processInfoFlags: Int,
    val name: String,
    val screenshotImageUrl: String? = null,
    val listImageUrl: String? = null,
    val iconImageUrl: String? = null,
    val pbwIconResourceId: Int,
)

private val APP_VERSION_REGEX = Regex("(\\d+)\\.(\\d+)(:?-.*)?")
