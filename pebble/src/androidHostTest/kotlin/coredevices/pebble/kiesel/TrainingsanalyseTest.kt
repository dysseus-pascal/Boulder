package coredevices.pebble.kiesel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingsanalyseTest {

    /** Eine gerade Strecke nach Osten, ein Punkt je Sekunde, rund 10 m/s. */
    private fun gerade(sekunden: Int, hoehe: Double? = 400.0): List<Spur.Punkt> =
        (0..sekunden).map { s ->
            Spur.Punkt(1_000_000L + s, 47.0, 7.0 + s * 0.000131, hoehe?.plus(s * 0.5), 5f)
        }

    @Test
    fun zonenAusDemMaximalpuls() {
        assertEquals(0, Trainingsanalyse.zone(90, 190))
        assertEquals(1, Trainingsanalyse.zone(95, 190))
        assertEquals(3, Trainingsanalyse.zone(140, 190))
        assertEquals(5, Trainingsanalyse.zone(171, 190))
    }

    @Test
    fun zonenSekundenGeltenBisZumNaechstenPunkt() {
        val puls = listOf(Pulspunkt(0, 100), Pulspunkt(10, 100), Pulspunkt(20, 150), Pulspunkt(600, 150))
        val z = Trainingsanalyse.zonenSekunden(puls, 190)
        assertEquals(20L, z[1])          // zweimal 10 s in Zone 1
        // Das Loch von 580 s zaehlt hoechstens eine Minute, dazu 10 s Rest
        assertEquals(70L, z[3])
    }

    @Test
    fun streckeUndTempo() {
        val s = Trainingsanalyse.strecke(gerade(60))
        assertEquals(61, s.size)
        assertTrue(s.last().meter > 550 && s.last().meter < 650)
        assertTrue(s[30].tempo > 9.0 && s[30].tempo < 11.0)
    }

    @Test
    fun pulsUeberStreckeLiegtZwischenDenPunkten() {
        val s = Trainingsanalyse.strecke(gerade(60))
        val p = Trainingsanalyse.pulsUeberStrecke(listOf(Pulspunkt(30, 120)), s)
        assertEquals(1, p.size)
        assertTrue(p[0].meter > s[29].meter && p[0].meter < s[31].meter)
    }

    @Test
    fun kilometerMitRest() {
        val s = Trainingsanalyse.strecke(gerade(250))   // rund 2,5 km
        val km = Trainingsanalyse.kilometer(s, emptyList())
        assertEquals(3, km.size)
        assertEquals(1, km[0].nummer)
        assertTrue(km[2].meter < 900)
        assertTrue(km[0].aufstieg > 40)   // 0,5 m je Sekunde, rund 100 s je km
    }

    @Test
    fun hoeheNurWennVorhanden() {
        assertTrue(Trainingsanalyse.hoeheUeberStrecke(Trainingsanalyse.strecke(gerade(20, null))).isEmpty())
        assertEquals(21, Trainingsanalyse.hoeheUeberStrecke(Trainingsanalyse.strecke(gerade(20))).size)
    }
}
