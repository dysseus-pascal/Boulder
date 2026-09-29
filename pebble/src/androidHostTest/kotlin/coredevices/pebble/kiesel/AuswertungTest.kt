package coredevices.pebble.kiesel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `ein Zusammenhang braucht gemeinsame Tage`() {
        val a = (1L..20L).map { tag(it, it.toDouble()) }
        val b = (1L..20L).map { heute.minusDays(it + 100) to it.toDouble() }
        assertEquals(0, Auswertung.zusammenhang(a, b, heute).n)
    }

    @Test
    fun `steigt eins mit dem anderen, ist r eins`() {
        val a = (1L..20L).map { tag(it, it.toDouble()) }
        val b = (1L..20L).map { tag(it, 3.0 * it + 7) }
        val z = Auswertung.zusammenhang(a, b, heute)
        assertEquals(20, z.n)
        assertEquals(1.0, z.r!!, 0.0001)
        assertEquals(3.0, z.steigung, 0.0001)
        assertEquals(7.0, z.achse, 0.0001)
    }

    @Test
    fun `faellt eins mit dem anderen, ist r minus eins`() {
        val a = (1L..20L).map { tag(it, it.toDouble()) }
        val b = (1L..20L).map { tag(it, 100.0 - it) }
        assertEquals(-1.0, Auswertung.zusammenhang(a, b, heute).r!!, 0.0001)
    }

    @Test
    fun `ohne Schwankung gibt es kein r`() {
        val a = (1L..20L).map { tag(it, it.toDouble()) }
        val b = (1L..20L).map { tag(it, 42.0) }
        assertNull(Auswertung.zusammenhang(a, b, heute).r)
    }

    @Test
    fun `unter vierzehn Tagen ist nichts belastbar`() {
        val a = (1L..13L).map { tag(it, it.toDouble()) }
        val b = (1L..13L).map { tag(it, 2.0 * it) }
        val z = Auswertung.zusammenhang(a, b, heute)
        assertEquals(1.0, z.r!!, 0.0001)
        assertFalse(z.belastbar)
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
    fun `Streuung braucht drei Werte`() {
        assertNull(Auswertung.streuung(listOf(1.0, 2.0)))
        assertEquals(1.0, Auswertung.streuung(listOf(1.0, 2.0, 3.0))!!, 0.0001)
    }

    @Test
    fun `gleiche Werte streuen nicht`() {
        assertEquals(0.0, Auswertung.streuung(listOf(7.0, 7.0, 7.0, 7.0))!!, 0.0001)
    }
}
