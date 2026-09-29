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
    fun bewegtOhneHalt() {
        // 30 s fahren, 60 s an der Ampel (derselbe Punkt), 30 s fahren.
        val fahrt = gerade(30)
        val halt = (1..60).map { fahrt.last().copy(zeit = fahrt.last().zeit + it) }
        val weiter = gerade(30).drop(1).map { it.copy(zeit = it.zeit + 90, lon = it.lon + 30 * 0.000131) }
        val s = Trainingsanalyse.strecke(fahrt + halt + weiter)
        val b = Trainingsanalyse.bewegt(s)
        assertTrue("bewegt $b", b in 55..70)
    }

    @Test
    fun sprungZaehltNichtAlsWeg() {
        // Nach 30 s ein Kilometer Versatz nach Norden - verlorener Empfang.
        val punkte = gerade(60).mapIndexed { i, p -> if (i > 30) p.copy(lat = p.lat + 0.009) else p }
        val s = Trainingsanalyse.strecke(punkte)
        assertTrue(s[31].nachSprung)
        assertTrue(s.count { it.nachSprung } == 1)
        assertEquals(Spur.laenge(punkte), s.last().meter, 0.01)
    }

    @Test
    fun kalorienAusDemPuls() {
        // Eine Stunde bei 130: rund 730 kcal fuer einen Mann von 80 kg und
        // 40 Jahren, deutlich weniger fuer eine Frau von 60 kg.
        val puls = (0..360).map { Pulspunkt(it * 10L, 130) }
        val er = Trainingsanalyse.kalorien(puls, 80.0, 40, Trainingsanalyse.Geschlecht.Mann)!!
        val sie = Trainingsanalyse.kalorien(puls, 60.0, 40, Trainingsanalyse.Geschlecht.Frau)!!
        assertEquals(730.0, er, 15.0)
        assertTrue("sie $sie", sie in 400.0..560.0)
        assertTrue(Trainingsanalyse.kalorien(puls, 0.0, 40, Trainingsanalyse.Geschlecht.Mann) == null)
    }

    @Test
    fun einbruchFaelltWeg() {
        // Alle 10 s ein Punkt um 130; nach 60 s dreissig Sekunden lang 90,
        // dann wieder 130 - der Sensor am Lenker.
        val puls = (0..15).map { i ->
            Pulspunkt(i * 10L, if (i in 6..8) 90L else 130L)
        }
        val e = Trainingsanalyse.entstoert(puls)
        assertEquals(13, e.size)
        assertTrue(e.none { it.bpm == 90L })
        assertEquals(130L, e.maxOf { it.bpm })
        assertEquals(130L, e.minOf { it.bpm })
    }

    @Test
    fun echterAbfallBleibt() {
        // An der Ampel: von 140 auf 100 und dort geblieben.
        val puls = (0..15).map { i -> Pulspunkt(i * 10L, if (i < 6) 140L else 100L) }
        assertEquals(puls, Trainingsanalyse.entstoert(puls))
    }

    @Test
    fun anstiegAmAnfangBleibt() {
        // Der Puls kommt in 30 s von 85 auf 125 und ein Zacken faellt kurz
        // auf 92: der Anstieg ist echt, der Zacken nicht.
        val puls = listOf(85L, 110L, 125L, 92L, 128L, 130L, 131L).mapIndexed { i, b -> Pulspunkt(i * 10L, b) }
        val e = Trainingsanalyse.entstoert(puls)
        assertEquals(listOf(85L, 110L, 125L, 128L, 130L, 131L), e.map { it.bpm })
    }

    @Test
    fun ausreisserMachtKeineSpitze() {
        // Ein einzelner Punkt 40 m voraus: er zaehlte 80 m in zwei Sekunden
        // und war die Spitze des Tages.
        val punkte = gerade(60).mapIndexed { i, p -> if (i == 30) p.copy(lon = p.lon + 4 * 0.000131) else p }
        val s = Trainingsanalyse.strecke(punkte)
        assertEquals(60, s.size)
        assertTrue("Spitze ${s.maxOf { it.tempo }}", s.maxOf { it.tempo } < 12.0)
        assertEquals(Spur.laenge(gerade(60)), s.last().meter, 1.0)
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
