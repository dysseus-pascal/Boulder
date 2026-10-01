package coredevices.pebble.kiesel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * Die Auswertung ohne Telefon pruefen.
 *
 * Sie ist die einzige Stelle der App, die etwas BEHAUPTET - "dein Mittwoch ist
 * schwach". Eine falsch gezogene Grenze faellt am Geraet nicht auf: das Bild
 * sieht immer plausibel aus. Hier faellt sie auf.
 */
class AuswertungTest {

    private val heute = LocalDate.of(2026, 9, 19)     // ein Samstag

    private fun tag(minusTage: Long, wert: Double) = heute.minusDays(minusTage) to wert

    @Test
    fun `heute zaehlt nicht mit`() {
        val bild = Auswertung.bild(
            listOf(tag(0, 100.0), tag(7, 1000.0), tag(14, 1000.0)), heute
        )
        assertEquals(2, bild.anzahl)
        assertEquals(1000.0, bild.gesamt!!, 0.001)

        val samstag = bild.profil.first { it.tag == DayOfWeek.SATURDAY }
        assertEquals(1000.0, samstag.mittel!!, 0.001)
    }

    @Test
    fun `ein einzelner Tag ergibt noch kein Muster`() {
        val bild = Auswertung.bild(listOf(tag(3, 500.0)), heute)
        val mittwoch = bild.profil.first { it.tag == DayOfWeek.WEDNESDAY }
        assertNull(mittwoch.mittel)
        assertEquals(1, mittwoch.anzahl)
    }

    @Test
    fun `zwei gleiche Wochentage werden gemittelt`() {
        val bild = Auswertung.bild(listOf(tag(3, 400.0), tag(10, 600.0)), heute)
        val mittwoch = bild.profil.first { it.tag == DayOfWeek.WEDNESDAY }
        assertEquals(500.0, mittwoch.mittel!!, 0.001)
        assertEquals(2, mittwoch.anzahl)
    }

    @Test
    fun `staerkster und schwaechster Tag`() {
        val werte = mutableListOf<Pair<LocalDate, Double>>()
        (1L..28L).forEach { i ->
            val t = heute.minusDays(i)
            werte += t to if (t.dayOfWeek == DayOfWeek.SUNDAY) 2000.0 else 1000.0
        }
        val bild = Auswertung.bild(werte, heute)
        assertEquals(DayOfWeek.SUNDAY, bild.staerkster!!.tag)
        assertEquals(1000.0, bild.schwaechster!!.mittel!!, 0.001)
    }

    @Test
    fun `Veraenderung braucht beide Haelften`() {
        val kurz = (1L..10L).map { tag(it, 1000.0) }
        assertNull(Auswertung.bild(kurz, heute).veraenderung)

        val lang = (1L..56L).map { i -> tag(i, if (i <= 28) 1100.0 else 1000.0) }
        assertEquals(10.0, Auswertung.bild(lang, heute).veraenderung!!, 0.001)
    }

    @Test
    fun `der Verlauf zeigt hoechstens acht Wochen`() {
        val lang = (1L..200L).map { tag(it, 1000.0) }
        assertEquals(Auswertung.WOCHEN, Auswertung.bild(lang, heute).wochen.size)
    }

    @Test
    fun `das Wochenprofil merkt sich die Spanne`() {
        val werte = listOf(tag(3, 4000.0), tag(10, 8000.0), tag(17, 12000.0))
        val mittwoch = Auswertung.bild(werte, heute).profil
            .first { it.tag == DayOfWeek.WEDNESDAY }
        assertEquals(8000.0, mittwoch.mittel!!, 0.001)
        assertEquals(4000.0, mittwoch.kleinster!!, 0.001)
        assertEquals(12000.0, mittwoch.groesster!!, 0.001)
    }

    @Test
    fun `Wochenende braucht beide Seiten`() {
        // Nur Wochentage: kein Vergleich.
        val nurWerk = (1L..20L)
            .map { heute.minusDays(it) to 100.0 }
            .filter { it.first.dayOfWeek != DayOfWeek.SATURDAY &&
                      it.first.dayOfWeek != DayOfWeek.SUNDAY }
        assertNull(Auswertung.wochenende(nurWerk, heute))
    }

    @Test
    fun `Wochenende rechnet beide Seiten getrennt`() {
        val werte = (1L..28L).map { i ->
            val t = heute.minusDays(i)
            val frei = t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY
            t to if (frei) 500.0 else 100.0
        }
        val w = Auswertung.wochenende(werte, heute)!!
        assertEquals(100.0, w.werktag, 0.001)
        assertEquals(500.0, w.wochenende, 0.001)
        assertEquals(400.0, w.unterschied, 0.001)
    }

    @Test
    fun `die Grenze entscheidet, ob heute mitzaehlt`() {
        // Eine Nacht ist am Morgen fertig: mit Grenze morgen zaehlt der heutige Tag mit.
        val reihe = listOf(tag(0, 400.0), tag(7, 500.0))
        assertEquals(1, Auswertung.bild(reihe, heute).anzahl)
        assertEquals(2, Auswertung.bild(reihe, heute.plusDays(1)).anzahl)
    }

    @Test
    fun `die letzten Tage sind lueckenlos`() {
        val reihe = listOf(tag(1, 10.0), tag(3, 30.0), tag(20, 99.0))
        val fenster = Auswertung.letzteTage(reihe, heute, 5)
        assertEquals(5, fenster.size)
        assertEquals(heute.minusDays(5), fenster.first().first)
        assertEquals(heute.minusDays(1), fenster.last().first)
        assertEquals(listOf(null, null, 30.0, null, 10.0), fenster.map { it.second })
    }

    @Test
    fun `die letzten Tage aus einer leeren Reihe sind lauter Luecken`() {
        val fenster = Auswertung.letzteTage(emptyList(), heute, 3)
        assertEquals(3, fenster.size)
        assertEquals(listOf(null, null, null), fenster.map { it.second })
    }

    @Test
    fun `Achsenschritte sind rund und hoechstens fuenf`() {
        assertEquals(listOf(50.0, 60.0, 70.0), Auswertung.achsenschritte(45.0, 70.0))
        assertEquals(listOf(46.0, 48.0, 50.0, 52.0), Auswertung.achsenschritte(45.0, 52.0))
        assertEquals(listOf(50.0, 55.0, 60.0), Auswertung.achsenschritte(50.0, 60.0))
        assertEquals(listOf(55.0), Auswertung.achsenschritte(55.0, 55.0))
        assertEquals(listOf(4000.0, 6000.0, 8000.0, 10000.0), Auswertung.achsenschritte(3500.0, 11000.0))
    }

    @Test
    fun `Achsenschritte sind nie weniger als drei`() {
        assertEquals(listOf(50.0, 55.0, 60.0, 65.0), Auswertung.achsenschritte(52.0, 62.0))
        assertEquals(listOf(45.0, 50.0, 55.0, 60.0), Auswertung.achsenschritte(46.0, 58.0))
        assertEquals(listOf(45.0, 50.0, 55.0, 60.0), Auswertung.achsenschritte(46.0, 59.0))
    }

    @Test
    fun `Tausender werden kurz`() {
        assertEquals("812", Auswertung.tausender(812.0, Locale.US))
        assertEquals("4.9k", Auswertung.tausender(4940.0, Locale.US))
        assertEquals("5k", Auswertung.tausender(4986.0, Locale.US))
        assertEquals("10k", Auswertung.tausender(10000.0, Locale.US))
        assertEquals("12.3k", Auswertung.tausender(12345.0, Locale.US))
        assertEquals("4,9k", Auswertung.tausender(4940.0, Locale.GERMAN))
        assertEquals("1k", Auswertung.tausender(999.6, Locale.US))
        assertEquals("999", Auswertung.tausender(999.4, Locale.US))
    }

    @Test
    fun `wann die Bausteine tragen koennen`() {
        val erster = LocalDate.of(2026, 9, 30)   // ein Mittwoch
        assertEquals(LocalDate.of(2026, 10, 14), Auswertung.typischeWocheAb(erster))
        val heute = LocalDate.of(2026, 10, 5)
        // Nachtreihen zaehlen bis heute: der Montag selbst traegt.
        assertEquals(LocalDate.of(2026, 10, 12), Auswertung.wochenAb(erster, heute.plusDays(1), heute))
        // Tageszaehler zaehlen bis gestern: der Montag traegt erst am Dienstag.
        assertEquals(LocalDate.of(2026, 10, 13), Auswertung.wochenAb(erster, heute, heute))
    }

    @Test
    fun `Streuung braucht drei Werte`() {
        assertNull(Auswertung.streuung(listOf(1.0, 2.0)))
        assertEquals(1.0, Auswertung.streuung(listOf(1.0, 2.0, 3.0))!!, 0.0001)
    }

    @Test
    fun `gleiche Werte streuen nicht`() {
        assertEquals(0.0, Auswertung.streuung(listOf(7.0, 7.0, 7.0, 7.0))!!, 0.0001)
    }

    @Test
    fun `ein klarer Unterschied zweier Gruppen`() {
        // Gerade Tage niedrig (um 46), ungerade hoch (um 55), wenig Streuung.
        val werte = (1L..20L).map { tag(it, if (it % 2 == 0L) 46.0 + (it % 3) else 55.0 - (it % 3)) }
        val niedrig = (1L..20L).filter { it % 2 == 0L }.map { heute.minusDays(it) }.toSet()
        val v = Auswertung.vergleich(werte, heute) { t -> t in niedrig }
        assertTrue(v.genug)
        assertTrue(v.klar)
        assertTrue(v.unterschied < -6)
    }

    @Test
    fun `Rauschen ist kein Unterschied`() {
        val werte = (1L..20L).map { tag(it, listOf(48.0, 56.0, 51.0, 53.0, 49.0)[(it % 5).toInt()]) }
        val gerade = (1L..20L).filter { it % 2 == 0L }.map { heute.minusDays(it) }.toSet()
        val v = Auswertung.vergleich(werte, heute) { t -> t in gerade }
        assertFalse(v.klar)
    }

    @Test
    fun `zu wenig Tage in einer Gruppe`() {
        val werte = (1L..20L).map { tag(it, it.toDouble()) }
        val v = Auswertung.vergleich(werte, heute) { t -> if (t == heute.minusDays(3)) true else false }
        assertFalse(v.genug)
    }

    @Test
    fun `aelter als dreissig Tage zaehlt nicht`() {
        val werte = (1L..60L).map { tag(it, 1.0) }
        val v = Auswertung.vergleich(werte, heute) { true }
        assertEquals(30, v.a.size)
    }
}
