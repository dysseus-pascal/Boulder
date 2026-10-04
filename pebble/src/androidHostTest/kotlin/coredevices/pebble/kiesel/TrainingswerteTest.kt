package coredevices.pebble.kiesel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingswerteTest {

    /** Eine gerade Strecke nach Osten, ein Punkt je Sekunde, rund 10 m/s. */
    private fun gerade(sekunden: Int, ab: Long = 1_000_000L): List<Spur.Punkt> =
        (0..sekunden).map { s -> Spur.Punkt(ab + s, 47.0, 7.0 + s * 0.000131, null, 5f) }

    @Test
    fun tempoHoechstensAlleZehnSekunden() {
        val proben = Trainingswerte.tempoProben(gerade(120), 1_000_000L, 1_000_121L)
        assertEquals(13, proben.size)
        assertTrue(proben.zipWithNext().all { (a, b) -> b.first - a.first >= Trainingswerte.TEMPO_TAKT_S })
        assertTrue(proben.drop(1).all { it.second in 9.0..11.0 })
    }

    @Test
    fun tempoNurInnerhalbDerSitzung() {
        // Wie Route, Distanz und Hoehe: was vor dem Start oder nach dem Ende lag, war kein Training.
        val proben = Trainingswerte.tempoProben(gerade(120), 1_000_030L, 1_000_060L)
        assertTrue(proben.isNotEmpty())
        assertTrue(proben.all { it.first in 1_000_030L until 1_000_060L })
    }

    @Test
    fun ohneSpurKeinTempo() {
        assertTrue(Trainingswerte.tempoProben(gerade(0), 0, Long.MAX_VALUE).isEmpty())
    }

    @Test
    fun teilstueckErsetztDieGanzeKurveNicht() {
        // Erst die ganze Kurve (360 Werte), dann ein nachgeschicktes Stueck von 40:
        // die Akte uebergeht die niedrigere Fassung, der Wert der ganzen Kurve bleibt.
        val ganz = Trainingswerte.fassungAusPuls(360, false, true)
        val stueck = Trainingswerte.fassungAusPuls(40, false, true)
        assertTrue(stueck < ganz)
        assertFalse(Trainingswerte.lohntSich(ganz, stueck))
        // Kommt dagegen mehr Puls dazu, ersetzt die neue Rechnung die alte.
        assertTrue(Trainingswerte.lohntSich(stueck, ganz))
    }

    @Test
    fun pulsSchlaegtUhrUndAlteFassung() {
        // Schon ein einziger Pulswert liegt ueber der Uhr (1) und ueber der festen
        // Fassung 2, mit der bis 0.40.0 jedes Teilstueck schrieb.
        assertTrue(Trainingswerte.fassungAusPuls(1, false, false) > Trainingswerte.FASSUNG_UHR)
        assertTrue(Trainingswerte.fassungAusPuls(1, false, false) > 2L)
        assertTrue(Trainingswerte.lohntSich(2L, Trainingswerte.fassungAusPuls(1, false, false)))
        assertTrue(Trainingswerte.lohntSich(Trainingswerte.FASSUNG_UHR, Trainingswerte.fassungAusPuls(1, false, false)))
    }

    @Test
    fun nachholenNurWoEtwasFehlt() {
        // Fehlt der Eintrag, wird geschrieben; bei gleichem Stand nicht noch einmal.
        assertTrue(Trainingswerte.lohntSich(null, Trainingswerte.fassungAusPuls(100, false, true)))
        assertFalse(Trainingswerte.lohntSich(Trainingswerte.fassungAusPuls(100, false, true), Trainingswerte.fassungAusPuls(100, false, true)))
    }

    @Test
    fun dieWaageSchlaegtDasEingestellteGewicht() {
        // Im Hintergrund ohne Waage gerechnet, im Detail mit: bei gleichem Puls
        // ersetzt die Rechnung mit der Waage, umgekehrt nicht.
        val ohne = Trainingswerte.fassungAusPuls(360, false, true)
        val mit = Trainingswerte.fassungAusPuls(360, true, true)
        assertTrue(Trainingswerte.lohntSich(ohne, mit))
        assertFalse(Trainingswerte.lohntSich(mit, ohne))
        // Mehr Puls ersetzt trotzdem - auch ohne Waage.
        assertTrue(Trainingswerte.lohntSich(mit, Trainingswerte.fassungAusPuls(361, false, false)))
        // Jede Fassung bis 0.42 (2 + Pulswerte) wird einmal neu gerechnet.
        assertTrue(Trainingswerte.lohntSich(2L + 360, ohne))
    }

    @Test
    fun derRuhepulsDerNaechteSchlaegtDenErsatz() {
        // Die Naechte kamen erst nach dem Training aufs Telefon: zuerst mit dem Ersatz (60) gerechnet,
        // beim naechsten Start mit ihnen - derselbe Puls, und die neue Rechnung ersetzt die alte.
        val ersatz = Trainingswerte.fassungAusPuls(360, waage = false, ruheAusNaechten = false)
        val naechte = Trainingswerte.fassungAusPuls(360, waage = false, ruheAusNaechten = true)
        assertTrue(Trainingswerte.lohntSich(ersatz, naechte))
        assertFalse(Trainingswerte.lohntSich(naechte, ersatz))
        // Auch mit der Waage im Ersatz-Stand: der Ruhepuls wiegt schwerer, ein Pulswert mehr noch schwerer.
        assertTrue(Trainingswerte.lohntSich(Trainingswerte.fassungAusPuls(360, waage = true, ruheAusNaechten = false), naechte))
        assertTrue(Trainingswerte.lohntSich(Trainingswerte.fassungAusPuls(360, waage = true, ruheAusNaechten = true), Trainingswerte.fassungAusPuls(361, waage = false, ruheAusNaechten = false)))
        // Jede Fassung bis 0.48 (2 + 2 * Pulswerte + Waage) wird einmal neu gerechnet, auch mit dem Ersatz.
        assertTrue(Trainingswerte.lohntSich(2L + 2 * 360 + 1, ersatz))
        // Die Werte selbst, festgenagelt: 2 + 4 * 360 + 2 + 1.
        assertEquals(1445L, Trainingswerte.fassungAusPuls(360, waage = true, ruheAusNaechten = true))
        assertEquals(1442L, ersatz)
    }

    @Test
    fun einUnplausiblerRuhepulsDerNaechteIstErsatz() {
        // Die Rechnung nimmt nur 30 bis 110 bpm, sonst den Ersatz - und die Fassung sagt dasselbe.
        assertFalse(Trainingswerte.ruheAusNaechten(null))
        assertFalse(Trainingswerte.ruheAusNaechten(29))
        assertTrue(Trainingswerte.ruheAusNaechten(30))
        assertTrue(Trainingswerte.ruheAusNaechten(110))
        assertFalse(Trainingswerte.ruheAusNaechten(111))
        assertEquals(30..110, Trainingsanalyse.RUHEPULS_GUELTIG)
    }
}
