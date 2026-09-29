package coredevices.pebble.kiesel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `eine einzelne Messung hat keine Laenge`() {
        assertEquals(0.0, Spur.laenge(listOf(p(0, 1.0, 1.0))), 0.001)
        assertEquals(0.0, Spur.laenge(emptyList()), 0.001)
    }

    @Test
    fun `Hoehenmeter zaehlen nur aufwaerts`() {
        val punkte = listOf(
            p(0, 0.0, 0.0, 400.0),
            p(3, 0.0, 0.0, 450.0),
            p(6, 0.0, 0.0, 400.0),
        )
        assertEquals(50.0, Spur.hoehenmeter(punkte), 0.001)
    }

    @Test
    fun `Rauschen unter der Schwelle sammelt nichts`() {
        // Im Stehen schwankt die GPS-Hoehe um ein, zwei Meter. Ohne Schwelle
        // haette dieser Spaziergang im Sitzen sechs Hoehenmeter.
        val punkte = listOf(
            p(0, 0.0, 0.0, 400.0),
            p(3, 0.0, 0.0, 402.0),
            p(6, 0.0, 0.0, 400.0),
            p(9, 0.0, 0.0, 402.0),
            p(12, 0.0, 0.0, 400.0),
        )
        assertEquals(0.0, Spur.hoehenmeter(punkte), 0.001)
    }

    @Test
    fun `ein langer Anstieg zaehlt ganz`() {
        val punkte = (0..10).map { p(it * 3L, 0.0, 0.0, 400.0 + it * 10.0) }
        assertEquals(100.0, Spur.hoehenmeter(punkte), 0.001)
    }

    @Test
    fun `Punkte ohne Hoehe stoeren die Rechnung nicht`() {
        val punkte = listOf(
            p(0, 0.0, 0.0, 400.0),
            p(3, 0.0, 0.0, null),
            p(6, 0.0, 0.0, 410.0),
        )
        assertEquals(10.0, Spur.hoehenmeter(punkte), 0.001)
    }

    @Test
    fun `GPX traegt Ort Hoehe und Zeit`() {
        val gpx = Spur.alsGpx(listOf(p(1758400000L, 12.5, 34.25, 500.0)), "Laufen")
        assertTrue(gpx.contains("<gpx"))
        assertTrue(gpx.contains("<name>Laufen</name>"))
        assertTrue(gpx.contains("lat=" + '"' + "12.5" + '"'))
        assertTrue(gpx.contains("lon=" + '"' + "34.25" + '"'))
        assertTrue(gpx.contains("<ele>500.0</ele>"))
        assertTrue(gpx.contains("<time>2025-09-20T20:26:40Z</time>"))
        assertTrue(gpx.trimEnd().endsWith("</gpx>"))
    }

    @Test
    fun einSprungIstKeinAnstieg() {
        // Erster Fix an einem alten Ort auf Meereshoehe, dann 540 m hoch und
        // 9000 km weiter - das ist kein Aufstieg, sondern ein Sprung.
        val punkte = listOf(
            Spur.Punkt(1_000_000, 37.4, -122.0, 0.0, 5f),
            Spur.Punkt(1_000_010, 46.948, 7.4474, 540.0, 5f),
            Spur.Punkt(1_000_020, 46.9481, 7.4476, 545.0, 5f),
        )
        org.junit.Assert.assertEquals(5.0, Spur.hoehenmeter(punkte), 0.001)
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
    fun route_ohne_punkte_vor_dem_beginn() {
        val ende = Kieselsport.sitzungsEnde(a, 60, listOf(p(999_990), p(1_000_010), p(1_000_500)), a.plusSeconds(99_999))
        val route = Kieselsport.routeAus(listOf(p(999_990), p(1_000_010), p(1_000_500)), a, ende)!!
        org.junit.Assert.assertEquals(listOf(1_000_010L, 1_000_500L), route.route.map { it.time.epochSecond })
    }
}
