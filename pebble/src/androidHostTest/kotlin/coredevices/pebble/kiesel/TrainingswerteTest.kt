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
        val ganz = Trainingswerte.fassungAusPuls(360, false)
        val stueck = Trainingswerte.fassungAusPuls(40, false)
        assertTrue(stueck < ganz)
        assertFalse(Trainingswerte.lohntSich(ganz, stueck))
        // Kommt dagegen mehr Puls dazu, ersetzt die neue Rechnung die alte.
        assertTrue(Trainingswerte.lohntSich(stueck, ganz))
    }

    @Test
    fun pulsSchlaegtUhrUndAlteFassung() {
        // Schon ein einziger Pulswert liegt ueber der Uhr (1) und ueber der festen
        // Fassung 2, mit der bis 0.40.0 jedes Teilstueck schrieb.
        assertTrue(Trainingswerte.fassungAusPuls(1, false) > Trainingswerte.FASSUNG_UHR)
        assertTrue(Trainingswerte.fassungAusPuls(1, false) > 2L)
        assertTrue(Trainingswerte.lohntSich(2L, Trainingswerte.fassungAusPuls(1, false)))
        assertTrue(Trainingswerte.lohntSich(Trainingswerte.FASSUNG_UHR, Trainingswerte.fassungAusPuls(1, false)))
    }

    @Test
    fun nachholenNurWoEtwasFehlt() {
        // Fehlt der Eintrag, wird geschrieben; bei gleichem Stand nicht noch einmal.
        assertTrue(Trainingswerte.lohntSich(null, Trainingswerte.fassungAusPuls(100, false)))
        assertFalse(Trainingswerte.lohntSich(Trainingswerte.fassungAusPuls(100, false), Trainingswerte.fassungAusPuls(100, false)))
    }

    @Test
    fun dieWaageSchlaegtDasEingestellteGewicht() {
        // Im Hintergrund ohne Waage gerechnet, im Detail mit: bei gleichem Puls
        // ersetzt die Rechnung mit der Waage, umgekehrt nicht.
        val ohne = Trainingswerte.fassungAusPuls(360, false)
        val mit = Trainingswerte.fassungAusPuls(360, true)
        assertTrue(Trainingswerte.lohntSich(ohne, mit))
        assertFalse(Trainingswerte.lohntSich(mit, ohne))
        // Mehr Puls ersetzt trotzdem - auch ohne Waage.
        assertTrue(Trainingswerte.lohntSich(mit, Trainingswerte.fassungAusPuls(361, false)))
        // Jede Fassung bis 0.42 (2 + Pulswerte) wird einmal neu gerechnet.
        assertTrue(Trainingswerte.lohntSich(2L + 360, ohne))
    }
}
