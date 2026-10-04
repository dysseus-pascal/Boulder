package coredevices.pebble.timestyle.bild

import io.rebble.libpebblecommon.connection.LatestHeartRate
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import io.rebble.libpebblecommon.database.entity.WeatherAppEntry
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
 * aus Boulders Naechten (Tag 88), Wetter von Ort 0.
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

    private val fenster = tag0310 - 5 * 3600 to tag0310 + 19 * 3600

    /** Eine Nacht ab [beginn] (UTC), Einschlafen und Aufwachen in Minuten danach. */
    private fun nacht(beginn: Long, einschlafen: Int, aufwachen: Int, schlaf: Int) =
        NachtEntity(beginn, 7200, einschlafen = einschlafen, aufwachen = aufwachen, schlafMinuten = schlaf, ruhepuls = 0, hrv = 0)

    @Test
    fun schlafKommtAusDerNachtOhneWachminuten() = runTest {
        val (von, bis) = fenster
        // Die Nacht ab gestern 20:00: eingeschlafen 21:30, aufgewacht 07:56 - 626 Minuten, davon
        // 37 wach. Die Uhr hatte nachgerechnet und alte Schlafbloecke stehen lassen: ihre Summe
        // war 626. Es gilt die Nacht: 589.
        val b = von - 3600
        val heute = nacht(b, einschlafen = 90, aufwachen = 716, schlaf = 589)
        val phasen = listOf(
            NachtAbschnittEntity(b, 80, 100, 2), // beginnt vor dem Einschlafen: zaehlt ab 90, also 10
            NachtAbschnittEntity(b, 100, 200, 1),
            NachtAbschnittEntity(b, 200, 260, 2),
            NachtAbschnittEntity(b, 260, 300, 0),
            NachtAbschnittEntity(b, 300, 330, 2),
            NachtAbschnittEntity(b, 330, 716, 3),
        )
        // Endet genau gestern 21:00: gehoert zum Vortag. Endet nach heute 21:00: morgen.
        val vortag = nacht(von - 6 * 3600, einschlafen = 60, aufwachen = 6 * 60, schlaf = 290)
        val morgen = nacht(bis - 3600, einschlafen = 30, aufwachen = 61, schlaf = 31)
        val quelle = Nachbau(naechte = listOf(vortag, heute, morgen), phasen = phasen)
        val d = tsDatenLesen(quelle, jetzt, zuerich, verbunden = true)
        assertEquals(589 * 60, d.schlafSekunden)
        assertEquals((10 + 60 + 30) * 60, d.ruhigerSchlafSekunden)
    }

    @Test
    fun eineNachtDieGenauAnDerGrenzeEndetZaehltNochHeute() {
        val (von, bis) = fenster
        val grenze = nacht(bis - 600 * 60, einschlafen = 0, aufwachen = 600, schlaf = 500)
        val vortag = nacht(von - 600 * 60, einschlafen = 0, aufwachen = 600, schlaf = 480)
        assertEquals(500 * 60 to 0, tsSchlafHeute(listOf(grenze, vortag), emptyList(), fenster))
    }

    private class Nachbau(
        val satz: WeatherAppEntry? = null,
        val schritte: Pair<Long, Long>? = null,
        val puls: LatestHeartRate? = null,
        val naechte: List<NachtEntity> = emptyList(),
        val phasen: List<NachtAbschnittEntity> = emptyList(),
    ) : TsDatenQuelle {
        val fragen = mutableListOf<String>()
        override suspend fun wetter() = satz
        override suspend fun schritteUndDistanzCm(von: Long, bis: Long): Pair<Long, Long>? {
            fragen += "schritte $von $bis"
            return schritte
        }
        override suspend fun letzterPuls() = puls
        // Wie die Datenbank: nur Naechte mit Beginn in [von, bis).
        override suspend fun naechte(von: Long, bis: Long): List<NachtEntity> {
            fragen += "naechte $von $bis"
            return naechte.filter { it.beginnUtc in von until bis }
        }
        override suspend fun nachtAbschnitte(von: Long, bis: Long): List<NachtAbschnittEntity> {
            fragen += "phasen $von $bis"
            return phasen
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
        // Schritte von Mitternacht bis jetzt einschliesslich; Naechte ab einem Tag vor dem Fenster, ohne Nacht keine Phasen.
        assertEquals(
            listOf("schritte $mitternacht ${jetzt.epochSeconds + 1}", "naechte ${tag0310 - 29 * 3600} ${tag0310 + 19 * 3600}"),
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
