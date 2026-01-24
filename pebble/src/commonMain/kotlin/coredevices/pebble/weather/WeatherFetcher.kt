package coredevices.pebble.weather

import androidx.compose.ui.text.intl.Locale
import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import coredevices.pebble.services.RealPebbleWebServices
import coredevices.util.CoreConfigFlow
import coredevices.util.WeatherUnit
import io.rebble.libpebblecommon.SystemAppIDs.WEATHER_APP_UUID
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.buildTimelinePin
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.util.GeolocationPositionResult
import io.rebble.libpebblecommon.util.SystemGeolocation
import io.rebble.libpebblecommon.weather.WeatherLocationData
import io.rebble.libpebblecommon.weather.WeatherType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

class WeatherFetcher(
    private val systemGeolocation: SystemGeolocation,
    private val coreConfigFlow: CoreConfigFlow,
    private val pebbleWebServices: RealPebbleWebServices,
    private val libPebble: LibPebble,
    private val clock: Clock,
    private val settings: Settings,
) {
    private val logger = Logger.withTag("WeatherFetcher")

    companion object {
        private const val SETTINGS_KEY_HAS_DONE_ONE_SYNC = "has_done_one_weather_sync"
    }

    suspend fun init() {
        // One-off sync on first launch after this ships
        if (settings.getBoolean(SETTINGS_KEY_HAS_DONE_ONE_SYNC, false)) {
            return
        }
        settings.putBoolean(SETTINGS_KEY_HAS_DONE_ONE_SYNC, true)
        fetchWeather()
    }

    suspend fun fetchWeather() {
        val weatherEnabled = coreConfigFlow.value.fetchWeather
        val pinsEnabled = coreConfigFlow.value.weatherPinsV2
        val units = coreConfigFlow.value.weatherUnits
        if (!pinsEnabled || !weatherEnabled) {
            Day.entries.forEach {
                libPebble.delete(it.dayUuid)
                libPebble.delete(it.nightUuid)
            }
        }
        if (!weatherEnabled) {
            libPebble.updateWeatherData(emptyList())
            return
        }
        val location = systemGeolocation.getCurrentPosition()
        when (location) {
            is GeolocationPositionResult.Error -> {
                logger.i { "Couldn't get location: ${location.message}" }
                return
            }
            is GeolocationPositionResult.Success -> {
                val locale = Locale.current.toLanguageTag()
                val response = pebbleWebServices.getWeather(location, units, locale)
                if (response != null) {
                    if (pinsEnabled) {
                        createTimelinePins(response)
                    }
                    populateWeatherApp(response, units)
                }
            }
        }
    }

    private fun createTimelinePins(weather: WeatherResponse) {
        val today = weather.fcstdaily7.data.forecasts.getOrNull(0)
        val tomorrow = weather.fcstdaily7.data.forecasts.getOrNull(1)
        val dayAfterTomorrow = weather.fcstdaily7.data.forecasts.getOrNull(2)
        createTimelinePins(today, Day.Today)
        createTimelinePins(tomorrow, Day.Tomorrow)
        createTimelinePins(dayAfterTomorrow, Day.DayAfterTomorrow)
    }

    private fun populateWeatherApp(weather: WeatherResponse, units: WeatherUnit) {
        val current = weather.conditions.data.observation
        val currentTemps = current.tempsFor(units)
        if (currentTemps == null) {
            logger.w { "Couldn't find temps for units: $units" }
            return
        }
        val tomorrow = weather.fcstdaily7.data.forecasts.getOrNull(0)
        if (tomorrow == null || tomorrow.day == null) {
            logger.w { "Couldn't find forcast for tomorrow" }
            return
        }
        val currentLocation = WeatherLocationData(
            key = WeatherAppCurrentLocationUuid,
            currentTemp = currentTemps.temp.toShort(),
            currentWeatherType = current.iconCode.toWeatherType(),
            todayHighTemp = currentTemps.max24Hour.toShort(),
            todayLowTemp = currentTemps.min24Hour.toShort(),
            tomorrowWeatherType = tomorrow.day.iconCode.toWeatherType(),
            tomorrowHighTemp = tomorrow.maxTemp?.toShort() ?: TEMP_NO_VALUE,
            tomorrowLowTemp = tomorrow.minTemp.toShort(),
            lastUpdateTimeUtcSecs = clock.now().epochSeconds,
            isCurrentLocation = true,
            locationName = "Current Location",
            forecastShort = current.phrase32Char,
            locationNameStartCase = "Current Location",
        )
        libPebble.updateWeatherData(listOf(currentLocation))
    }

    private fun createTimelinePins(dailyForecast: DailyForecast?, day: Day) {
        if (dailyForecast == null) {
            libPebble.delete(day.dayUuid)
            libPebble.delete(day.nightUuid)
            return
        }
        if (dailyForecast.day != null) {
            createTimelinePin(
                title = "Sunrise",
                subtitle = "${dailyForecast.day.temp}°/${dailyForecast.night.temp}°",
                dayOrNight = dailyForecast.day,
                timestamp = dailyForecast.sunrise,
                uuid = day.dayUuid,
                location = "Current Location",
            )
        } else {
            libPebble.delete(day.dayUuid)
        }

        val dayTempString = dailyForecast.day?.temp ?: "-"
        createTimelinePin(
            title = "Sunset",
            subtitle = "$dayTempString/${dailyForecast.night.temp}°",
            dayOrNight = dailyForecast.night,
            timestamp = dailyForecast.sunset,
            uuid = day.nightUuid,
            location = "Current Location",
        )
    }

    private fun createTimelinePin(
        uuid: Uuid,
        title: String,
        subtitle: String,
        dayOrNight: DailyDayNight,
        timestamp: Instant,
        location: String,
    ) {
        val pin = buildTimelinePin(
            parentId = WEATHER_APP_UUID,
            timestamp = timestamp,
        ) {
            itemID = uuid
            duration = Duration.ZERO
            layout = TimelineItem.Layout.WeatherPin
            attributes {
                title { title }
                subtitle { subtitle }
                body { dayOrNight.narrative }
                tinyIcon { dayOrNight.iconCode.toWeatherType().toWeatherIcon() }
                largeIcon { dayOrNight.iconCode.toWeatherType().toWeatherIcon() }
                lastUpdated { clock.now() }
                location { location }
            }
            actions {
                action(TimelineItem.Action.Type.OpenWatchapp) {
                    attributes { title { "More" } }
                }
            }
        }
        libPebble.insertOrReplace(pin)
    }
}

enum class Day(
    val dayUuid: Uuid,
    val nightUuid: Uuid,
) {
    Today(TodayDayUuid, TodayNightUuid),
    Tomorrow(TomorrowDayUuid, TomorrowNightUuid),
    DayAfterTomorrow(DayAfterTomorrowDayUuid, DayAfterTomorrowNightUuid),
}

private val TodayDayUuid = Uuid.parse("b0beeaa7-a6cf-46c6-8bc4-2700ed3fa952")
private val TodayNightUuid = Uuid.parse("ef6abfda-5312-4649-a10a-427235059479")
private val TomorrowDayUuid = Uuid.parse("2834d0c8-85bc-45bd-b2b7-d0f026098d28")
private val TomorrowNightUuid = Uuid.parse("2f363ad4-bc5d-455a-a445-4e00ec07417e")
private val DayAfterTomorrowDayUuid = Uuid.parse("3233984e-2dc9-4469-8a9a-46f91d81b7fc")
private val DayAfterTomorrowNightUuid = Uuid.parse("fd97c081-9970-436b-aa87-9c55232bce35")
// This will become configured in db table, later, for multiple locations
private val WeatherAppCurrentLocationUuid = Uuid.parse("d9540f35-733d-4dcc-80d1-8cfe7c197067")
private const val TEMP_NO_VALUE = Short.MAX_VALUE

object Iso8601InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant {
        val string = decoder.decodeString()
        // The API returns a non-standard ISO-8601 date string.
        // The timezone offset is missing a colon.
        // Examples: 2025-12-01T13:37:16+0000 and 2025-12-01T14:17:58-0800
        // We need to insert the colon to make it parseable by Instant.
        // i.e. 2025-12-01T13:37:16+00:00
        val sanitized = string.substring(0, string.length - 2) + ":" + string.substring(string.length - 2)
        return Instant.parse(sanitized)
    }
}

fun Int.toWeatherType(): WeatherType = when (this) {
    in 0..4 -> WeatherType.HeavyRain
    in 5..8 -> WeatherType.LightSnow
    9 -> WeatherType.LightRain
    10 -> WeatherType.LightSnow
    11 -> WeatherType.LightRain
    12 -> WeatherType.HeavyRain
    in 13..14 -> WeatherType.LightSnow
    in 15..17 -> WeatherType.HeavySnow
    18 -> WeatherType.LightSnow
    in 19..22 -> WeatherType.CloudyDay
    in 23..25 -> WeatherType.Generic
    in 26..28 -> WeatherType.CloudyDay
    in 29..30 -> WeatherType.PartlyCloudy
    in 31..34 -> WeatherType.Sun
    35 -> WeatherType.LightSnow
    36 -> WeatherType.Sun
    in 37..40 -> WeatherType.HeavyRain
    in 41..43 -> WeatherType.HeavySnow
    44 -> WeatherType.Generic
    45 -> WeatherType.LightRain
    46 -> WeatherType.LightSnow
    47 -> WeatherType.HeavyRain
    else -> WeatherType.Generic
}

fun WeatherType.toWeatherIcon(): TimelineIcon = when (this) {
    WeatherType.PartlyCloudy -> TimelineIcon.PartlyCloudy
    WeatherType.CloudyDay -> TimelineIcon.CloudyDay
    WeatherType.LightSnow -> TimelineIcon.LightSnow
    WeatherType.LightRain -> TimelineIcon.LightRain
    WeatherType.HeavyRain -> TimelineIcon.HeavyRain
    WeatherType.HeavySnow -> TimelineIcon.HeavySnow
    WeatherType.Generic -> TimelineIcon.TimelineWeather
    WeatherType.Sun -> TimelineIcon.TimelineSun
    WeatherType.RainAndSnow -> TimelineIcon.RainingAndSnowing
    WeatherType.Unknown -> TimelineIcon.TimelineWeather
}

@Serializable
data class WeatherResponse(
    val fcstdaily7: DailyForecasts,
    val conditions: Conditions,
)

@Serializable
data class DailyForecasts(
    val data: DailyForecastData
)

@Serializable
data class Conditions(
    val data: ConditionsData,
)

@Serializable
data class ConditionsData(
    val observation: ConditionsObservation,
)

@Serializable
data class ConditionsObservation(
    val metric: ConditionTemps? = null,
    val imperial: ConditionTemps? = null,
    @SerialName("uk_hybrid")
    val ukHybrid: ConditionTemps? = null,
    @SerialName("phrase_32char")
    val phrase32Char: String,
    @SerialName("icon_code")
    val iconCode: Int,
)

fun ConditionsObservation.tempsFor(units: WeatherUnit): ConditionTemps? = when (units) {
    WeatherUnit.Metric -> metric
    WeatherUnit.Imperial -> imperial
    WeatherUnit.UkHybrid -> ukHybrid
}

@Serializable
data class ConditionTemps(
    @SerialName("feels_like")
    val feelsLike: Int,
    val temp: Int,
    @SerialName("temp_max_24hour")
    val max24Hour: Int,
    @SerialName("temp_min_24hour")
    val min24Hour: Int,
)

@Serializable
data class DailyForecastData(
    val forecasts: List<DailyForecast>
)

@Serializable
data class DailyForecast(
    @Serializable(with = Iso8601InstantSerializer::class)
    val sunrise: Instant,
    @Serializable(with = Iso8601InstantSerializer::class)
    val sunset: Instant,
    val day: DailyDayNight? = null,
    val night: DailyDayNight,
    @SerialName("max_temp")
    val maxTemp: Int?,
    @SerialName("min_temp")
    val minTemp: Int,
)

@Serializable
data class DailyDayNight(
    val narrative: String,
    val temp: Int,
    @SerialName("icon_code")
    val iconCode: Int,
)
