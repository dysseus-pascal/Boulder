package coredevices.pebble.kiesel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

/**
 * Der Zeilenleser der Spur gegen den alten Weg ueber kotlinx.
 *
 * DIE SPUR IST DIE STRECKE: liest der neue Leser eine Zeile anders, steht
 * unter einem alten Training ploetzlich eine andere Zahl. Deshalb hier der
 * alte Leser woertlich und beide auf denselben Zeilen - ganzen, abgeschnittenen
 * und solchen, die so nie geschrieben wurden.
 *
 * DIE PUNKTE SIND ERFUNDEN (wie in SpurTest): ein Zufallsweg im Nichts.
 */
class SpurzeileTest {

    private companion object {
        /** Der Fingerabdruck fuer Spur.RECHNUNG = 1. */
        const val FINGER_ANZAHL = 301
        const val FINGER_METER = 2271.143653198798
    }

    /** Der Leser bis 0.46.0, unveraendert. */
    private fun alt(zeilen: List<String>): List<Spur.Punkt> = zeilen.mapNotNull { zeile ->
        if (zeile.isBlank()) return@mapNotNull null
        val o = try { Json.parseToJsonElement(zeile).jsonObject } catch (e: Exception) { return@mapNotNull null }
        Spur.Punkt(
            o["t"]?.jsonPrimitive?.longOrNull ?: 0L,
            o["lat"]?.jsonPrimitive?.doubleOrNull ?: Double.NaN,
            o["lon"]?.jsonPrimitive?.doubleOrNull ?: Double.NaN,
            o["h"]?.jsonPrimitive?.doubleOrNull,
            o["g"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: Float.NaN,
        )
    }.filter { it.zeit > 0 && !it.lat.isNaN() && !it.lon.isNaN() }

    /** Beide Leser auf einer Zeile: dasselbe Ergebnis, oder beide werfen. */
    private fun gleich(zeile: String) {
        val a = runCatching { alt(listOf(zeile)) }
        val n = runCatching { Spur.ausZeilen(listOf(zeile)) }
        assertEquals("wirft: $zeile", a.isFailure, n.isFailure)
        if (a.isSuccess) assertEquals("Zeile: $zeile", a.getOrThrow(), n.getOrThrow())
    }

    /** Eine Zeile, wie SpurDienst sie schreibt (Spur.haengeAn). */
    private fun geschrieben(t: Long, lat: Double, lon: Double, h: Double?, g: Float) = buildJsonObject {
        put("t", t)
        put("lat", lat)
        put("lon", lon)
        if (h != null) put("h", h)
        put("g", g)
    }.toString()

    private fun weg(n: Int, zufall: Random): List<String> {
        var lat = 0.5
        var lon = 0.5
        return (0 until n).map { i ->
            lat += zufall.nextDouble(-0.0002, 0.0002)
            lon += zufall.nextDouble(-0.0002, 0.0002)
            val h = if (i % 7 == 0) null else zufall.nextDouble(-20.0, 3000.0)
            geschrieben(1_000_000L + 3 * i, lat, lon, h, zufall.nextDouble(2.0, 60.0).toFloat())
        }
    }

    @Test
    fun `geschriebene Zeilen lesen beide gleich`() {
        val zeilen = weg(2000, Random(7))
        assertEquals(alt(zeilen), Spur.ausZeilen(zeilen))
        // Mit 2000 Zeilen ist die Laenge dieselbe Zahl, nicht nur eine aehnliche.
        assertEquals(Spur.laenge(alt(zeilen)), Spur.laenge(Spur.ausZeilen(zeilen)), 0.0)
    }

    @Test
    fun `jede abgeschnittene Zeile wie vorher`() {
        // Die App stirbt beim Schreiben: die letzte Zeile endet irgendwo.
        weg(40, Random(3)).forEach { z -> for (i in 0..z.length) gleich(z.substring(0, i)) }
    }

    @Test
    fun `fremde Schreibweisen wie vorher`() {
        listOf(
            // Wie Kiesel-Helper mit org.json schrieb, Leerzeichen, andere Reihenfolge, Zusatzfelder.
            """{"t":1000,"lat":1.0E-4,"lon":5.0E-5,"g":3.7899999618530273}""",
            """ { "lon" : 8.5 , "t" : 1000 , "lat" : 47.25 } """,
            "{\"t\":1000,\n\"lat\":1,\t\"lon\":2}",
            """{"x":"y","t":1000,"lat":1,"lon":2,"h":null,"g":true}""",
            """{"t":"1000","lat":"47.5","lon":"8.25","h":"500"}""",
            """{"t":1000,"lat":1,"lon":2,"extra":{"a":[1,2,{"b":null}]}}""",
            """{"t":1000,"lat":1,"lon":2,"t":2000}""",
            "{\"\\u0074\":1000,\"lat\":1,\"lon\":2}",
            """{"t":1000,"lat":1,"lon":2,"s":"a\"b\\c\/d\n\u00e9"}""",
            // Zahlen am Rand: Vorzeichen, Exponent, fuehrende Null, zu gross, NaN.
            """{"t":+1000,"lat":1,"lon":2}""",
            """{"t":1e3,"lat":1,"lon":2}""",
            """{"t":0123,"lat":1,"lon":2}""",
            """{"t":-5,"lat":1,"lon":2}""",
            """{"t":99999999999999999999,"lat":1,"lon":2}""",
            """{"t":1000,"lat":NaN,"lon":2}""",
            """{"t":1000,"lat":Infinity,"lon":-Infinity}""",
            """{"t":1000.5,"lat":1,"lon":2}""",
            """{"t":1000,"lat":1,"lon":2,"g":1e400}""",
            // Kaputtes, das kotlinx ablehnt.
            """{"t":1000,"lat":1,"lon":2,}""",
            """{t:1000,"lat":1,"lon":2}""",
            """{"t":1000,"lat":1,"lon":2}x""",
            """{"t":1000,"lat":1,"lon":2}{"t":1}""",
            """{"t":1000 "lat":1,"lon":2}""",
            """{"t":,"lat":1,"lon":2}""",
            """{"t":1000,"lat":1,"lon":"2\x"}""",
            """{"t":1000,"lat":1,"lon":"\u12"}""",
            """{"t":1000,"lat":1,"lon":"\u12g4"}""",
            """{"t":1000,"lat":1,"lon":2,"a":[1,]}""",
            """{"t":1000,"lat":1,"lon":2,"a":{"b"}}""",
            """{"t":1000,"lat":1,"lon":a\b}""",
            // Kein Objekt.
            "[1,2]", "5", "\"t\"", "null", "{}", "   ", "\t",
            // Ein Objekt in einem der Felder: kotlinx warf, die ganze Datei war leer.
            """{"t":{"a":1},"lat":1,"lon":2}""",
            """{"t":1000,"lat":[1],"lon":2}""",
            """{"t":{"a":1},"lat":1,"lon":2,"t":1000}""",
        ).forEach(::gleich)
    }

    @Test
    fun `Steuerzeichen wie vorher`() {
        // Nach einem Absturz stehen Nullbytes mitten in der Datei: kotlinx bricht an
        // jedem Zeichen unter 0x20 ab, das kein Leerraum ist - auch hinter einer Zahl.
        val steuer = listOf('\u0000', '\u0001', '\u0008', '\u000b', '\u000c', '\u001f', '\u007f', '\u00a0')
        for (c in steuer) listOf(
            "{\"t\":1000,\"lat\":1.5$c,\"lon\":2}",
            "{\"t\":1000,\"lat\":1.5,\"lon\":2,\"h\":5$c}",
            "{\"t\":1000,\"lat\":1.5,\"lon\":2,\"g\":3$c}",
            "{\"t\":1000$c,\"lat\":1.5,\"lon\":2}",
            "{\"t\":1000,\"lat\":${c}1.5,\"lon\":2}",
            "{\"t\":1000,$c\"lat\":1.5,\"lon\":2}",
            "{\"t\":1000,\"lat\":\"1.5$c\",\"lon\":2}",
            "{\"t\":1000,\"lat\":1.5,\"lon\":2}$c",
            "$c{\"t\":1000,\"lat\":1.5,\"lon\":2}",
            "{\"t\":1000,\"lat\":1.5,\"lon\":2,\"a\":[1$c]}",
            "{\"t\":1000,\"lat\":1.5,\"lon\":tr${c}ue}",
        ).forEach(::gleich)
    }

    @Test
    fun `Zufallszeilen aus JSON-Zeichen wie vorher`() {
        // Was kein Mensch schreibt, aber eine halb geschriebene Datei sein koennte.
        val zeichen = "{}[]\":,tlaongh0123456789.-+eE \\u\u0000\u0001\u001f\u000b"
        val zufall = Random(11)
        repeat(20_000) {
            val z = buildString { repeat(zufall.nextInt(1, 30)) { append(zeichen[zufall.nextInt(zeichen.length)]) } }
            gleich(z)
        }
    }

    @Test
    fun `veraenderte Zeilen wie vorher`() {
        // Ein Zeichen weg, dazu oder anders - an jeder Stelle einer echten Zeile.
        val zeichen = "{}[]\":,.-+eE0129 \t\\/ubnNaIxtlg\u0000\u0001\u001f"
        val zufall = Random(13)
        val zeilen = weg(200, zufall)
        repeat(50_000) {
            val z = StringBuilder(zeilen[zufall.nextInt(zeilen.size)])
            repeat(zufall.nextInt(1, 4)) {
                val i = zufall.nextInt(z.length + 1)
                when (zufall.nextInt(3)) {
                    0 -> if (i < z.length) z.deleteCharAt(i)
                    1 -> z.insert(i, zeichen[zufall.nextInt(zeichen.length)])
                    else -> if (i < z.length) z.setCharAt(i, zeichen[zufall.nextInt(zeichen.length)])
                }
            }
            gleich(z.toString())
        }
    }

    @Test
    fun `das Mass rechnet wie die Liste vorher`() {
        val punkte = Spur.ausZeilen(weg(500, Random(5)))
        val a = Instant.ofEpochSecond(1_000_300)
        val b = Instant.ofEpochSecond(1_001_200)
        val drin = Spur.inSitzung(punkte, a, b)
        val m = Spur.mass(drin)
        // Vorher: Spur.laenge(punkte) der beschnittenen Spur, ab zwei Punkten.
        assertEquals(drin.size, m.anzahl)
        assertEquals(Spur.laenge(drin), m.meter, 0.0)
        assertEquals(drin.last().zeit, m.letzte)
        assertEquals(Spur.Mass(1, 0.0, drin.first().zeit), Spur.mass(drin.take(1)))
        assertEquals(Spur.Mass(0, 0.0, 0L), Spur.mass(emptyList()))
    }

    @Test
    fun `das gemerkte Mass kommt bitgenau zurueck`() {
        val zufall = Random(9)
        repeat(1000) {
            val m = Spur.Mass(zufall.nextInt(0, 100_000), zufall.nextDouble(0.0, 300_000.0), zufall.nextLong(0, 2_000_000_000))
            val stempel = Spur.stempel(1_000_000, 1_000_000, 1_003_600, zufall.nextLong(0, 10_000_000))
            assertEquals(m, Spur.massAus(Spur.massText(stempel, m), stempel))
        }
    }

    @Test
    fun `ein anderer Stempel gilt nicht`() {
        val m = Spur.Mass(10, 1234.5, 99)
        val text = Spur.massText(Spur.stempel(1, 1, 9, 1234), m)
        // Die Datei wuchs (1234 -> 12345 beginnt gleich), das Fenster wurde laenger, nichts gemerkt.
        assertNull(Spur.massAus(text, Spur.stempel(1, 1, 9, 123)))
        assertNull(Spur.massAus(text, Spur.stempel(1, 1, 9, 12345)))
        assertNull(Spur.massAus(text, Spur.stempel(1, 1, 10, 1234)))
        assertNull(Spur.massAus(null, Spur.stempel(1, 1, 9, 1234)))
        assertNull(Spur.massAus("kaputt", Spur.stempel(1, 1, 9, 1234)))
        assertEquals(m, Spur.massAus(text, Spur.stempel(1, 1, 9, 1234)))
        // Eine andere Fassung der Rechnung gilt nicht, auch nicht die ohne Fassung.
        assertNull(Spur.massAus(Spur.massText(Spur.stempel(1, 1, 9, 1234, Spur.RECHNUNG - 1), m), Spur.stempel(1, 1, 9, 1234)))
        assertNull(Spur.massAus("1;1;9;1234;10;99;1234.5", Spur.stempel(1, 1, 9, 1234)))
        // Der Beginn bleibt vorn: TrainingsDaten.Laengen raeumt nach ihm ab.
        assertEquals("1", text.substringBefore(';'))
    }

    @Test
    fun `Fingerabdruck der Rechnung`() {
        // AENDERT SICH EINE DIESER ZAHLEN, rechnet die Spur anders: dann Spur.RECHNUNG
        // erhoehen und hier nachtragen - sonst zeigen Liste und Widget bis zu 91 Tage
        // die alten Meter aus dem Laengenspeicher, das Detail die neuen.
        val zeilen = weg(500, Random(5)) + listOf("{\"t\":1001000,\"lat\":0.6,\"lon\":0.6,\"g\":80}", "kaputt")
        val m = Spur.mass(Spur.inSitzung(Spur.ausZeilen(zeilen), Instant.ofEpochSecond(1_000_300), Instant.ofEpochSecond(1_001_200)))
        assertEquals("Spur.RECHNUNG", 1, Spur.RECHNUNG)
        assertEquals("Punkte, Rechnung ${Spur.RECHNUNG}", FINGER_ANZAHL, m.anzahl)
        // 1 µm Spielraum: Math.sin darf je JVM in der letzten Stelle abweichen.
        assertEquals("Meter, Rechnung ${Spur.RECHNUNG}", FINGER_METER, m.meter, 1e-6)
    }

    @Test
    fun `die Strecke aus Laengenspeicher und Route wie vorher aus den Punkten`() {
        // Vorher (TrainingsDaten bis 0.46.0): punkte() = Spur ab zwei Punkten in
        // der Sitzung, sonst die Route; strecke = laenge(punkte) ab zwei Punkten und > 0.
        val zufall = Random(4)
        val alle = Spur.ausZeilen(weg(60, zufall))
        val route = Spur.ausZeilen(weg(30, Random(8)))
        val stillstand = List(5) { Spur.Punkt(1_000_000L + it, 0.5, 0.5, null, 5f) }
        val spuren = listOf(null, emptyList(), alle.take(1), alle.take(2), alle, stillstand)
        val routen = listOf(emptyList(), route.take(1), route, stillstand)
        for (spur in spuren) for (r in routen) {
            val punkte = if (spur.orEmpty().size >= 2) spur.orEmpty() else r
            val vorher = Spur.laenge(punkte).takeIf { punkte.size >= 2 && it > 0 }
            // Ohne Datei (null) kennt der Laengenspeicher kein Mass.
            var gelesen = false
            val jetzt = Spur.laengeAus(spur?.let(Spur::mass)) { gelesen = true; r }?.takeIf { it > 0 }
            assertEquals("Spur ${spur?.size}, Route ${r.size}", vorher, jetzt)
            // Die Route umrechnen nur ohne Spur - wie vorher punkte(), das sie dann gar nicht ansah.
            assertEquals("Route gelesen bei Spur ${spur?.size}", spur.orEmpty().size < 2, gelesen)
        }
    }

    @Test
    fun `die offene Spur misst die ganze Datei`() {
        // Ohne Fenster (offeneSpuren): Anzahl, letzter Punkt und Laenge ueber alles.
        val punkte = Spur.ausZeilen(weg(300, Random(2)))
        val m = Spur.mass(punkte)
        assertEquals(punkte.size, m.anzahl)
        assertEquals(punkte.last().zeit, m.letzte)
        assertEquals(Spur.laenge(punkte), m.meter, 0.0)
    }
}
