package coredevices.pebble.kiesel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Die Streckenrechnung ohne Telefon pruefen.
 *
 * DIE ZAHLEN UNTER EINER KARTE SIND NICHT NACHZURECHNEN. Steht da "8,2 km",
 * glaubt man es; eine Formel, die zehn Prozent danebenliegt, faellt nie auf.
 * Deshalb steht hier eine Strecke, deren Laenge man von Hand kennt.
 *
 * DIE PUNKTE SIND ERFUNDEN. Runde Gradzahlen im Nichts vor Afrika und eine
 * Handvoll Meter daneben - keine echte Runde, weil auch ein Test kein Ort
 * ist, an dem die Wege eines Menschen liegen muessen.
 */
class SpurTest {

    private fun p(zeit: Long, lat: Double, lon: Double, hoehe: Double? = null) =
        Spur.Punkt(zeit, lat, lon, hoehe, 5f)

    @Test
    fun `ein Grad Breite sind gut hundertelf Kilometer`() {
        // Der Meridianbogen ist bekannt: 1 Grad Breite = 111,19 km. Weicht die
        // Formel hier ab, weicht sie ueberall ab.
        val d = Spur.abstand(p(0, 0.0, 0.0), p(0, 1.0, 0.0))
        assertEquals(111195.0, d, 200.0)
    }

    @Test
    fun `derselbe Punkt ist null Meter weit`() {
        assertEquals(0.0, Spur.abstand(p(0, 10.0, 20.0), p(0, 10.0, 20.0)), 0.001)
    }

    @Test
    fun `die Laenge summiert die Abschnitte`() {
        val punkte = listOf(
            p(0, 0.0, 0.0),
            p(3, 0.001, 0.0),
            p(6, 0.002, 0.0),
        )
        // Zweimal 0,001 Grad Breite, also rund 222 Meter.
        assertEquals(222.4, Spur.laenge(punkte), 2.0)
    }

    @Test
    fun `ein Sprung zaehlt nicht mit`() {
        // Verlorener und wiedergefundener Empfang: der Punkt in der Mitte
        // liegt Kilometer entfernt. Beide Abschnitte dorthin sind ueber der
        // Schwelle und fallen weg - die Summe ist null, nicht zwanzig
        // Kilometer.
        val punkte = listOf(
            p(0, 0.0, 0.0),
            p(3, 0.1, 0.0),
            p(6, 0.0, 0.0),
        )
        assertEquals(0.0, Spur.laenge(punkte), 0.001)
    }

    @Test
    fun `Zittern im Stehen ist kein Weg`() {
        // Neun Minuten am selben Ort, alle drei Sekunden ein Punkt, der bis zu
        // fuenfzehn Meter daneben liegt - bei zwanzig Metern Genauigkeit.
        val punkte = (0 until 180).map { i ->
            val versatz = if (i % 2 == 0) 0.0 else 0.000135
            Spur.Punkt(i * 3L, 47.0 + versatz, 8.0, null, 20f)
        }
        assertEquals(0.0, Spur.laenge(punkte), 0.001)
    }

    @Test
    fun `langsames Gehen summiert sich trotzdem`() {
        // Vier Meter je Punkt bei zehn Metern Genauigkeit: einzeln unter der
        // Schwelle, zusammen 400 Meter.
        val punkte = (0..100).map { i -> Spur.Punkt(i * 3L, 47.0 + i * 0.000036, 8.0, null, 10f) }
        assertEquals(400.0, Spur.laenge(punkte), 15.0)
    }

    @Test
    fun `eine Kehre bleibt ein Weg`() {
        // Hin und zurueck auf derselben Strasse, 100 m weit: kein Ausreisser,
        // sondern eine Wende - und 200 m Weg.
        val hin = (0..10).map { i -> Spur.Punkt(i * 3L, 47.0, 8.0 + i * 0.000131, null, 5f) }
        val zurueck = (1..10).map { i -> Spur.Punkt(30 + i * 3L, 47.0, 8.0 + (10 - i) * 0.000131, null, 5f) }
        assertEquals(200.0, Spur.laenge(hin + zurueck), 6.0)
    }

    @Test
    fun `kaputte Zeilen kosten nur sich selbst`() {
        // Die letzte Zeile abgeschnitten (App starb beim Schreiben), eine ohne
        // Zeit: uebrig bleibt genau der eine ganze Punkt.
        val punkte = Spur.ausZeilen(listOf(
            """{"t":1,"lat":47.0,"lon":8.0,"g":5}""",
            "",
            """{"lat":47.0,"lon":8.0}""",
            """{"t":2,"lat":47.""",
        ))
        assertEquals(1, punkte.size)
        assertEquals(1L, punkte[0].zeit)
    }

    @Test
    fun `eine einzelne Messung hat keine Laenge`() {
        assertEquals(0.0, Spur.laenge(listOf(p(0, 1.0, 1.0))), 0.001)
        assertEquals(0.0, Spur.laenge(emptyList()), 0.001)
    }

    /**
     * Punkte gut hundert Meter auseinander: weiter als das Fenster der
     * Hoehenglaettung (Trainingsanalyse.hoehen), so zaehlt jede Hoehe fuer sich.
     */
    private fun weit(i: Int, hoehe: Double?) = Spur.Punkt(i * 30L, i * 0.001, 0.0, hoehe, 5f)

    @Test
    fun `Hoehenmeter zaehlen nur aufwaerts`() {
        val punkte = listOf(weit(0, 400.0), weit(1, 450.0), weit(2, 400.0))
        assertEquals(50.0, Trainingsanalyse.aufstieg(punkte), 0.001)
    }

    @Test
    fun `Rauschen unter der Schwelle sammelt nichts`() {
        // Im Stehen schwankt die GPS-Hoehe um ein, zwei Meter. Ohne Schwelle
        // haette dieser Spaziergang im Sitzen sechs Hoehenmeter.
        val punkte = listOf(400.0, 402.0, 400.0, 402.0, 400.0).mapIndexed { i, h -> weit(i, h) }
        assertEquals(0.0, Trainingsanalyse.aufstieg(punkte), 0.001)
    }

    @Test
    fun `ein langer Anstieg zaehlt ganz`() {
        val punkte = (0..10).map { weit(it, 400.0 + it * 10.0) }
        assertEquals(100.0, Trainingsanalyse.aufstieg(punkte), 0.001)
    }

    @Test
    fun `Punkte ohne Hoehe stoeren die Rechnung nicht`() {
        val punkte = listOf(weit(0, 400.0), weit(1, null), weit(2, 410.0))
        assertEquals(10.0, Trainingsanalyse.aufstieg(punkte), 0.001)
    }

    @Test
    fun `GPS-Hoehe, die im Stehen wandert, ist kein Anstieg`() {
        // Zehn Minuten Rast, alle drei Sekunden eine Hoehe, die um vier Meter
        // auf und ab wandert - ohne Weg. Roh mit Schwelle waeren das je
        // Welle vier Meter, geglaettet ueber die Rast bleibt nichts.
        val punkte = (0 until 200).map { i -> Spur.Punkt(i * 3L, 47.0, 8.0, 400.0 + 4 * Math.sin(i / 10.0), 5f) }
        assertEquals(0.0, Trainingsanalyse.aufstieg(punkte), 0.001)
    }

    @Test
    fun einSprungIstKeinAnstieg() {
        // Erster Fix an einem alten Ort auf Meereshoehe, dann 540 m hoch und
        // 9000 km weiter - das ist kein Aufstieg, sondern ein Sprung.
        val punkte = listOf(
            Spur.Punkt(1_000_000, 37.4, -122.0, 0.0, 5f),
            Spur.Punkt(1_000_010, 46.948, 7.4474, 540.0, 5f),
            // Gut hundert Meter weiter: ausserhalb des Glaettungsfensters.
            Spur.Punkt(1_000_040, 46.949, 7.4474, 545.0, 5f),
        )
        org.junit.Assert.assertEquals(5.0, Trainingsanalyse.aufstieg(punkte), 0.001)
    }

    @Test
    fun nachDemSprungZaehltDieErsteHoeheAuchSpaeter() {
        // Tunnel: der erste Fix danach kommt ohne Hoehe, der zweite 40 m hoeher -
        // ein anderer Ort, kein Anstieg. Erst die 10 m danach sind gestiegen.
        val punkte = listOf(
            weit(0, 400.0), weit(1, 400.0),
            Spur.Punkt(90, 0.005, 0.0, null, 5f),       // gut 400 m weiter: ein Sprung
            Spur.Punkt(120, 0.006, 0.0, 440.0, 5f),
            Spur.Punkt(150, 0.007, 0.0, 450.0, 5f),
        )
        assertEquals(10.0, Trainingsanalyse.aufstieg(punkte), 0.001)
    }
}

class SitzungsendeTest {
    private val a = java.time.Instant.ofEpochSecond(1_000_000)
    private fun p(t: Long) = Spur.Punkt(t, 47.0, 8.0, null, 5f)

    @Test
    fun nach_einer_pause_reicht_die_sitzung_bis_zum_letzten_punkt() {
        // 20 min aktiv, aber die Spur lief 35 min (15 min Pause).
        val ende = Kieselsport.sitzungsEnde(a, 1200, listOf(p(1_000_000), p(1_002_100)), a.plusSeconds(99_999))
        org.junit.Assert.assertEquals(1_002_101L, ende.epochSecond)
    }

    @Test
    fun ohne_spur_zaehlt_die_aktive_dauer() {
        org.junit.Assert.assertEquals(1_001_200L, Kieselsport.sitzungsEnde(a, 1200, emptyList(), a.plusSeconds(99_999)).epochSecond)
    }

    @Test
    fun strecke_und_hoehe_nur_aus_der_sitzung() {
        // Der erste Fix kam zehn Sekunden vor dem Start - er zaehlt weder zur
        // Route noch zur Strecke; der Punkt genau am Ende auch nicht.
        val punkte = listOf(p(999_990), p(1_000_010), p(1_000_500), p(1_000_600))
        val drin = Spur.inSitzung(punkte, a, a.plusSeconds(600))
        org.junit.Assert.assertEquals(listOf(1_000_010L, 1_000_500L), drin.map { it.zeit })
        org.junit.Assert.assertEquals(
            Kieselsport.routeAus(punkte, a, a.plusSeconds(600))!!.route.map { it.time.epochSecond },
            drin.map { it.zeit },
        )
    }

    @Test
    fun route_ohne_punkte_vor_dem_beginn() {
        val ende = Kieselsport.sitzungsEnde(a, 60, listOf(p(999_990), p(1_000_010), p(1_000_500)), a.plusSeconds(99_999))
        val route = Kieselsport.routeAus(listOf(p(999_990), p(1_000_010), p(1_000_500)), a, ende)!!
        org.junit.Assert.assertEquals(listOf(1_000_010L, 1_000_500L), route.route.map { it.time.epochSecond })
    }

    @Test
    fun die_letzte_pause_beendet_die_spur() {
        // 60 min aktiv, 20 min Pause, gespeichert bei 80 min. Die Zusammenfassung
        // kam erst nach der Heimfahrt - die Punkte im Auto gehoeren nicht dazu.
        val plan = Trainingspausen.Plan(listOf(Trainingspausen.Pause(1_001_800, 1_003_000)), 1_004_800)
        val punkte = listOf(p(1_000_000), p(1_004_790), p(1_005_400), p(1_007_000))
        val ende = Kieselsport.sitzungsEnde(a, 3600, punkte, a.plusSeconds(9_000), plan)
        // Beginn + 60 min + 20 min Pause; der letzte Punkt davor liegt darin.
        org.junit.Assert.assertEquals(1_004_800L, ende.epochSecond)
    }

    @Test
    fun ohne_spur_reicht_die_sitzung_ueber_die_pausen() {
        // Kraft ohne GPS: 50 min aktiv, 10 min Pause - die Sitzung dauert 60 min.
        val plan = Trainingspausen.Plan(listOf(Trainingspausen.Pause(1_001_200, 1_001_800)), 1_003_600)
        org.junit.Assert.assertEquals(1_003_600L, Kieselsport.sitzungsEnde(a, 3000, emptyList(), a.plusSeconds(9_000), plan).epochSecond)
    }

    @Test
    fun ohne_bekannte_pause_hoechstens_zwanzig_minuten_mehr() {
        // Weder Pause noch Ende kamen an: die Spur lief bis zur Zusammenfassung zwei Stunden spaeter.
        val punkte = (0..120).map { p(1_000_000L + it * 60) }
        val ende = Kieselsport.sitzungsEnde(a, 1200, punkte, a.plusSeconds(99_999))
        org.junit.Assert.assertTrue(ende.epochSecond <= 1_001_200L + Kieselsport.PUFFER_OHNE_PAUSE_S)
        org.junit.Assert.assertEquals(1_002_341L, ende.epochSecond)
        // Beim Nachtragen gibt es keine Dauer der Uhr - dort zaehlt die ganze Spur.
        org.junit.Assert.assertEquals(1_007_201L, Kieselsport.sitzungsEnde(a, 60, punkte, a.plusSeconds(99_999), ohnePause = null).epochSecond)
    }
}
