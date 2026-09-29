package coredevices.pebble.kiesel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Die Antwort des Suchdienstes zerlegen - ohne Netz und ohne Telefon.
 *
 * NOMINATIM SCHICKT DIE ZAHLEN ALS ZEICHENKETTEN. Wer sie als Zahlen liest,
 * bekommt auf manchen Fassungen eine Null und navigiert in den Golf von
 * Guinea - der Punkt null Grad Nord, null Grad Ost. Diese Pruefung steht
 * genau dafuer da.
 */
class OrtsucheTest {

    @Test
    fun `Zahlen kommen als Zeichenketten`() {
        val json = """[{"lat":"47.3768866","lon":"8.541694","display_name":"Zürich"}]"""
        val (lat, lon) = Ortsuche.ausAntwort(json)!!
        assertEquals(47.3768866, lat, 0.000001)
        assertEquals(8.541694, lon, 0.000001)
    }

    @Test
    fun `der erste Treffer zaehlt`() {
        val json = """[{"lat":"1.0","lon":"2.0"},{"lat":"9.0","lon":"9.0"}]"""
        assertEquals(1.0, Ortsuche.ausAntwort(json)!!.first, 0.0001)
    }

    @Test
    fun `nichts gefunden ist kein Ort`() {
        assertNull(Ortsuche.ausAntwort("[]"))
    }

    @Test
    fun `Unsinn ist kein Ort`() {
        assertNull(Ortsuche.ausAntwort("kein json"))
        assertNull(Ortsuche.ausAntwort(""))
        assertNull(Ortsuche.ausAntwort("""[{"lat":"oben","lon":"links"}]"""))
    }

    @Test
    fun `unmoegliche Koordinaten werden verworfen`() {
        assertNull(Ortsuche.ausAntwort("""[{"lat":"99.9","lon":"8.5"}]"""))
    }
}
