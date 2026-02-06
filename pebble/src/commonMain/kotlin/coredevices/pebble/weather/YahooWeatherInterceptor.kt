package coredevices.pebble.weather

import androidx.compose.ui.text.intl.Locale
import co.touchlab.kermit.Logger
import com.eygraber.uri.Uri
import coredevices.pebble.services.RealPebbleWebServices
import coredevices.util.CoreConfigHolder
import coredevices.util.WeatherUnit
import dev.jordond.compass.geocoder.Geocoder
import io.rebble.libpebblecommon.js.HttpInterceptor
import io.rebble.libpebblecommon.js.InterceptResponse
import io.rebble.libpebblecommon.weather.WeatherType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

class YahooWeatherInterceptor(
    private val pebbleWebServices: RealPebbleWebServices,
    private val geocoder: Geocoder,
    private val coreConfigHolder: CoreConfigHolder,
) : HttpInterceptor {
    private val logger = Logger.withTag("YahooWeatherInterceptor")

    override fun shouldIntercept(url: String): Boolean {
        if (!coreConfigHolder.config.value.interceptPKJSWeather) {
            return false
        }
        return url.asYahooWeatherRequest() != null
    }

    override suspend fun onIntercepted(
        url: String,
        method: String,
        body: String?,
        appUuid: Uuid,
    ): InterceptResponse {
        if (!coreConfigHolder.config.value.interceptPKJSWeather) {
            return InterceptResponse.ERROR
        }
        val request = url.asYahooWeatherRequest()
        if (request == null) {
            logger.w { "Unknown request: $url" }
            return InterceptResponse.ERROR
        }

        logger.d { "Intercepting weather request - calling Pebble Service" }
        return callPebbleService(request)
    }

    private val placeNameRegex = Regex("text=\"(.+?)\"")
    private val unitsRegex = Regex("u=\"([fc])\"")

    // https://web.archive.org/web/20160206195745/https://developer.yahoo.com/weather/documentation.html
    private fun String.asYahooWeatherRequest(): YahooWeatherRequest? {
        val uri = Uri.parseOrNull(this.lowercase())
        if (uri?.authority == null) {
            return null
        }
        if (uri.authority != "query.yahooapis.com") {
            return null
        }
        if (uri.path != "/v1/public/yql") {
            return null
        }
        val q = uri.getQueryParameter("q")
        if (q == null) {
            return null
        }
        /**
        select * from weather.forecast where woeid in (select woeid from geo.places(1) where text="mission district, san francisco, california, 90110, united states") and u="f" limit 1
         */
        val placeName = placeNameRegex.find(q)?.groupValues?.getOrNull(1)
        val units = unitsRegex.find(q)?.groupValues?.getOrNull(1)
        if (placeName == null || units == null) {
            return null
        }
        return YahooWeatherRequest(placeName = placeName, units = units)
    }

    private suspend fun callPebbleService(request: YahooWeatherRequest): InterceptResponse {
        val place = geocoder.locations(request.placeName).getOrNull()?.firstOrNull()
        if (place == null) {
            logger.d { "No place found" }
            return InterceptResponse.ERROR
        }
        val units = request.units.asUnits()
        val weather = pebbleWebServices.getWeather(
            latitude = place.latitude,
            longitude = place.longitude,
            units = units,
            language = Locale.current.toLanguageTag(),
        )
        if (weather == null) {
            logger.d { "No response from Pebble service" }
            return InterceptResponse.ERROR
        }
        val location = geocoder.reverse(place.latitude, place.longitude).getOrNull()?.firstOrNull()
        val placeName = location?.usefulName() ?: "Unknown location"
        val country = location?.isoCountryCode ?: ""
        val temps = weather.conditions.data.observation.tempsFor(units)
        val firstDay = weather.fcstdaily7.data.forecasts.firstOrNull()
        val weatherType = weather.conditions.data.observation.iconCode.toWeatherType()
        val yahooWeatherCode = weatherType.asYahooWeatherCode()
        if (temps == null || firstDay == null) {
            logger.d { "No temps/firstDay from Pebble service" }
            return InterceptResponse.ERROR
        }
        val response = YahooWeatherResponse(
            query = YahooWeatherResponse.Query(
                results = YahooWeatherResponse.Query.Results(
                    channel = YahooWeatherResponse.Query.Results.Channel(
                        item = YahooWeatherResponse.Query.Results.Channel.Item(
                            condition = YahooWeatherResponse.Query.Results.Channel.Item.Condition(
                                temp = temps.temp,
                                code = yahooWeatherCode,
                                text = weather.conditions.data.observation.phrase12Char,
                                date = firstDay.sunriseRaw.asYahooDate(),
                            ),
                            forecast = weather.fcstdaily7.data.forecasts.take(5).map { dayFc ->
                                val code = dayFc.day?.iconCode ?: dayFc.night.iconCode
                                YahooWeatherResponse.Query.Results.Channel.Item.Forecast(
                                    day = dayFc.dow,
                                    date = dayFc.sunriseRaw.asYahooDate(),
                                    low = dayFc.minTemp,
                                    high = dayFc.maxTemp ?: -1,
                                    text = dayFc.day?.phrase12Char ?: dayFc.night.phrase12Char,
                                    code = code.toWeatherType().asYahooWeatherCode(),
                                )
                            }
                        ),
                        wind = YahooWeatherResponse.Query.Results.Channel.Wind(
                            chill = 0, // TODO
                            direction = 0, // TODO
                            speed = 0, // TODO
                        ),
                        atmosphere = YahooWeatherResponse.Query.Results.Channel.Atmosphere(
                            humidity = 0, // TODO
                            visbility = 0, // TODO
                            pressure = 0, // TODO
                            rising = 0, // TODO
                        ),
                        location = YahooWeatherResponse.Query.Results.Channel.Location(
                            city = placeName,
                            region = location?.administrativeArea ?: location?.subAdministrativeArea ?: location?.country ?: "",
                            country = country,
                        ),
                        units = YahooWeatherResponse.Query.Results.Channel.Units(
                            temperature = request.units.first(),
                            distance = units.asYahooDistanceString(),
                            pressure = "mb", // TODO
                            speed = units.asYahooSpeedString(),
                        ),
                        astronomy = YahooWeatherResponse.Query.Results.Channel.Astronomy(
                            sunrise = firstDay.sunriseRaw.asYahooTime(),
                            sunset = firstDay.sunsetRaw.asYahooTime(),
                        ),
                    ),
                ),
            ),
        )
        logger.d { "Success from Pebble service" }
        val result = Json.encodeToString(YahooWeatherResponse.serializer(), response)
        return InterceptResponse(
            result = result,
            status = 200,
        )
    }
}

private fun WeatherUnit.asYahooDistanceString(): String = when (this) {
    WeatherUnit.Metric -> "km"
    WeatherUnit.Imperial -> "mi"
    WeatherUnit.UkHybrid -> "km"
}

private fun WeatherUnit.asYahooSpeedString(): String = when (this) {
    WeatherUnit.Metric -> "kph"
    WeatherUnit.Imperial -> "mph"
    WeatherUnit.UkHybrid -> "kph"
}

// in: 2026-02-06T07:08:00-0800
// out: yyyy/mm/dd
private fun String.asYahooDate(): String {
    return take(10).replace("-", "/")
}

// in: 2026-02-06T17:38:00-0800
// h:mm am/pm (e.g. "4:51 pm")
private val yahooTimeRegex = Regex("T(\\d+):(\\d+):")

private fun String.asYahooTime(): String {
    val match = yahooTimeRegex.find(this)
    val hour = match?.groupValues?.getOrNull(1)?.toInt()
    val minute = match?.groupValues?.getOrNull(2)?.toInt()
    if (hour == null || minute == null) {
        return this
    }
    val hour12 = hour % 12
    val amPm = if (hour < 12) "am" else "pm"
    return "${hour12.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')} $amPm"
}

/**
0	tornado
1	tropical storm
2	hurricane
3	severe thunderstorms
4	thunderstorms
5	mixed rain and snow
6	mixed rain and sleet
7	mixed snow and sleet
8	freezing drizzle
9	drizzle
10	freezing rain
11	showers
12	showers
13	snow flurries
14	light snow showers
15	blowing snow
16	snow
17	hail
18	sleet
19	dust
20	foggy
21	haze
22	smoky
23	blustery
24	windy
25	cold
26	cloudy
27	mostly cloudy (night)
28	mostly cloudy (day)
29	partly cloudy (night)
30	partly cloudy (day)
31	clear (night)
32	sunny
33	fair (night)
34	fair (day)
35	mixed rain and hail
36	hot
37	isolated thunderstorms
38	scattered thunderstorms
39	scattered thunderstorms
40	scattered showers
41	heavy snow
42	scattered snow showers
43	heavy snow
44	partly cloudy
45	thundershowers
46	snow showers
47	isolated thundershowers
3200	not available
 */
private fun WeatherType.asYahooWeatherCode(): Int = when (this) {
    WeatherType.PartlyCloudy -> 30
    WeatherType.CloudyDay -> 28
    WeatherType.LightSnow -> 14
    WeatherType.LightRain -> 40
    WeatherType.HeavyRain -> 45
    WeatherType.HeavySnow -> 43
    WeatherType.Generic -> 33
    WeatherType.Sun -> 32
    WeatherType.RainAndSnow -> 5
    WeatherType.Unknown -> 33
}

@Serializable
private data class YahooWeatherResponse(
    val query: Query,
) {
    @Serializable
    data class Query(
        val results: Results,
    ) {
        @Serializable
        data class Results(
            val channel: Channel,
        ) {
            @Serializable
            data class Channel(
                val item: Item,
                val wind: Wind,
                val atmosphere: Atmosphere,
                val location: Location,
                val units: Units,
                val astronomy: Astronomy,
            ) {
                @Serializable
                data class Item(
                    val condition: Condition,
                    val forecast: List<Forecast>,
                ) {
                    @Serializable
                    data class Condition(
                        val temp: Int,
                        val code: Int,
                        val text: String,
                        val date: String,
                    )

                    @Serializable
                    data class Forecast(
                        val day: String,
                        val date: String,
                        val low: Int,
                        val high: Int,
                        val text: String,
                        val code: Int,
                    )
                }

                @Serializable
                data class Wind(
                    val chill: Int,
                    val direction: Int,
                    val speed: Int,
                )

                @Serializable
                data class Atmosphere(
                    val humidity: Int,
                    val visbility: Int,
                    val pressure: Int,
                    val rising: Int,
                )

                @Serializable
                data class Location(
                    val city: String,
                    val region: String,
                    val country: String,
                )

                @Serializable
                data class Units(
                    val temperature: Char,
                    val distance: String,
                    val pressure: String,
                    val speed: String,
                )

                @Serializable
                data class Astronomy(
                    val sunrise: String,
                    val sunset: String,
                )
            }
        }
    }
}

private fun String?.asUnits(): WeatherUnit = when (this) {
    "f" -> WeatherUnit.Imperial
    else -> WeatherUnit.Metric
}

private data class YahooWeatherRequest(
    val placeName: String,
    val units: String,
)
