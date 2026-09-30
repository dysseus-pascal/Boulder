package coredevices.pebble.kiesel

import io.rebble.libpebblecommon.database.entity.NachtEntity
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AkkurechnungTest {
    private val tag = 86400L
    private val beginn = 1790445600L   // 26.9.2026 18:00 UTC, Fensterbeginn 20:00 Ortszeit

    /** Eine Nacht: eingeschlafen 22:00 (Minute 120), aufgewacht 06:00 (Minute 600). */
    private fun nacht(n: Int, von: Int, bis: Int, geladen: Boolean = false) =
        NachtEntity(beginn + n * tag, 7200, 120, 600, 420, 50, 60, akkuBeginn = von, akkuEnde = bis, geladen = geladen)

    @Test
    fun nachtUndTagGetrennt() {
        // Nachts 8 % in 8 h (1 %/h), tagsueber von 06:00 bis 22:00 16 % (1 %/h): 24 %/Tag.
        val n = listOf(nacht(0, 80, 72), nacht(1, 56, 48), nacht(2, 32, 24))
        val s = Akkurechnung.rechne(n, 20, Instant.ofEpochSecond(beginn + 3 * tag))
        assertEquals(1.0, s.nachtJeStunde!!, 0.01)
        assertEquals(1.0, s.tagJeStunde!!, 0.01)
        assertEquals(24.0, s.jeTag!!, 0.01)
        assertEquals(100 / 24.0, s.tageJeLadung!!, 0.01)
        assertEquals(20 / 24.0, s.tageUebrig!!, 0.01)
        assertEquals(7, s.punkte.size)
    }

    @Test
    fun ladenZaehltNicht() {
        // Zweite Nacht am Kabel, und der Tag davor endet hoeher als er begann.
        val n = listOf(nacht(0, 80, 72), nacht(1, 95, 100, geladen = true), nacht(2, 90, 82))
        val s = Akkurechnung.rechne(n, null, Instant.ofEpochSecond(beginn + 3 * tag))
        assertEquals(1.0, s.nachtJeStunde!!, 0.01)
        // Tag 0->1: geladen (95 > 72); Tag 1->2: Nacht 1 war am Kabel. Kein
        // Tageswert - und damit keine Restzeit, nicht 24 x Nachtwert.
        assertNull(s.tagJeStunde)
        assertNull(s.jeTag)
        assertNull(s.tageUebrig)
    }

    @Test
    fun lueckeUeberEinenTagZaehltNichtAlsTag() {
        // Eine Nacht fehlt (Uhr abgelegt): zwischen Aufwachen und dem naechsten
        // Einschlafen liegen 40 h - daraus keinen Tagesverbrauch machen.
        val n = listOf(nacht(0, 80, 72), nacht(2, 40, 32))
        val s = Akkurechnung.rechne(n, null, Instant.ofEpochSecond(beginn + 3 * tag))
        assertNull(s.tagJeStunde)
        assertNull(s.jeTag)
    }

    @Test
    fun nachtOhneDauerFaelltHeraus() {
        val kaputt = NachtEntity(beginn + tag, 7200, 300, 300, 0, 50, 60, akkuBeginn = 70, akkuEnde = 70)
        val s = Akkurechnung.rechne(listOf(nacht(0, 80, 72), kaputt), null, Instant.ofEpochSecond(beginn + 3 * tag))
        assertEquals(1, s.naechte.size)
    }

    @Test
    fun ohneNaechteNichts() {
        val s = Akkurechnung.rechne(emptyList(), 84, Instant.ofEpochSecond(beginn))
        assertNull(s.jeTag)
        assertEquals(listOf(beginn to 84), s.punkte)
    }

    /** Die Naechte aus boulder_nacht vom 29./30.9.2026, wie die Uhr sie schickte. */
    private val echt = listOf(
        NachtEntity(1790618400, 7200, 132, 561, 406, 54, 53, akkuBeginn = 41, akkuEnde = 39),  // 22:12-05:21
        NachtEntity(1790652060, 7200, 28, 96, 68, 53, 84, akkuBeginn = 39, akkuEnde = 39),     // 05:49-06:57
        NachtEntity(1790704800, 7200, 54, 600, 518, 55, 52, akkuBeginn = 84, akkuEnde = 80),   // 20:54-06:00
    )

    @Test
    fun echteNaechteOhneTagKeineRestzeit() {
        // Frueher: Median von 0,28 / 0 / 0,44 %/h, mal 24 h - bei 75 % elf
        // Tage und mehr. Die kurze "Nacht" zaehlt jetzt nicht, die beiden
        // langen zusammen: 6 % in 16,25 h. Dazwischen wurde geladen, einen Tag
        // gibt es nicht, also auch keine Restzeit.
        val s = Akkurechnung.rechne(echt, 75, Instant.ofEpochSecond(1790760000))
        assertEquals(6 / 16.25, s.nachtJeStunde!!, 0.001)
        assertNull(s.tagJeStunde)
        assertNull(s.tageUebrig)
        // Die Liste zeigt die kurze "Nacht" ohne Wert, nicht mit 0,0 %/h.
        assertNull(s.naechte[1].jeStunde)
    }

    @Test
    fun eineNachtGibtNurDenNachtwert() {
        val s = Akkurechnung.rechne(listOf(nacht(0, 80, 72)), 70, Instant.ofEpochSecond(beginn + tag))
        assertEquals(1.0, s.nachtJeStunde!!, 0.01)
        assertNull(s.tagJeStunde)
        assertNull(s.jeTag)
        assertNull(s.tageUebrig)
    }

    @Test
    fun nurNaechteAmKabelGebenNichts() {
        val n = listOf(nacht(0, 60, 100, geladen = true), nacht(1, 90, 100, geladen = true))
        val s = Akkurechnung.rechne(n, 95, Instant.ofEpochSecond(beginn + 2 * tag))
        assertNull(s.nachtJeStunde)
        assertNull(s.tagJeStunde)
        assertNull(s.jeTag)
        assertNull(s.tageUebrig)
        assertEquals(2, s.naechte.size)
    }

    @Test
    fun echteNaechteMitEinemTag() {
        // Ausgedacht, nach den echten: ein Tag von 06:00 bis 22:00 mit 80 -> 66 %
        // (14 %, wie die Uhr sie wirklich braucht), dann die Nacht 22:00-06:00
        // (8 h) mit 66 -> 62. Rund 17 %/Tag, bei 66 % knapp vier Tage.
        val abend = NachtEntity(1790704800 + 86400, 7200, 120, 600, 420, 55, 52, akkuBeginn = 66, akkuEnde = 62)
        val s = Akkurechnung.rechne(echt + abend, 66, Instant.ofEpochSecond(1790704800 + 86400 + 7200))
        assertEquals(14 / 16.0, s.tagJeStunde!!, 0.001)
        val nacht = 10 / (16.25 + 8)
        assertEquals(8 * nacht + 16 * 14 / 16.0, s.jeTag!!, 0.001)
        assertTrue(s.tageUebrig!! in 3.0..5.0, "${s.tageUebrig}")
    }

    @Test
    fun rundungGleichtSichAus() {
        // Drei Naechte zu je 7 h mit 1, 3, 2 %: jede einzeln 0,14 bis 0,43 %/h,
        // zusammen 6 % in 21 h.
        assertEquals(6 / 21.0, Akkurechnung.rate(listOf(1 to 7.0, 3 to 7.0, 2 to 7.0))!!, 0.001)
        assertNull(Akkurechnung.rate(emptyList()))
    }
}
