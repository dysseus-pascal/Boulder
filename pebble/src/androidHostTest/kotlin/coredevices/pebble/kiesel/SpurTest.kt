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
