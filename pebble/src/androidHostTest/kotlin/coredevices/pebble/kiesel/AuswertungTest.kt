package coredevices.pebble.kiesel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

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
        assertEquals(Fenster.TREND_WOCHEN, Auswertung.bild(lang, heute).wochen.size)
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
    fun `wann die Bausteine tragen koennen`() {
        val erster = LocalDate.of(2026, 9, 30)   // ein Mittwoch
        // Mi/Do/Fr je zweimal: 30.09./07.10., 01./08.10., 02./09.10. - Naechte zaehlen bis heute.
        assertEquals(LocalDate.of(2026, 10, 9), Auswertung.typischeWocheAb(erster, LocalDate.of(2026, 10, 9).plusDays(1), LocalDate.of(2026, 10, 9)))
        // Tageszaehler erst, wenn der 09.10. vorbei ist.
        assertEquals(LocalDate.of(2026, 10, 10), Auswertung.typischeWocheAb(erster, LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 10)))
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
    fun `typische Woche erscheint am angekuendigten Tag`() {
        val erster = LocalDate.of(2026, 9, 30)
        // Lueckenlose Tageszaehler ab dem ersten Tag: am angekuendigten Tag tragen drei Wochentage.
        val ab = Auswertung.typischeWocheAb(erster, LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 10))
        val reihe = (0L..20L).map { erster.plusDays(it) to 100.0 }
        val amTag = Auswertung.bild(reihe, ab).profil.count { it.mittel != null }
        val davor = Auswertung.bild(reihe, ab.minusDays(1)).profil.count { it.mittel != null }
        assertEquals(3, amTag)
        assertEquals(2, davor)
    }

    @Test
    fun `Wochenprofil und Wochenende rechnen im festen Fenster`() {
        // Vor dem Fenster jeder Tag 9000, darin jeder Tag 1000.
        val alt = (Fenster.TREND_TAGE + 1..Fenster.TREND_TAGE + 60).map { tag(it, 9000.0) }
        val jung = (1L..Fenster.TREND_TAGE).map { tag(it, 1000.0) }
        val bild = Auswertung.bild(alt + jung, heute)
        assertEquals(Fenster.TREND_TAGE.toInt(), bild.anzahl)
        assertEquals(1000.0, bild.gesamt!!, 0.001)
        val w = Auswertung.wochenende(alt + jung, heute)!!
        assertEquals(1000.0, w.werktag, 0.001)
        assertEquals(1000.0, w.wochenende, 0.001)
    }

    @Test
    fun `keine Richtung ueber einen Quellwechsel`() {
        val lang = (1L..56L).map { i -> tag(i, if (i <= 28) 1100.0 else 1000.0) }
        // Boulder misst erst seit drei Wochen: davor ist es Vorgeschichte, kein Trend.
        assertNull(Auswertung.bild(lang, heute, eigenAb = heute.minusDays(21)).veraenderung)
        // Liegt der Wechsel vor den acht Wochen, gilt die Richtung.
        assertEquals(10.0, Auswertung.bild(lang, heute, eigenAb = heute.minusDays(60)).veraenderung!!, 0.001)
    }

    @Test
    fun `gleiche Werte ohne Streuung sind ein klarer Unterschied`() {
        // Fuenfmal 160 mg gegen sechsmal 80 mg: keine Streuung, aber 80 mg Abstand.
        val tage = (1L..11L).map { tag(it, if (it <= 5) 160.0 else 80.0) }
        val viel = (1L..5L).map { heute.minusDays(it) }.toSet()
        val v = Auswertung.vergleich(tage, heute) { t -> t in viel }
        assertEquals(0.0, v.streuung, 0.0001)
        assertTrue(v.klar)
        // Ohne Abstand bleibt es "no difference".
        assertFalse(Auswertung.vergleich((1L..10L).map { tag(it, 80.0) }, heute) { t -> t.dayOfMonth % 2 == 0 }.klar)
    }

    @Test
    fun `aelter als dreissig Tage zaehlt nicht`() {
        val werte = (1L..60L).map { tag(it, 1.0) }
        val v = Auswertung.vergleich(werte, heute) { true }
        assertEquals(30, v.a.size)
    }
}
