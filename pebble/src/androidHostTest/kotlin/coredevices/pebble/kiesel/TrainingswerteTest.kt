package coredevices.pebble.kiesel

import org.junit.Assert.assertEquals
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
        // Die Akte weist eine Probe ausserhalb der Sitzung ab - und mit ihr den ganzen Satz.
        val proben = Trainingswerte.tempoProben(gerade(120), 1_000_030L, 1_000_060L)
        assertTrue(proben.isNotEmpty())
        assertTrue(proben.all { it.first in 1_000_030L until 1_000_060L })
    }

    @Test
    fun ohneSpurKeinTempo() {
        assertTrue(Trainingswerte.tempoProben(gerade(0), 0, Long.MAX_VALUE).isEmpty())
    }
}
