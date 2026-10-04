package coredevices.pebble.timestyle.bild

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** Was die Vorschau zeigt: echte Zeit, Einheiten und Akku der Uhr, Boulders Daten (TsDaten). */
class TsVorschauTest {
    // Sa 3.10.2026 13:45:07 UTC = 15:45:07 in Zuerich (Sommerzeit).
    private val jetzt = Instant.fromEpochSeconds(1790985600L + 13 * 3600 + 45 * 60 + 7)
    private val zuerich = TimeZone.of("Europe/Zurich")

    private fun zustand(akku: Int? = null, daten: TsDaten = TsDaten.LEER) =
        tsVorschauZustand(jetzt, zuerich, vierundzwanzig = true, metrisch = true, akku = akku, daten = daten)

    @Test
    fun uhrzeitUndDatumSindEcht() {
        val z = zustand()
        assertEquals(15, z.stunde)
        assertEquals(45, z.minute)
        assertEquals(7, z.sekunde)
        assertEquals(3, z.tag)
        assertEquals(9, z.monat) // tm_mon: Oktober = 9
        assertEquals(2026, z.jahr)
        assertEquals(6, z.wochentag) // tm_wday: Samstag = 6
        assertEquals(275, z.jahresTag) // tm_yday, ab 0
        assertEquals(7200L, z.utcVersatzSekunden)
        // 13:45:07 UTC = 14:45:07 in Biel.
        assertEquals(615, z.beats)
        assertEquals(true, z.vierundzwanzig)
    }

    @Test
    fun sonntagIstNull() {
        val sonntag = Instant.fromEpochSeconds(1790985600L + 86400 + 12 * 3600)
        assertEquals(0, tsVorschauZustand(sonntag, zuerich, true, true, null, TsDaten.LEER).wochentag)
    }

    @Test
    fun boulderDatenKommenUnveraendertAn() {
        val wetter = TsWetter(72, 75, 54, 4, TsWetterSymbol.KLAR_TAG, TsWetterSymbol.REGEN)
        val daten = TsDaten(wetter, schritte = 1234, distanzMeter = 987, puls = 71,
            schlafSekunden = 25200, ruhigerSchlafSekunden = 5400, verbunden = false)
        val z = zustand(daten = daten)
        // Die Temperatur steht so da, wie Boulder sie geschickt hat (seine Einheit, hier F).
        assertEquals(wetter, z.wetter)
        assertEquals(1234, z.schritte)
        assertEquals(987, z.distanzMeter)
        assertEquals(71, z.puls)
        assertEquals(25200, z.schlafSekunden)
        assertEquals(5400, z.ruhigerSchlafSekunden)
        assertEquals(false, z.verbunden)
    }

    @Test
    fun ohneDatenWieEineUhrOhneDaten() {
        // Kein Wetter: "..." ohne Symbol; Gesundheit 0 - wie die Uhr ohne Daten.
        val z = zustand()
        assertEquals(null, z.wetter)
        assertEquals(0, z.schritte)
        assertEquals(0, z.puls)
        assertEquals(0, z.schlafSekunden)
        assertEquals(true, z.verbunden)
    }

    @Test
    fun akkuDerUhrWennBekannt() {
        // Nie gemeldet: der Wert des Pruefbaus TS_DEMO (80 %, sidebar_widgets.c).
        assertEquals(80, TS_BEISPIEL_AKKU)
        assertEquals(80, zustand(akku = null).akkuProzent)
        assertEquals(37, zustand(akku = 37).akkuProzent)
        assertEquals(100, zustand(akku = 140).akkuProzent)
        assertEquals(0, zustand(akku = -3).akkuProzent)
    }
}
