package io.rebble.libpebblecommon.weather

import io.rebble.libpebblecommon.database.entity.WeatherAppEntry
import io.rebble.libpebblecommon.database.entity.WeatherPrefsValue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * Boulder: welcher Wettersatz der des Standardorts der Uhr ist (fuer die
 * Vorschau von TimeStyle Bedrock). Die Firmware nimmt NUR Ort 0 der
 * Reihenfolge (weather_service.c) - die Vorschau muss denselben nehmen.
 */
class WetterDerUhrTest {
    private val ort0 = Uuid.parse("10111213-1415-1617-1819-1a1b1c1d1e1f")
    private val ort1 = Uuid.parse("20212223-2425-2627-2829-2a2b2c2d2e2f")

    private fun satz(key: Uuid, temp: Short) = WeatherAppEntry(
        key = key, currentTemp = temp, currentWeatherType = 1, todayHighTemp = 24, todayLowTemp = 12,
        tomorrowWeatherType = 1, tomorrowHighTemp = 20, tomorrowLowTemp = 10, lastUpdateTimeUtcSecs = 1790985600,
        isCurrentLocation = false, locationName = "Ort", forecastShort = "",
    )

    private val saetze = mapOf(ort0 to satz(ort0, 22), ort1 to satz(ort1, 7))
    private val gelesen = mutableListOf<Uuid>()
    private val lies: suspend (Uuid) -> WeatherAppEntry? = { gelesen += it; saetze[it] }

    @Test
    fun ortNullDerReihenfolge() = runTest {
        assertEquals(22, wetterOrtNull(WeatherPrefsValue(listOf(ort0, ort1)), lies)?.currentTemp?.toInt())
        // Umgestellte Reihenfolge: jetzt ist ort1 der Standardort.
        assertEquals(7, wetterOrtNull(WeatherPrefsValue(listOf(ort1, ort0)), lies)?.currentTemp?.toInt())
        assertEquals(listOf(ort0, ort1), gelesen)
    }

    @Test
    fun ohneReihenfolgeKeinWetter() = runTest {
        assertNull(wetterOrtNull(null, lies))
        assertNull(wetterOrtNull(WeatherPrefsValue(emptyList()), lies))
        assertEquals(emptyList(), gelesen)
    }

    @Test
    fun fehltDerSatzVonOrtNullKeinAusweichen() = runTest {
        // Wie die Firmware: ohne Satz fuer Ort 0 gibt es kein Standardwetter,
        // auch wenn Ort 1 einen hat.
        val unbekannt = Uuid.parse("30313233-3435-3637-3839-3a3b3c3d3e3f")
        assertNull(wetterOrtNull(WeatherPrefsValue(listOf(unbekannt, ort1)), lies))
        assertEquals(listOf(unbekannt), gelesen)
    }
}
