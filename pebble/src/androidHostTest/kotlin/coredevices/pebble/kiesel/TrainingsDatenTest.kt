package coredevices.pebble.kiesel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/**
 * Die Regeln, mit denen TrainingsDaten seit 0.47 weniger liest: das 3-h-Fenster
 * des Widgets vor der Streckenrechnung und die zwei gemerkten Spuren.
 */
class TrainingsDatenTest {

    @Test
    fun `das Widget zeigt ein Training drei Stunden lang`() {
        // Die Frist selbst, festgenagelt - Lesen und Zeichnen nehmen beide sie.
        assertEquals(Duration.ofHours(3), TrainingsDaten.WIDGET_FRIST)
        val jetzt = Instant.ofEpochSecond(1_700_000_000)
        val drei = Duration.ofHours(3)
        // Knapp darunter noch, ab genau drei Stunden nicht mehr - auch eine Nanosekunde zaehlt.
        assertTrue(TrainingsDaten.zeigtImWidget(jetzt.minus(drei).plusNanos(1), jetzt))
        assertTrue(TrainingsDaten.zeigtImWidget(jetzt.minus(drei).plusSeconds(1), jetzt))
        assertFalse(TrainingsDaten.zeigtImWidget(jetzt.minus(drei), jetzt))
        assertFalse(TrainingsDaten.zeigtImWidget(jetzt.minus(drei).minusSeconds(1), jetzt))
        // Ein Ende in der Zukunft (falsche Uhr der Quelle) ist nicht zu alt.
        assertTrue(TrainingsDaten.zeigtImWidget(jetzt.plus(Duration.ofHours(2)), jetzt))
    }

    @Test
    fun `das Fenster vor der Strecke laesst genau weg, was das Widget wegliesse`() {
        val jetzt = Instant.ofEpochSecond(1_700_000_000)
        val zufall = Random(3)
        val dauern = List(5000) { Duration.ofMillis(zufall.nextLong(-3_600_000, 26 * 3_600_000)) }
        for (d in dauern) {
            val ende = jetzt.minus(d)
            // Das Widget prueft einen Augenblick spaeter: was beim Lesen wegfaellt, faellt dort erst recht.
            val spaeter = jetzt.plusMillis(zufall.nextLong(0, 5000))
            if (TrainingsDaten.zuAlt(ende, jetzt, TrainingsDaten.WIDGET_FRIST)) assertFalse("spaeter $d", TrainingsDaten.zeigtImWidget(ende, spaeter))
        }
    }

    @Test
    fun `ohne Grenze ist nichts zu alt`() {
        val jetzt = Instant.ofEpochSecond(1_700_000_000)
        assertFalse(TrainingsDaten.zuAlt(jetzt.minus(Duration.ofDays(400)), jetzt, null))
        assertTrue(TrainingsDaten.zuAlt(jetzt.minus(Duration.ofHours(3)), jetzt, Duration.ofHours(3)))
    }

    @Test
    fun `die Rueckkehr aus einem fremden Detail trifft die Vorschau`() {
        // Der Rundgang des Pruefers: Sport (oberstes), Detail eines aelteren, zurueck.
        val z = Zuletzt<String, String>(2)
        z.lege("oben", "Spur oben")
        z.lege("alt", "Spur alt")
        assertEquals("Spur oben", z.hole("oben"))
        // Noch ein Detail: es verdraengt das aeltere, nicht die eben benutzte Vorschau.
        z.lege("drittes", "Spur drittes")
        assertEquals("Spur oben", z.hole("oben"))
        assertNull(z.hole("alt"))
        assertEquals("Spur drittes", z.hole("drittes"))
    }

    @Test
    fun `derselbe Schluessel ersetzt und belegt keinen zweiten Platz`() {
        val z = Zuletzt<String, Int>(2)
        z.lege("a", 1)
        z.lege("b", 2)
        z.lege("a", 3)
        assertEquals(3, z.hole("a"))
        assertEquals(2, z.hole("b"))
        assertNull(z.hole("c"))
    }
}
