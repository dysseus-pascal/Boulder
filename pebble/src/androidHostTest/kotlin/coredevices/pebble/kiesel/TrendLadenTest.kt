package coredevices.pebble.kiesel

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Boulder: die Bewegung je Tag nur noch ab heute-SICHER_TAGE, und was der taegliche Sicherungslauf WorkManager meldet. */
class TrendLadenTest {
    private val heute = LocalDate.of(2026, 10, 3)

    /** 180 Tage Schritte und Aktivminuten wie bisher gelesen, dazu Naechte und ein Archiv. */
    private fun neu(ab: LocalDate): Reihen {
        val tage = generateSequence(heute.minusDays(Trenddaten.TAGE)) { it.plusDays(1) }.takeWhile { it < heute }.filter { it >= ab }
        return mapOf(
            Spalte.SCHRITTE.schluessel to tage.map { it to (it.dayOfYear * 37 % 9000).toDouble() }.toList(),
            Spalte.AKTIV.schluessel to tage.map { it to (it.dayOfYear % 60).toDouble() }.toList(),
            Spalte.SCHLAF.schluessel to listOf(heute.minusDays(150) to 400.0, heute to 450.0),
        )
    }

    private val archiv = mapOf(
        Spalte.SCHRITTE.schluessel to listOf(heute.minusDays(120) to 7000.0, heute.minusDays(89) to 11000.0),
        Spalte.SCHLAF.schluessel to listOf(heute.minusDays(160) to 380.0),
    )

    @Test
    fun ab_sicher_tage_gelesen_ist_das_ergebnis_gleich() {
        val vorher = neu(heute.minusDays(Trenddaten.TAGE))
        val jetzt = neu(Trenddaten.bewegungAb(heute))
        assertTrue(jetzt.getValue(Spalte.SCHRITTE.schluessel).size < vorher.getValue(Spalte.SCHRITTE.schluessel).size)
        assertEquals(Trenddaten.mitArchiv(vorher, archiv, heute), Trenddaten.mitArchiv(jetzt, archiv, heute))
        assertEquals(Trenddaten.festzuschreiben(vorher, heute), Trenddaten.festzuschreiben(jetzt, heute))
    }

    @Test
    fun sicherung_versucht_hoechstens_dreimal_nach() {
        assertTrue((0 until Sichern.VERSUCHE).all { Sichern.nochmal(it) })
        assertFalse(Sichern.nochmal(Sichern.VERSUCHE))
        assertEquals(3, Sichern.VERSUCHE)
    }

    private val kein = "The folder is not reachable. Choose it again."
    private val fehl = "The backup could not be written."

    @Test
    fun sicherung_schreibfehler_dreimal_nochmal_dann_bis_morgen() {
        val aus = (0..4).map { Sichern.ausgang(fehl, kein, fehl, versuch = it) }
        assertEquals(List(3) { Sichern.Ausgang.NOCHMAL } + List(2) { Sichern.Ausgang.AUFGEBEN }, aus)
        // Eine Ausnahme zaehlt wie ein Schreibfehler.
        assertEquals(Sichern.Ausgang.NOCHMAL, Sichern.nachFehler(2))
        assertEquals(Sichern.Ausgang.AUFGEBEN, Sichern.nachFehler(3))
    }

    @Test
    fun sicherung_ohne_ordner_ist_ein_erfolg_mit_zeile_auch_im_wiederholten_versuch() {
        assertEquals(Sichern.Ausgang.OHNE_ORDNER, Sichern.ausgang(kein, kein, fehl, versuch = 0))
        assertEquals(Sichern.Ausgang.OHNE_ORDNER, Sichern.ausgang(kein, kein, fehl, versuch = 5))
    }

    @Test
    fun sicherung_gelungen_oder_kein_ordner_gewaehlt_ist_fertig() {
        assertEquals(Sichern.Ausgang.FERTIG, Sichern.ausgang("Backed up 120 days.", kein, fehl, versuch = 0))
        assertEquals(Sichern.Ausgang.FERTIG, Sichern.ausgang(null, kein, fehl, versuch = 2))
    }

    @Test
    fun sicherung_vermerkt_den_fehlenden_ordner_nie_zweimal_hintereinander() {
        val zeile = "Backup: " + kein
        assertTrue(Sichern.neueZeile(null, zeile))
        assertFalse(Sichern.neueZeile(zeile, zeile))
        assertTrue(Sichern.neueZeile("Kieselsport: Saved", zeile))
    }
}
