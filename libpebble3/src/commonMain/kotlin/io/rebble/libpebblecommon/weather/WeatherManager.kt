package io.rebble.libpebblecommon.weather

import io.rebble.libpebblecommon.connection.Weather
import io.rebble.libpebblecommon.database.dao.WeatherAppRealDao
import io.rebble.libpebblecommon.database.entity.AppPrefsEntryDao
import io.rebble.libpebblecommon.database.entity.WeatherAppEntry
import io.rebble.libpebblecommon.database.entity.WeatherPrefsValue
import io.rebble.libpebblecommon.database.entity.setWeatherSettings
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class WeatherManager(
    private val weatherAppEntryDao: WeatherAppRealDao,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
    private val appPrefsEntryDao: AppPrefsEntryDao,
): Weather {
    override fun updateWeatherData(weatherData: List<WeatherLocationData>) {
        libPebbleCoroutineScope.launch {
            val existing = weatherAppEntryDao.getAll()
            val toDelete = existing.filter { existingEntry ->
                weatherData.none { it.key == existingEntry.key }
            }
            toDelete.forEach {
                weatherAppEntryDao.markForDeletion(it.key)
            }
            weatherAppEntryDao.insertOrReplace(weatherData.map { it.toWeatherAppEntry() })
            appPrefsEntryDao.setWeatherSettings(WeatherPrefsValue(weatherData.map { it.key }))
        }
    }
}

fun WeatherLocationData.toWeatherAppEntry() = WeatherAppEntry(
    key = key,
    currentTemp = currentTemp,
    currentWeatherType = currentWeatherType.code,
    todayHighTemp = todayHighTemp,
    todayLowTemp = todayLowTemp,
    tomorrowWeatherType = tomorrowWeatherType.code,
    tomorrowHighTemp = tomorrowHighTemp,
    tomorrowLowTemp = tomorrowLowTemp,
    lastUpdateTimeUtcSecs = lastUpdateTimeUtcSecs,
    isCurrentLocation = isCurrentLocation,
    locationName = locationName,
    forecastShort = forecastShort,
)

enum class WeatherType(val code: Byte) {
    PartlyCloudy(0),
    CloudyDay(1),
    LightSnow(2),
    LightRain(3),
    HeavyRain(4),
    HeavySnow(5),
    Generic(6),
    Sun(7),
    RainAndSnow(8),
    Unknown(255u.toByte()),
}

data class WeatherLocationData(
    val key: Uuid,
    val currentTemp: Short,
    val currentWeatherType: WeatherType,
    val todayHighTemp: Short,
    val todayLowTemp: Short,
    val tomorrowWeatherType: WeatherType,
    val tomorrowHighTemp: Short,
    val tomorrowLowTemp: Short,
    val lastUpdateTimeUtcSecs: Long,
    val isCurrentLocation: Boolean,
    val locationName: String,
    val forecastShort: String,
    val locationNameStartCase: String,
)