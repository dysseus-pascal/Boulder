package coredevices.pebble.timestyle.bild

import io.rebble.libpebblecommon.database.entity.WeatherAppEntry
import io.rebble.libpebblecommon.weather.WeatherDailyForecast
import io.rebble.libpebblecommon.weather.WeatherHourlyForecast
import io.rebble.libpebblecommon.weather.WeatherType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Das Wetter der Vorschau = das Wetter der Uhr. Die Zahlen sind DIESELBEN wie
 * in den Firmware-Tests (test_timestyle.c: wechsel_auf_die_minute, uv_*,
 * wetter_hoechstens_drei_stunden_alt) - woertlich, nicht nachgerechnet.
 */
class TsWetterJetztTest {
    // --- Wetterart -> Symbol (wetter_symbol.c) ---

    @Test
    fun jedeWetterartHatIhrSymbol() {
        // WeatherType der Firmware: 0 teils bewoelkt, 1 bewoelkt, 2 leichter Schnee,
        // 3 leichter Regen, 4 starker Regen, 5 starker Schnee, 6 allgemein, 7 Sonne,
        // 8 Regen und Schnee, 255 unbekannt.
        val tag = mapOf(
            0 to TsWetterSymbol.TEILS_BEWOELKT, 1 to TsWetterSymbol.BEWOELKT, 2 to TsWetterSymbol.SCHNEE,
            3 to TsWetterSymbol.REGEN, 4 to TsWetterSymbol.STARKREGEN, 5 to TsWetterSymbol.STARKSCHNEE,
            6 to TsWetterSymbol.ALLGEMEIN, 7 to TsWetterSymbol.KLAR_TAG, 8 to TsWetterSymbol.REGEN_UND_SCHNEE,
            255 to TsWetterSymbol.ALLGEMEIN, 9 to TsWetterSymbol.ALLGEMEIN,
        )
        tag.forEach { (art, symbol) -> assertEquals(symbol, tsWetterSymbol(art, false), "Art $art am Tag") }
        // Nachts nur Sonne und "teils bewoelkt" anders.
        assertEquals(TsWetterSymbol.KLAR_NACHT, tsWetterSymbol(7, true))
        assertEquals(TsWetterSymbol.TEILS_BEWOELKT_NACHT, tsWetterSymbol(0, true))
        assertEquals(TsWetterSymbol.BEWOELKT, tsWetterSymbol(1, true))
        assertEquals(TsWetterSymbol.REGEN, tsWetterSymbol(3, true))
        // Boulders WeatherType hat dieselben Nummern.
        assertEquals(7, WeatherType.Sun.code.toInt())
        assertEquals(0, WeatherType.PartlyCloudy.code.toInt())
        assertEquals(8, WeatherType.RainAndSnow.code.toInt())
    }

    // --- Tag oder Nacht, auf die Minute wie die Firmware ---

    private fun pruefeGenau(breite: Int, laenge: Int, tag: Int, tagAb: Int, nachtAb: Int, nachtMinuten: Int) {
        assertEquals(nachtMinuten, (0 until 1440).count { tsIstNacht(breite, laenge, tag, it) }, "$breite/$laenge Tag $tag")
        if (tagAb >= 0) {
            assertTrue(tsIstNacht(breite, laenge, tag, (tagAb + 1439) % 1440))
            assertFalse(tsIstNacht(breite, laenge, tag, tagAb))
        }
        if (nachtAb >= 0) {
            assertFalse(tsIstNacht(breite, laenge, tag, (nachtAb + 1439) % 1440))
            assertTrue(tsIstNacht(breite, laenge, tag, nachtAb))
        }
    }

    @Test
    fun wechselAufDieMinuteWieDieFirmware() {
        pruefeGenau(4737, 854, 276, 331, 1018, 753) // Zuerich, 3. Oktober
        pruefeGenau(4737, 854, 172, 209, 1166, 483) // Zuerich, 21. Juni
        pruefeGenau(4737, 854, 355, 431, 939, 932) // Zuerich, 21. Dezember
        pruefeGenau(4071, -7401, 69, 680, 1375, 745) // New York, 10. Maerz
        pruefeGenau(-3387, 15121, 1, 1129, 550, 579) // Sydney, 1. Januar
        pruefeGenau(-22, -7851, 100, 673, 1399, 714) // Quito
        pruefeGenau(3568, 13976, 200, 1179, 595, 584) // Tokio
        pruefeGenau(6965, 1896, 355, -1, -1, 1440) // Tromsoe, Polarnacht
        pruefeGenau(6965, 1896, 172, -1, -1, 0) // Tromsoe, Mitternachtssonne
    }

    @Test
    fun fingerabdruckWieDieFirmware() {
        // Dasselbe Gitter wie test_timestyle.c (nacht_fingerabdruck): jede Minute
        // fuer Breite -80..80, Laenge -170..170, jeden 25. Tag. Eine Minute
        // Unterschied irgendwo aendert die Pruefsumme.
        var h = 0L
        var nacht = 0
        for (b in -8000..8000 step 1000) for (l in -17000..17000 step 8500) for (tag in 1..366 step 25) {
            for (m in 0 until 1440) {
                val n = tsIstNacht(b, l, tag, m)
                if (n) nacht++
                h = (h * 31 + (if (n) 1 else 0) + m) and 0xffffffffL
            }
        }
        assertEquals(903070, nacht)
        assertEquals(3572735266L, h)
    }

    @Test
    fun ohneOrtImmerTag() {
        for (m in 0 until 1440 step 60) {
            assertFalse(tsIstNacht(-32768, -32768, 276, m))
            assertFalse(tsIstNacht(4737, -32768, 276, m))
        }
    }

    // --- Alter (wetter_frisch) ---

    @Test
    fun hoechstensDreiStundenAltUndEineInDerZukunft() {
        val jetzt = 1790985600L + 12 * 3600
        assertFalse(tsWetterFrisch(0, jetzt))
        assertTrue(tsWetterFrisch(jetzt, jetzt))
        assertTrue(tsWetterFrisch(jetzt - 3 * 3600, jetzt))
        assertFalse(tsWetterFrisch(jetzt - 3 * 3600 - 1, jetzt))
        assertTrue(tsWetterFrisch(jetzt + 3600, jetzt))
        assertFalse(tsWetterFrisch(jetzt + 3600 + 1, jetzt))
        assertEquals(10800, TS_WETTER_HOECHSTALTER_S)
    }

    // --- UV der Stunde (wetter_uv_jetzt) ---

    private val tag0310 = 1790985600L
    private fun stunden() = IntArray(24) { it * 10 } // Stunde h hat UV h,0

    @Test
    fun uvDieNaechsteStunde() {
        val stand = tag0310 + 6 * 3600
        assertEquals(12, tsUvJetzt(stunden(), 70, 0, 0, stand, tag0310 + 12 * 3600 + 29 * 60 + 59))
        assertEquals(13, tsUvJetzt(stunden(), 70, 0, 0, stand, tag0310 + 12 * 3600 + 30 * 60))
        assertEquals(23, tsUvJetzt(stunden(), 70, 0, 0, stand, tag0310 + 23 * 3600 + 45 * 60))
        assertEquals(0, tsUvJetzt(stunden(), 70, 0, 0, stand, tag0310))
    }

    @Test
    fun uvInDerZeitDesWetterorts() {
        val stand = tag0310 + 6 * 3600
        val jetzt = tag0310 + 10 * 3600
        assertEquals(12, tsUvJetzt(stunden(), 70, 120, 0, stand, jetzt))
        assertEquals(16, tsUvJetzt(stunden(), 70, -32768, 19800, stand, jetzt))
        assertEquals(12, tsUvJetzt(stunden(), 70, 120, 19800, stand, jetzt))
    }

    @Test
    fun uvGerundetUndUnbekannt() {
        val s = stunden()
        s[12] = 24
        assertEquals(2, tsUvJetzt(s, 70, 0, 0, tag0310, tag0310 + 12 * 3600))
        s[12] = 25
        assertEquals(3, tsUvJetzt(s, 70, 0, 0, tag0310, tag0310 + 12 * 3600))
        s[12] = 255
        assertEquals(7, tsUvJetzt(s, 70, 0, 0, tag0310, tag0310 + 12 * 3600))
        assertEquals(7, tsUvJetzt(null, 79, 0, 0, tag0310, tag0310 + 12 * 3600))
        assertEquals(-1, tsUvJetzt(null, -1, 0, 0, tag0310, tag0310 + 12 * 3600))
    }

    @Test
    fun uvAmNaechstenTagUnbekannt() {
        assertEquals(-1, tsUvJetzt(stunden(), 70, 0, 0, tag0310 - 2 * 3600, tag0310 + 9 * 3600))
        assertEquals(23, tsUvJetzt(stunden(), 70, -300, 0, tag0310 - 3 * 3600, tag0310 + 4 * 3600))
    }

    // --- Der ganze Satz (tsWetterAusSatz = wetter_quelle.c + Weather_refresh) ---

    // Sa 3.10.2026 13:45 UTC (15:45 in Zuerich), Daten von 13:00.
    private val jetzt = Instant.fromEpochSeconds(tag0310 + 13 * 3600 + 45 * 60)

    private fun satz(
        temp: Int = 22,
        art: WeatherType = WeatherType.Sun,
        hoch: Int = 24,
        tief: Int = 12,
        stand: Long = tag0310 + 13 * 3600,
        breite: Double? = 47.37,
        laenge: Double? = 8.54,
        tagesart: WeatherType? = WeatherType.HeavyRain,
        uvTag: Short? = 47,
        uvStunden: List<Short?>? = List(24) { (it * 3 + 1).toShort() },
        versatz: Int? = 120,
        name: String = "Zurich",
    ) = WeatherAppEntry(
        key = Uuid.parse("10111213-1415-1617-1819-1a1b1c1d1e1f"),
        currentTemp = temp.toShort(), currentWeatherType = art.code,
        todayHighTemp = hoch.toShort(), todayLowTemp = tief.toShort(),
        tomorrowWeatherType = 1, tomorrowHighTemp = 20, tomorrowLowTemp = 10,
        lastUpdateTimeUtcSecs = stand, isCurrentLocation = true, locationName = name, forecastShort = "Sunny",
        latitude = breite, longitude = laenge,
        dailyForecast = tagesart?.let { listOf(WeatherDailyForecast(24, 12, it), WeatherDailyForecast(20, 10, WeatherType.LightSnow)) },
        todayUvIndexX10 = uvTag,
        todayHourly = uvStunden?.map { WeatherHourlyForecast(WeatherType.Sun, 20, it) },
        locationUtcOffsetMin = versatz,
    )

    @Test
    fun allesAusDemSatz() {
        val w = tsWetterAusSatz(satz(), jetzt, uhrVersatzS = 7200)!!
        assertEquals(22, w.temperatur)
        assertEquals(24, w.hoch)
        assertEquals(12, w.tief)
        // 15:45 am Ort: Stunde 16 -> 49 -> UV 5.
        assertEquals(5, w.uv)
        assertEquals(TsWetterSymbol.KLAR_TAG, w.symbol)
        // Das Tagessymbol aus daily[0], immer als Tag.
        assertEquals(TsWetterSymbol.STARKREGEN, w.symbolHeute)
    }

    @Test
    fun dieZahlenWieBoulderSieSchickt() {
        // Boulder schickt in seiner Einheit (hier Fahrenheit) - die Uhr rechnet nicht um.
        val w = tsWetterAusSatz(satz(temp = 72, hoch = 75, tief = 54), jetzt, 7200)!!
        assertEquals(72, w.temperatur)
        assertEquals(75, w.hoch)
        assertEquals(54, w.tief)
    }

    @Test
    fun nachtsDasMondsymbol() {
        // 21:00 UTC in Zuerich (Untergang 16:58 UTC): Mond; das Tagessymbol bleibt Tag.
        val abends = Instant.fromEpochSeconds(tag0310 + 21 * 3600)
        val w = tsWetterAusSatz(satz(stand = tag0310 + 20 * 3600, tagesart = WeatherType.Sun), abends, 7200)!!
        assertEquals(TsWetterSymbol.KLAR_NACHT, w.symbol)
        assertEquals(TsWetterSymbol.KLAR_TAG, w.symbolHeute)
        // Genau an der Grenze der Firmware (Minute 1018 = 16:58 UTC Nacht, 16:57 Tag).
        val s = satz(stand = tag0310 + 16 * 3600)
        assertEquals(TsWetterSymbol.KLAR_TAG, tsWetterAusSatz(s, Instant.fromEpochSeconds(tag0310 + 1017 * 60), 7200)!!.symbol)
        assertEquals(TsWetterSymbol.KLAR_NACHT, tsWetterAusSatz(s, Instant.fromEpochSeconds(tag0310 + 1018 * 60), 7200)!!.symbol)
        // Ohne Ort: immer Tag.
        assertEquals(TsWetterSymbol.KLAR_TAG, tsWetterAusSatz(satz(stand = tag0310 + 20 * 3600, breite = null), abends, 7200)!!.symbol)
    }

    @Test
    fun ohneTagesvorhersageDasJetzt() {
        val w = tsWetterAusSatz(satz(art = WeatherType.CloudyDay, tagesart = null), jetzt, 7200)!!
        assertEquals(TsWetterSymbol.BEWOELKT, w.symbolHeute)
    }

    @Test
    fun hochUndTiefNurZusammen() {
        val unbekannt = Short.MAX_VALUE.toInt()
        val w = tsWetterAusSatz(satz(hoch = unbekannt), jetzt, 7200)!!
        assertNull(w.hoch)
        assertNull(w.tief)
        assertEquals(22, w.temperatur)
        // Auch umgekehrt: ohne Tief kein Hoch.
        val ohneTief = tsWetterAusSatz(satz(tief = unbekannt), jetzt, 7200)!!
        assertNull(ohneTief.hoch)
        assertNull(ohneTief.tief)
    }

    @Test
    fun keinFrischesWetterWieAufDerUhr() {
        assertNull(tsWetterAusSatz(null, jetzt, 7200))
        // 3 h alt: noch; 3 h und 1 s: nicht mehr.
        assertEquals(22, tsWetterAusSatz(satz(stand = jetzt.epochSeconds - 3 * 3600), jetzt, 7200)?.temperatur)
        assertNull(tsWetterAusSatz(satz(stand = jetzt.epochSeconds - 3 * 3600 - 1), jetzt, 7200))
        // Mehr als eine Stunde in der Zukunft: nicht.
        assertNull(tsWetterAusSatz(satz(stand = jetzt.epochSeconds + 3601), jetzt, 7200))
        // Temperatur unbekannt oder ohne Ortsnamen (weather_service.c): kein Wetter.
        assertNull(tsWetterAusSatz(satz(temp = Short.MAX_VALUE.toInt()), jetzt, 7200))
        assertNull(tsWetterAusSatz(satz(name = ""), jetzt, 7200))
    }

    @Test
    fun uvAusDenStundenwertenDesSatzes() {
        // Ohne Zeitzone des Ortes gilt die der Uhr: +5 h -> 18:45 -> Stunde 19 -> 58 -> 6.
        assertEquals(6, tsWetterAusSatz(satz(versatz = null), jetzt, 5 * 3600)!!.uv)
        // Fehlender Stundenwert (null) -> 255 -> Tageswert 47 -> 4.
        val luecke = List<Short?>(24) { if (it == 16) null else (it * 3 + 1).toShort() }
        assertEquals(4, tsWetterAusSatz(satz(uvStunden = luecke), jetzt, 7200)!!.uv)
        // 254 ist der hoechste Wert, den Boulder schickt (255 = unbekannt): (254 + 5) / 10 = 25.
        val hoch = List<Short?>(24) { if (it == 16) 254 else 10 }
        assertEquals(25, tsWetterAusSatz(satz(uvStunden = hoch), jetzt, 7200)!!.uv)
        // Ohne Stundenwerte und ohne Tageswert: unbekannt ("...").
        assertNull(tsWetterAusSatz(satz(uvStunden = null, uvTag = null), jetzt, 7200)!!.uv)
        // Ohne Stundenwerte: der Tageswert, abgeschnitten (47 -> 4).
        assertEquals(4, tsWetterAusSatz(satz(uvStunden = null), jetzt, 7200)!!.uv)
        // Firmware b23 kannte nur den Tageswert: auch mit Stundenwerten 47 -> 4 (nicht 5).
        assertEquals(4, tsWetterAusSatz(satz(), jetzt, 7200, uvJeStunde = false)!!.uv)
        assertEquals(5, tsWetterAusSatz(satz(), jetzt, 7200, uvJeStunde = true)!!.uv)
    }
}
