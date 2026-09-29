package coredevices.pebble.kiesel

import io.rebble.libpebblecommon.database.entity.NachtEntity
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        // Tag 0->1: geladen (95 > 72); Tag 1->2: Nacht 1 war am Kabel. Kein Tageswert.
        assertNull(s.tagJeStunde)
        assertEquals(24.0, s.jeTag!!, 0.01)
        assertNull(s.tageUebrig)
    }

    @Test
    fun lueckeUeberEinenTagZaehltNichtAlsTag() {
        // Eine Nacht fehlt (Uhr abgelegt): zwischen Aufwachen und dem naechsten
        // Einschlafen liegen 40 h - daraus keinen Tagesverbrauch machen.
        val n = listOf(nacht(0, 80, 72), nacht(2, 40, 32))
        val s = Akkurechnung.rechne(n, null, Instant.ofEpochSecond(beginn + 3 * tag))
        assertNull(s.tagJeStunde)
        assertEquals(24.0, s.jeTag!!, 0.01)
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

    @Test
    fun medianNimmtDieMitte() {
        assertEquals(2.0, Akkurechnung.median(listOf(9.0, 1.0, 2.0)))
        assertEquals(1.5, Akkurechnung.median(listOf(1.0, 2.0)))
    }
}
