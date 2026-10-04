package coredevices.pebble.timestyle.bild

import io.rebble.libpebblecommon.connection.LatestHeartRate
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import io.rebble.libpebblecommon.database.entity.WeatherAppEntry
import io.rebble.libpebblecommon.health.OverlayType
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Was die Vorschau aus Boulders Daten macht - nach den Regeln der Firmware:
 * Schritte und Distanz seit Mitternacht (health_service_sum_today), Puls nur
 * bis 15 Minuten alt (health_service.c), Schlaf des Schlaftages bis 21 Uhr
 * (activity_sessions.c), Wetter von Ort 0.
 */
class TsDatenTest {
    private val zuerich = TimeZone.of("Europe/Zurich")

    // Sa 3.10.2026 15:45:07 in Zuerich = 13:45:07 UTC; Mitternacht = 2.10. 22:00 UTC.
    private val tag0310 = 1790985600L
    private val jetzt = Instant.fromEpochSeconds(tag0310 + 13 * 3600 + 45 * 60 + 7)
    private val mitternacht = tag0310 - 2 * 3600

    @Test
    fun pulsHoechstens15MinutenAlt() {
        val t = jetzt.epochSeconds
        assertEquals(0, tsPulsJetzt(null, t))
        assertEquals(71, tsPulsJetzt(LatestHeartRate(71, t), t))
        assertEquals(71, tsPulsJetzt(LatestHeartRate(71, t - 15 * 60 + 1), t))
        assertEquals(0, tsPulsJetzt(LatestHeartRate(71, t - 15 * 60), t))
        assertEquals(900, TS_PULS_HOECHSTALTER_S)
    }

    @Test
    fun schlaftagVonGestern21BisHeute21() {
        // 21 Uhr in Zuerich (Sommerzeit) = 19:00 UTC.
        val (von, bis) = tsSchlafFenster(jetzt, zuerich)
        assertEquals(tag0310 - 5 * 3600, von) // 2.10. 21:00 = 2.10. 19:00 UTC
        assertEquals(tag0310 + 19 * 3600, bis)
        assertEquals(21 * 60, TS_SCHLAF_TAGESGRENZE_MIN)
        // Nach 21 Uhr bleibt es derselbe Schlaftag (bis Mitternacht).
        val spaet = Instant.fromEpochSeconds(tag0310 + 20 * 3600) // 22:00 in Zuerich
        assertEquals(von to bis, tsSchlafFenster(spaet, zuerich))
    }

    private fun abschnitt(start: Long, dauer: Long, typ: OverlayType) = OverlayDataEntity(
        startTime = start, duration = dauer, type = typ.value, steps = 0, restingKiloCalories = 0,
        activeKiloCalories = 0, distanceCm = 0, offsetUTC = 7200,
    )

    @Test
    fun schlafZaehltWasImSchlaftagEndet() {
        val fenster = tag0310 - 5 * 3600 to tag0310 + 19 * 3600
        val (von, bis) = fenster
        val nacht = listOf(
            // Die Nacht: 22:30-06:10 Schlaf (Ende im Fenster), darin 2 x Tiefschlaf.
            abschnitt(von + 5400, 7 * 3600 + 40 * 60, OverlayType.Sleep),
            abschnitt(von + 9000, 3600, OverlayType.DeepSleep),
            abschnitt(von + 18000, 1800 + 59, OverlayType.DeepSleep), // 30 Minuten und 59 s -> 30
            // Ein Nickerchen zaehlt mit, ein tiefes Nickerchen zum ruhigen Schlaf.
            abschnitt(bis - 3 * 3600, 1200, OverlayType.Nap),
            abschnitt(bis - 3 * 3600 + 300, 600, OverlayType.DeepNap),
            // Endet genau an der Grenze heute 21:00: zaehlt noch.
            abschnitt(bis - 600, 600, OverlayType.Nap),
            // Endet genau gestern 21:00: gehoert zum Vortag.
            abschnitt(von - 3600, 3600, OverlayType.Sleep),
            // Endet nach heute 21:00: morgen.
            abschnitt(bis - 600, 601, OverlayType.Sleep),
            // Gehen ist kein Schlaf.
            abschnitt(von + 40000, 1800, OverlayType.Walk),
        )
        val (gesamt, ruhig) = tsSchlafHeute(nacht, fenster)
        assertEquals((7 * 60 + 40 + 20 + 10) * 60, gesamt)
        assertEquals((60 + 30 + 10) * 60, ruhig)
    }

    private class Nachbau(
        val satz: WeatherAppEntry? = null,
        val schritte: Pair<Long, Long>? = null,
        val puls: LatestHeartRate? = null,
        val abschnitte: List<OverlayDataEntity> = emptyList(),
    ) : TsDatenQuelle {
        val fragen = mutableListOf<String>()
        override suspend fun wetter() = satz
        override suspend fun schritteUndDistanzCm(von: Long, bis: Long): Pair<Long, Long>? {
            fragen += "schritte $von $bis"
            return schritte
        }
        override suspend fun letzterPuls() = puls
        override suspend fun schlaf(von: Long, bis: Long): List<OverlayDataEntity> {
            fragen += "schlaf $von $bis"
            return abschnitte
        }
    }

    @Test
    fun liestHeuteSeitMitternacht() = runTest {
        val quelle = Nachbau(schritte = 5523L to 423_099L, puls = LatestHeartRate(64, jetzt.epochSeconds - 60))
        val d = tsDatenLesen(quelle, jetzt, zuerich, verbunden = true)
        assertEquals(5523, d.schritte)
        // Meter wie die Uhr (ganze Meter): 423'099 cm -> 4230 m.
        assertEquals(4230, d.distanzMeter)
        assertEquals(64, d.puls)
        assertEquals(true, d.verbunden)
        // Schritte von Mitternacht bis jetzt einschliesslich; Schlaf ab einem Tag vor dem Fenster.
        assertEquals(
            listOf("schritte $mitternacht ${jetzt.epochSeconds + 1}", "schlaf ${tag0310 - 29 * 3600} ${tag0310 + 19 * 3600}"),
            quelle.fragen,
        )
    }

    @Test
    fun ohneDatenNullenWieDieUhr() = runTest {
        val d = tsDatenLesen(Nachbau(), jetzt, zuerich, verbunden = false)
        assertEquals(TsDaten(null, 0, 0, 0, 0, 0, verbunden = false), d)
    }

    @Test
    fun wetterMitDerZoneDerUhr() = runTest {
        // Der Satz kennt die Zone des Ortes nicht: es gilt die der Uhr (Zuerich, +2 h).
        val satz = WeatherAppEntry(
            key = Uuid.parse("10111213-1415-1617-1819-1a1b1c1d1e1f"), currentTemp = 18, currentWeatherType = 1,
            todayHighTemp = 21, todayLowTemp = 9, tomorrowWeatherType = 1, tomorrowHighTemp = 20, tomorrowLowTemp = 10,
            lastUpdateTimeUtcSecs = jetzt.epochSeconds - 600, isCurrentLocation = true, locationName = "Zurich",
            forecastShort = "", todayUvIndexX10 = 30,
            todayHourly = List(24) { io.rebble.libpebblecommon.weather.WeatherHourlyForecast(
                io.rebble.libpebblecommon.weather.WeatherType.CloudyDay, 18, (it * 3 + 1).toShort()) },
        )
        val w = tsDatenLesen(Nachbau(satz = satz), jetzt, zuerich, verbunden = true).wetter!!
        assertEquals(18, w.temperatur)
        // 15:45 Uhr der Uhr -> Stunde 16 -> 49 -> 5.
        assertEquals(5, w.uv)
        assertNull(tsDatenLesen(Nachbau(), jetzt, zuerich, true).wetter)
        // Firmware b23 (uvJeStunde = false): der Tageswert 30 -> 3.
        assertEquals(3, tsDatenLesen(Nachbau(satz = satz), jetzt, zuerich, true, uvJeStunde = false).wetter!!.uv)
    }
}
