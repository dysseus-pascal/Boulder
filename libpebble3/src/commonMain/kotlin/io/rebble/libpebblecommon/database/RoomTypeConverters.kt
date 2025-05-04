package io.rebble.libpebblecommon.database

import androidx.room.TypeConverter
import io.rebble.libpebblecommon.database.entity.ChannelGroup
import kotlinx.serialization.json.Json
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val json = Json { ignoreUnknownKeys = true }

class RoomTypeConverters {
    @TypeConverter
    fun StringToUuid(string: String?): Uuid? = string?.let { Uuid.parse(it) }

    @TypeConverter
    fun UuidToString(uuid: Uuid?): String? = uuid?.toString()

    @TypeConverter
    fun StringToChannelGroupList(value: String): List<ChannelGroup> {
        return json.decodeFromString(value)
    }

    @TypeConverter
    fun ChannelGroupListToString(list: List<ChannelGroup>): String {
        return json.encodeToString(list)
    }

    @TypeConverter
    fun LongToInstant(value: Long): Instant = Instant.fromEpochMilliseconds(value)

    @TypeConverter
    fun InstantToLong(instant: Instant): Long = instant.toEpochMilliseconds()
}