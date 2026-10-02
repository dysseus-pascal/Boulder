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
        val ganz = Trainingswerte.fassungAusPuls(360)
        val stueck = Trainingswerte.fassungAusPuls(40)
        assertTrue(stueck < ganz)
        assertFalse(Trainingswerte.lohntSich(ganz, stueck))
        // Kommt dagegen mehr Puls dazu, ersetzt die neue Rechnung die alte.
        assertTrue(Trainingswerte.lohntSich(stueck, ganz))
    }

    @Test
    fun pulsSchlaegtUhrUndAlteFassung() {
        // Schon ein einziger Pulswert liegt ueber der Uhr (1) und ueber der festen
        // Fassung 2, mit der bis 0.40.0 jedes Teilstueck schrieb.
        assertTrue(Trainingswerte.fassungAusPuls(1) > Trainingswerte.FASSUNG_UHR)
        assertTrue(Trainingswerte.fassungAusPuls(1) > 2L)
        assertTrue(Trainingswerte.lohntSich(2L, Trainingswerte.fassungAusPuls(1)))
        assertTrue(Trainingswerte.lohntSich(Trainingswerte.FASSUNG_UHR, Trainingswerte.fassungAusPuls(1)))
    }

    @Test
    fun nachholenNurWoEtwasFehlt() {
        // Fehlt der Eintrag, wird geschrieben; bei gleichem Stand nicht noch einmal.
        assertTrue(Trainingswerte.lohntSich(null, Trainingswerte.fassungAusPuls(100)))
        assertFalse(Trainingswerte.lohntSich(Trainingswerte.fassungAusPuls(100), Trainingswerte.fassungAusPuls(100)))
    }
}
