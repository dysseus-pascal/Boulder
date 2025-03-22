package io.rebble.libpebblecommon.database

import androidx.room.TypeConverter
import kotlin.uuid.Uuid

class RoomTypeConverters {
    @TypeConverter
    fun StringToUuid(string: String?): Uuid? = string?.let { Uuid.parse(it) }

    @TypeConverter
    fun UuidToString(uuid: Uuid?): String? = uuid?.toString()
}