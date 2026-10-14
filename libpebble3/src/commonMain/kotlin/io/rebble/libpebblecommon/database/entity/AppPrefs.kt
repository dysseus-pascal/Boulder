package io.rebble.libpebblecommon.database.entity

import coredev.BlobDatabase
import coredev.GenerateRoomEntity
import io.rebble.libpebblecommon.database.dao.BlobDbItem
import io.rebble.libpebblecommon.database.dao.ValueParams
import io.rebble.libpebblecommon.database.entity.WeatherPrefsValue.Companion.asBytes
import io.rebble.libpebblecommon.database.entity.WeatherPrefsValue.Companion.encodeToString
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import io.rebble.libpebblecommon.structmapper.SFixedString
import io.rebble.libpebblecommon.structmapper.SUByte
import io.rebble.libpebblecommon.structmapper.SUUIDList
import io.rebble.libpebblecommon.structmapper.StructMappable
import io.rebble.libpebblecommon.structmapper.StructMapper
import io.rebble.libpebblecommon.util.Endian
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

@GenerateRoomEntity(
    primaryKey = "id",
    databaseId = BlobDatabase.AppConfigs,
    windowBeforeSecs = -1,
    windowAfterSecs = -1,
    onlyInsertAfter = false,
    sendDeletions = true,
)
data class AppPrefsEntry(
    val id: String,
    val value: String,
) : BlobDbItem {
    override fun key(): UByteArray = SFixedString(
        mapper = StructMapper(),
        initialSize = id.length,
        default = id,
    ).toBytes()

    override fun value(params: ValueParams): UByteArray? {
        return when (id) {
            KEY_WEATHER_APP -> WeatherPrefsValue.fromString(value)?.asBytes()
            else -> null
        }
    }

    override fun recordHashCode(): Int = hashCode()
}

private const val KEY_WEATHER_APP = "weatherApp"
private val json = Json { ignoreUnknownKeys = true }

suspend fun AppPrefsEntryDao.setWeatherSettings(weatherPrefs: WeatherPrefsValue) {
    insertOrReplace(
        AppPrefsEntry(
            id = KEY_WEATHER_APP,
            value = weatherPrefs.encodeToString(),
        )
    )
}

/**
 * Boulder: die Reihenfolge der Wetterorte, wie Boulder sie der Uhr zuletzt
 * geschickt hat (Ort 0 ist der Standardort der Uhr). Null, wenn nie gesetzt.
 */
suspend fun AppPrefsEntryDao.getWeatherSettings(): WeatherPrefsValue? =
    WeatherPrefsValue.fromString(getEntry(KEY_WEATHER_APP)?.value)

@Serializable
data class WeatherPrefsValue(
    val locationUuids: List<Uuid>,
) {
    companion object {
        fun WeatherPrefsValue.encodeToString(): String = json.encodeToString(this)
        fun fromString(value: String?): WeatherPrefsValue? = value?.let { json.decodeFromString(value) }
        fun WeatherPrefsValue.asBytes(): UByteArray = WeatherPrefsBlobItem(locationUuids).toBytes()
    }
}

class WeatherPrefsBlobItem(
    locationUuids: List<Uuid>,
) : StructMappable(endianness = Endian.Little) {
    val numLocations = SUByte(m, locationUuids.size.toUByte())
    val uuids = SUUIDList(m, locationUuids)
}