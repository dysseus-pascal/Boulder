package coredevices.pebble.kiesel.ansicht

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import theme.greyScheme
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * DER HEALTH-START, GEZEICHNET - mit dem Code der App und den Beispielwerten
 * des Entwurfs ([Healthbeispiel]), im grauen Schema der App wie im Entwurf.
 *
 * Nachgesehen wird am Bild und an dem, was TalkBack liest, nicht an einem
 * Nachbau: wo steht die gruene Jetzt-Linie beim Oeffnen (bei drei Vierteln
 * der sichtbaren Breite), was sagt das Band, welche Zeilen stehen darunter.
 *
 * Die Bilder liegen danach unter build/health-start.png (die Seite, wie sie
 * oeffnet) und build/tagesband-ganz.png (das ganze Band, ungeschnitten).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GesundheitBildTest {

    private class Gezeichnet(val bild: Bitmap, val wurzel: SemanticsNode, val primaer: Int, val dichte: Float) {
        val breite = bild.width
        val pixel = IntArray(bild.width * bild.height).also { bild.getPixels(it, 0, bild.width, 0, 0, bild.width, bild.height) }
        fun bei(x: Int, y: Int) = pixel[y * breite + x]

        fun alle(n: SemanticsNode = wurzel): List<SemanticsNode> = listOf(n) + n.children.flatMap { alle(it) }
        val texte: List<String> get() = alle().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        val beschreibungen: List<String> get() = alle().flatMap { n -> n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
    }

    private fun zeichne(name: String, inhalt: @Composable () -> Unit): Gezeichnet {
        val aktivitaet = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var primaer = 0
        aktivitaet.setContent {
            MaterialTheme(colorScheme = greyScheme) {
                primaer = MaterialTheme.colorScheme.primary.toArgb()
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { inhalt() }
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        val ansicht = aktivitaet.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val bild = Bitmap.createBitmap(ansicht.width, ansicht.height, Bitmap.Config.ARGB_8888)
        ansicht.draw(Canvas(bild))
        File("build/$name.png").outputStream().use { bild.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val wurzel = (ansicht as ViewGroup).getChildAt(0) as ViewRootForTest
        return Gezeichnet(bild, wurzel.semanticsOwner.unmergedRootSemanticsNode, primaer, aktivitaet.resources.displayMetrics.density)
    }

    /** Der Knoten des Bands: der mit der Beschreibung, die mit "TODAY SO FAR" beginnt. */
    private fun band(g: Gezeichnet): SemanticsNode = assertNotNull(
        g.alle().firstOrNull { n -> n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith("TODAY SO FAR") } },
        "kein Band in ${g.beschreibungen}",
    )

    /** Die Spalten im Bereich des Bands, in denen das Gruen ueber mehr als 100 dp senkrecht durchgeht: die Jetzt-Linie. */
    private fun jetztLinie(g: Gezeichnet, links: Int, rechts: Int, oben: Int, unten: Int): Double {
        val spalten = (links until rechts).filter { x ->
            var lauf = 0
            var bester = 0
            for (y in oben until unten) {
                if (g.bei(x, y) == g.primaer) lauf++ else lauf = 0
                bester = maxOf(bester, lauf)
            }
            bester > 100 * g.dichte
        }
        assertTrue(spalten.isNotEmpty(), "keine Jetzt-Linie zwischen $links und $rechts")
        assertTrue(spalten.last() - spalten.first() < 4 * g.dichte, "mehr als eine Linie: $spalten")
        return spalten.average()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h1500dp-xxhdpi")
    fun der_start_mit_dem_band_beim_oeffnen() {
        val b = Healthbeispiel
        val g = zeichne("health-start") {
            GesundheitInhalt(b.stand, b.sport, 78, b.jetzt, imperial = false, b.wege, koerper = null) {}
        }
        val band = band(g)
        val r = band.boundsInRoot
        // DAS JETZT BEI DREI VIERTELN der sichtbaren Breite des Bands.
        val x = jetztLinie(g, r.left.toInt(), r.right.toInt(), r.top.toInt(), r.bottom.toInt())
        val anteil = (x - r.left) / r.width
        assertTrue(abs(anteil - JETZT_ANTEIL) < 0.01, "Jetzt bei ${"%.3f".format(anteil)} der Breite")

        // Was das Band TalkBack sagt: die Nacht mit ihren Werten und der Puls von jetzt.
        val satz = band.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().single()
        assertTrue("7:59 h · resting 55 · HRV 50" in satz, satz)
        assertTrue("68 bpm" in satz, satz)

        // Die Zeilen darunter: Sport, Heart & Body, Water, Stack - keine Zeilen Supplements und Caffeine mehr.
        val texte = g.texte
        for (t in listOf("Sport", "Heart & Body", "Water", "Stack", "supplements", "caffeine now", "at bedtime")) {
            assertTrue(t in texte, "\"$t\" fehlt in $texte")
        }
        assertTrue("Supplements" !in texte && "Caffeine" !in texte, "alte Zeilen in $texte")
        // Stack: 3 von 4 genommen, Magnesium kommt erst um 20:00 - noch nicht versaeumt; 63 mg um 6:44
        // wirken um 13:40 noch 24 mg, um 21:46 noch etwa 8.
        val stack = g.beschreibungen.firstOrNull { it.startsWith("Stack") }
        assertEquals("Stack, Magnesium due, 3 of 4 supplements, 24 mg caffeine now, ≈ 8 mg at bedtime", stack)
        // Das Sport-Feld: der Spaziergang von heute, und die Zahl der Woche wie auf der Sportseite.
        assertTrue(texte.any { it.startsWith("Walk 2.7 km · today") }, "$texte")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1100dp-h400dp-xxhdpi")
    fun das_ganze_band_ungeschnitten() {
        val b = Healthbeispiel
        val g = zeichne("tagesband-ganz") {
            Box(Modifier.padding(12.dp)) { Tagesband(b.stand, b.sport.trainings, b.jetzt, imperial = false, b.wege) {} }
        }
        val r = band(g).boundsInRoot
        // So breit, dass nichts geschoben ist: die Jetzt-Linie steht dort, wo die Zeit sie hinlegt -
        // 12 dp Rand plus 40 dp je Stunde ab 21:31 gestern (Einschlafen 21:46 minus 15 min).
        val x = jetztLinie(g, r.left.toInt(), r.right.toInt(), r.top.toInt(), r.bottom.toInt())
        val stunden = java.time.Duration.between(b.nacht.einschlafen.minusSeconds(15 * 60), b.jetzt).seconds / 3600.0
        assertEquals(r.left + (12 + stunden * 40) * g.dichte, x, 2.0 * g.dichte)
    }
}
