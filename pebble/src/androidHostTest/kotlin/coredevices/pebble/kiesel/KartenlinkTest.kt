package coredevices.pebble.kiesel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Linkzerlegung ohne Telefon pruefen.
 *
 * GOOGLE SCHREIBT SEINE LINKS IN EINEM HALBEN DUTZEND FORMEN, und sie haben
 * sich ueber die Jahre geaendert. Ein Fehler darin faellt erst im Auto auf -
 * hier faellt er vorher auf.
 */
class KartenlinkTest {

    @Test
    fun `Ziel aus der Richtungsform`() {
        val z = Kartenlink.zerlege(
            "https://www.google.com/maps/dir/?api=1&destination=47.3769,8.5417"
        )!!
        assertEquals(47.3769, z.lat!!, 0.0001)
        assertEquals(8.5417, z.lon!!, 0.0001)
    }

    @Test
    fun `Name statt Koordinate`() {
        val z = Kartenlink.zerlege(
            "https://www.google.com/maps/dir/?api=1&destination=Bahnhof+Bern"
        )!!
        assertNull(z.lat)
        assertEquals("Bahnhof Bern", z.text)
        assertTrue(z.brauchbar)
    }

    @Test
    fun `Ortsform mit Name und Kartenmitte`() {
        val z = Kartenlink.zerlege(
            "https://www.google.com/maps/place/Kunsthaus/@47.3703,8.5486,17z/data=!3m1"
        )!!
        assertEquals("Kunsthaus", z.text)
        assertEquals(47.3703, z.lat!!, 0.0001)
    }

    @Test
    fun `nur ein Kartenausschnitt`() {
        val z = Kartenlink.zerlege("https://www.google.com/maps/@46.9481,7.4474,15z")!!
        assertEquals(46.9481, z.lat!!, 0.0001)
        assertNull(z.text)
    }

    @Test
    fun `die alte daddr-Form`() {
        val z = Kartenlink.zerlege("https://maps.google.com/?daddr=46.5,7.5")!!
        assertEquals(46.5, z.lat!!, 0.0001)
    }

    @Test
    fun `geo mit Koordinate`() {
        val z = Kartenlink.zerlege("geo:47.05,8.31")!!
        assertEquals(47.05, z.lat!!, 0.0001)
        assertEquals(8.31, z.lon!!, 0.0001)
    }

    @Test
    fun `geo null null ist eine Suche`() {
        val z = Kartenlink.zerlege("geo:0,0?q=Rathaus")!!
        assertNull(z.lat)
        assertEquals("Rathaus", z.text)
    }

    @Test
    fun `geo mit Koordinate in der Abfrage`() {
        val z = Kartenlink.zerlege("geo:0,0?q=47.1,8.2")!!
        assertEquals(47.1, z.lat!!, 0.0001)
        assertNull(z.text)
    }

    @Test
    fun `unbrauchbare Links geben nichts`() {
        assertNull(Kartenlink.zerlege(""))
        assertNull(Kartenlink.zerlege("https://www.google.com/search?q=wetter"))
    }

    @Test
    fun `unmoegliche Koordinaten sind ein Name`() {
        val z = Kartenlink.zerlege("https://maps.google.com/?q=999,999")!!
        assertEquals("999,999", z.text)
        assertNull(z.lat)
    }

    @Test
    fun `Kurzlinks werden erkannt`() {
        assertTrue(Kartenlink.istKurzlink("https://maps.app.goo.gl/abc123"))
        assertTrue(Kartenlink.istKurzlink("https://goo.gl/maps/xyz"))
        assertTrue(!Kartenlink.istKurzlink("https://www.google.com/maps/@1.0,2.0,15z"))
    }
}
