package coredevices.pebble.kiesel.ansicht

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import coredevices.pebble.R
import coredevices.pebble.kiesel.GesundheitsStand
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * DER SATZ UND DAS BILD DER PULSKARTE SAGEN DASSELBE - nachgemessen am
 * gezeichneten Bild, nicht an einem Nachbau der Zeichnung.
 *
 * Die ganze Karte wird gezeichnet (Robolectric mit echter Grafik), ihr Satz
 * aus der Semantik gelesen wie TalkBack ihn liest, und dann im Bild
 * nachgesehen, wie ein Mensch es taete: wo liegen die gepunkteten
 * Zonenlinien, wie hoch reicht die Linie zwischen ihnen, welche Farbe hat sie
 * dort. Ein Nachbau der Zeichnung im Test saehe einen Fehler nicht, der nur
 * im Bild steckt.
 *
 * Zwei Tage: zehn Minuten 150 zwischen 70ern, darin ein Ausreisser (Audit M5),
 * und nur fuenf Minuten 150 - ein einzelner Punkt der Linie, den eine
 * glaettende Kurve verfehlt.
 *
 * Die Bilder liegen danach unter build/pulskarte-<tag>.png - zum Ansehen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PulsKarteBildTest {
    /** Die Grenzen moderat, kraeftig, Spitze - Ruhepuls 53, Maximalpuls 187. */
    private val zonen = listOf(107, 133, 160)

    /**
     * Ein ganzer Tag bei 70 bpm, drei Stunden vor jetzt [minuten] Minuten 150 -
     * auf einer Fuenf-Minuten-Marke, also so viele Punkte der Linie wie
     * [minuten] / 5. [ausreisser]: eine Minute darin mit 175, wie sie ein
     * Sensor liefert - sie steht in der Saeule, nicht im Satz und nicht in der Linie.
     */
    private fun tag(minuten: Int, ausreisser: Boolean): List<Pair<Long, Int>> {
        val jetzt = Instant.now().epochSecond / 300 * 300
        val spitze = jetzt - 3 * 3600
        return (jetzt - 23 * 3600 until jetzt - 600 step 60).map { t ->
            t to when {
                ausreisser && t == spitze + 360 -> 175
                t in spitze until spitze + minuten * 60 -> 150
                else -> 70
            }
        }
    }

    private fun stand(puls: List<Pair<Long, Int>>) = GesundheitsStand(
        schritte = 0, aktivMinuten = 0, meter = 0.0, wocheSchritte = emptyList(), nacht = null, ruhepulsWoche = emptyList(),
        wocheSchlaf = emptyList(), puls = puls, spo2 = emptyList(), wasserMl = 0, glaeser = emptyList(), wasserZiel = null,
        supplemente = null, koffein = emptyList(), letzteDaten = null, pulsZonen = zonen,
    )

    /** Die gezeichnete Karte: ihr Bild, ihre Texte und die Zonenfarben, wie die Karte sie nimmt. */
    private class Gezeichnet(val bild: Bitmap, val texte: List<String>, val farben: List<Color>) {
        val breite = bild.width
        val pixel = IntArray(bild.width * bild.height).also { bild.getPixels(it, 0, bild.width, 0, 0, bild.width, bild.height) }
        fun bei(x: Int, y: Int) = pixel[y * breite + x]
    }

    private fun zeichne(s: GesundheitsStand, name: String): Gezeichnet {
        val aktivitaet = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var farben = emptyList<Color>()
        aktivitaet.setContent {
            MaterialTheme {
                // Dieselben Farben, die die Karte ihrer Linie gibt - gelesen, nicht nachgebaut.
                farben = Farben.pulszonen.map { it.ton() }
                PulsKarte(s)
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        val ansicht = aktivitaet.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val bild = Bitmap.createBitmap(ansicht.width, ansicht.height, Bitmap.Config.ARGB_8888)
        ansicht.draw(Canvas(bild))
        File("build/pulskarte-$name.png").outputStream().use { bild.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val wurzel = (ansicht as ViewGroup).getChildAt(0) as ViewRootForTest
        fun texte(n: SemanticsNode): List<String> =
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + n.children.flatMap { texte(it) }
        return Gezeichnet(bild, texte(wurzel.semanticsOwner.unmergedRootSemanticsNode), farben)
    }

    /**
     * Die Mitte der gepunkteten Linie in [farbe]: die Zeilen, in denen diese
     * Farbe in vielen kurzen Stuecken quer ueber das Bild steht. Eine Linie der
     * Kurve ist ein langes Stueck, ein Punkt der Legende ein einzelnes.
     */
    private fun punktlinie(g: Gezeichnet, farbe: Color): Double {
        val argb = farbe.toArgb()
        val zeilen = (0 until g.bild.height).filter { y ->
            val stuecke = mutableListOf<Int>()
            var lauf = 0
            for (x in 0 until g.breite) {
                if (g.bei(x, y) == argb) lauf++ else if (lauf > 0) { stuecke += lauf; lauf = 0 }
            }
            if (lauf > 0) stuecke += lauf
            stuecke.size >= 20 && stuecke.max() <= 12
        }
        assertTrue(zeilen.isNotEmpty(), "keine gepunktete Linie in $farbe")
        // Eine Linie, nicht zwei: die Zeilen haengen zusammen.
        assertTrue(zeilen.last() - zeilen.first() < 12, "Zeilen $zeilen")
        return zeilen.average()
    }

    @Test
    fun zehn_minuten_150_mit_ausreisser_wie_im_audit() = pruefe("audit", tag(minuten = 10, ausreisser = true))

    @Test
    fun fuenf_minuten_150_ein_einzelner_punkt_der_linie() = pruefe("einzeln", tag(minuten = 5, ausreisser = false))

    /** Satz und Bild der Karte: Spanne 70-150 und "vigorous", und die Linie reicht in dieser Farbe bis 150. */
    private fun pruefe(name: String, puls: List<Pair<Long, Int>>) {
        val g = zeichne(stand(puls), name)
        val context = RuntimeEnvironment.getApplication()

        // DER SATZ: Spanne aus den Medianen (70-150, nicht 175) und die Zone der Spitze.
        val spanne = g.texte.firstNotNullOfOrNull { Regex("""(\d+)–(\d+)""").find(it) }
        val (tief, hoch) = assertNotNull(spanne, "keine Spanne in ${g.texte}").destructured.let { (a, b) -> a.toInt() to b.toInt() }
        assertEquals(70 to 150, tief to hoch)
        val zonenNamen = listOf(R.string.ks_h_zone_leicht, R.string.ks_h_zone_moderat, R.string.ks_h_zone_kraeftig, R.string.ks_h_zone_spitze)
            .map { context.getString(it).lowercase() }
        // "High in %1$s zone": das Wort zwischen den festen Teilen ist die Zone.
        val (vor, nach) = context.getString(R.string.ks_h_puls_hoch, "\u0000").split("\u0000")
        val satz = assertNotNull(g.texte.firstOrNull { it.contains(vor) }, "kein Satz zur Spitze in ${g.texte}")
        val zone = zonenNamen.indexOf(satz.substringAfter(vor).substringBefore(nach))
        assertEquals(2, zone, satz)

        // DAS BILD: die gepunkteten Grenzen kraeftig (133) und Spitze (160) als Massstab.
        val y133 = punktlinie(g, g.farben[2])
        val y160 = punktlinie(g, g.farben[3])
        assertTrue(y160 < y133, "160 liegt ueber 133")
        val yHoch = y133 + (hoch - zonen[1]) * (y160 - y133) / (zonen[2] - zonen[1])

        // Die Dicke des Strichs, gemessen am flachen Morgen bei 70 (leichte Zone).
        val leicht = g.farben[0].toArgb()
        val morgen = g.breite / 4
        val dicke = (0 until g.bild.height).count { g.bei(morgen, it) == leicht }
        assertTrue(dicke in 3..15, "Strich $dicke px")

        // Der oberste Punkt der Linie: Zonenfarbe, aber nicht auf einer gepunkteten Linie.
        val linienfarben = g.farben.map { it.toArgb() }.toSet()
        val gepunktet = listOf(y133, y160).flatMap { m -> (m.toInt() - 6..m.toInt() + 6) }.toSet()
        val oben = (0 until g.bild.height).asSequence().filter { it !in gepunktet && it > y160 - 3 * (y133 - y160) }
            .flatMap { y -> (0 until g.breite).asSequence().map { x -> x to y } }
            .firstOrNull { (x, y) -> g.bei(x, y) in linienfarben }
        val (x, y) = assertNotNull(oben, "keine Linie im Bild")
        // Er reicht bis zur Zahl im Satz: die Oberkante des Strichs liegt eine halbe Dicke ueber ihr.
        assertTrue(abs(y + dicke / 2.0 - yHoch) <= 2.5, "Linie oben bei ${y + dicke / 2.0} px, $hoch bpm liegt bei $yHoch px (133: $y133, 160: $y160)")
        // Und dort hat sie die Farbe der Zone, die der Satz nennt.
        assertEquals(g.farben[zone].toArgb(), g.bei(x, y), "Farbe am Gipfel")
    }
}
