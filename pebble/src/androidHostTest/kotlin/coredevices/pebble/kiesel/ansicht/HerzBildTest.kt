package coredevices.pebble.kiesel.ansicht

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.sp
import coredevices.pebble.R
import coredevices.pebble.kiesel.GesundheitsStand
import coredevices.pebble.ui.HERZ_TITEL
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
 * HEART & BODY, GEZEICHNET - die ganze Seite mit dem Code der App und den
 * Beispielwerten des Entwurfs "Erlebnis-Herz" ([Herzbeispiel]), im grauen
 * Schema der App. NACHGESTELLT ist nur die Kopfleiste (Pfeil und Titel wie
 * in WatchHomeScreen, siehe SportUnterseiteTest); ihr Titel ist der des
 * Bildschirms ([HERZ_TITEL]).
 *
 * Nachgesehen wird am Bild und an dem, was TalkBack liest: Kopf von HRV und
 * Blutdruck (Wert, Schnitt der Woche, Urteil), keine Legende, beim
 * Blutdruck zwei Baender und die Punkte daneben bernstein, die Zeile Trends
 * am Ende - und ohne fruehere Werte alles neutral.
 *
 * Die Bilder liegen danach unter build/herzseite.png,
 * build/herzseite-neutral.png und build/herzseite-blutdruck-ohne-woche.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h1560dp-xxhdpi")
class HerzBildTest {

    private val context get() = RuntimeEnvironment.getApplication()

    /** Die Farben, wie die Seite sie nimmt - gelesen, nicht nachgebaut. */
    private class Farbsatz(val imBand: Int, val ausserBand: Int, val bandflaeche: Int, val neutral: Int)

    private class Gezeichnet(val bild: Bitmap, val wurzel: SemanticsNode, val farben: Farbsatz, val dichte: Float) {
        val breite = bild.width
        val pixel = IntArray(bild.width * bild.height).also { bild.getPixels(it, 0, bild.width, 0, 0, bild.width, bild.height) }
        fun bei(x: Int, y: Int) = pixel[y * breite + x]

        fun alle(n: SemanticsNode = wurzel): List<SemanticsNode> = listOf(n) + n.children.flatMap { alle(it) }
        fun text(n: SemanticsNode): String? = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        fun beschreibung(n: SemanticsNode): String? = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
        val texte: List<String> get() = alle().mapNotNull { text(it) }

        /** Der Knoten mit genau diesem Text. */
        fun mitText(t: String): SemanticsNode = assertNotNull(alle().singleOrNull { text(it) == t }, "\"$t\" nicht genau einmal in $texte")

        /** Das Bild, dessen Satz fuer TalkBack mit [anfang] beginnt. */
        fun bild(anfang: String): SemanticsNode = assertNotNull(
            alle().firstOrNull { beschreibung(it)?.startsWith(anfang) == true },
            "kein Bild \"$anfang…\" in ${alle().mapNotNull { beschreibung(it) }}",
        )

        /** Die Pixel im Rechteck, fuer die [passt] gilt. */
        fun pixelIn(r: Rect, passt: (Int) -> Boolean): List<Pair<Int, Int>> =
            (r.top.toInt() until r.bottom.toInt()).flatMap { y -> (r.left.toInt() until r.right.toInt()).filter { x -> passt(bei(x, y)) }.map { x -> x to y } }

        /** Zusammenhaengende Flecken genau dieser Farbe im Rechteck, groesser als [mindestens] Pixel - je ihre Mitte. */
        fun flecken(r: Rect, farbe: Int, mindestens: Int): List<Pair<Double, Double>> {
            val offen = pixelIn(r) { it == farbe }.toMutableSet()
            val aus = mutableListOf<Pair<Double, Double>>()
            while (offen.isNotEmpty()) {
                val start = offen.first()
                offen.remove(start)
                val fleck = mutableListOf(start)
                var i = 0
                while (i < fleck.size) {
                    val (x, y) = fleck[i++]
                    for (n in listOf(x + 1 to y, x - 1 to y, x to y + 1, x to y - 1)) if (offen.remove(n)) fleck += n
                }
                if (fleck.size > mindestens) aus += fleck.map { it.first }.average() to fleck.map { it.second }.average()
            }
            return aus
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun zeichne(name: String, s: GesundheitsStand, oeffneTrend: () -> Unit = {}, oeffneNacht: (Long) -> Unit = {}): Gezeichnet {
        val aktivitaet = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        lateinit var farben: Farbsatz
        aktivitaet.setContent {
            MaterialTheme(colorScheme = greyScheme) {
                farben = Farbsatz(
                    Farben.imBand.ton().toArgb(), Farben.ausserBand.ton().toArgb(),
                    Farben.imBand.flaeche().copy(alpha = Farben.BAND_DECKUNG).compositeOver(Farben.karte()).toArgb(),
                    Farben.neutral().toArgb(),
                )
                Scaffold(
                    topBar = {
                        TopAppBar(
                            navigationIcon = { IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = "Back") } },
                            title = { Text(stringResource(HERZ_TITEL), color = MaterialTheme.colorScheme.primary, fontSize = 28.sp, maxLines = 1) },
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.background,
                ) { innen ->
                    Box(Modifier.padding(innen)) { HerzInhalt(s, Herzbeispiel.jetzt, oeffneNacht, oeffneTrend) }
                }
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        val ansicht = aktivitaet.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val bild = Bitmap.createBitmap(ansicht.width, ansicht.height, Bitmap.Config.ARGB_8888)
        ansicht.draw(Canvas(bild))
        File("build/$name.png").outputStream().use { bild.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val wurzel = (ansicht as ViewGroup).getChildAt(0) as ViewRootForTest
        return Gezeichnet(bild, wurzel.semanticsOwner.unmergedRootSemanticsNode, farben, aktivitaet.resources.displayMetrics.density)
    }

    private fun naheFarbe(a: Int, b: Int) = listOf(16, 8, 0).all { abs((a shr it and 0xFF) - (b shr it and 0xFF)) <= 3 }

    private val urteile get() = listOf(R.string.ks_h_im_band, R.string.ks_h_unter_band, R.string.ks_h_ueber_band, R.string.ks_h_neben_band).map { context.getString(it) }

    /**
     * DIE SEITE WIE IM ENTWURF: Titel "Heart & Body"; HRV mit Wert, Schnitt
     * der sieben Naechte und Urteil im Kopf; Blutdruck mit dem Wert von
     * heute, seiner Zeit und dem Schnitt der Woche; keine Legende; die
     * Zeile Trends als letzte Karte, und ein Tipp darauf oeffnet die Trends.
     */
    @Test
    fun heart_and_body_wie_im_entwurf() {
        var trends = 0
        var naechte = 0
        val g = zeichne("herzseite", Herzbeispiel.stand(), oeffneTrend = { trends++ }, oeffneNacht = { naechte++ })

        assertEquals("Heart & Body", context.getString(HERZ_TITEL))
        g.mitText("Heart & Body")

        // Die Karten in der Reihenfolge der Seite; der Puls oben bleibt, wie er war.
        val hrvKopf = g.mitText("HRV · last 7 nights")
        val bdKopf = g.mitText("BLOOD PRESSURE · last 7 days")
        val folge = listOf(
            g.alle().first { g.text(it)?.contains("bpm, measured") == true },
            hrvKopf, bdKopf,
            g.alle().first { g.text(it)?.startsWith("LAST NIGHT") == true },
            g.mitText("YOUR USUAL"),
            g.mitText("What makes a difference"),
        ).map { it.boundsInRoot.top }
        assertEquals(folge.sorted(), folge, "Reihenfolge")

        // HRV: 50 ms gestern Nacht, Schnitt der Woche 49 (48 53 46 55 52 41 50), im Band.
        assertTrue("50 ms" in g.texte, "${g.texte}")
        g.mitText("Last night · 7-night avg 49")
        // Blutdruck: heute 7:10 gemessen, Schnitt der sieben Sitzungen 125/80.
        assertTrue("122/78 mmHg" in g.texte, "${g.texte}")
        val bdUnter = assertNotNull(g.texte.singleOrNull { it.endsWith(" · avg 125/80") }, "${g.texte}")
        assertTrue(bdUnter.startsWith("Today") && "7:10" in bdUnter, bdUnter)

        // Das Urteil rechts im Kopf beider Karten, auf seiner Hoehe und rechts vom Titel, in der Farbe "im Band".
        val imBand = context.getString(R.string.ks_h_im_band)
        val urteilKnoten = g.alle().filter { g.text(it) == imBand }
        assertEquals(2, urteilKnoten.size, "${g.texte}")
        for ((kopf, urteil) in listOf(hrvKopf, bdKopf).zip(urteilKnoten.sortedBy { it.boundsInRoot.top })) {
            val k = kopf.boundsInRoot
            val u = urteil.boundsInRoot
            assertTrue(abs(k.center.y - u.center.y) < 4 * g.dichte, "Urteil nicht auf der Hoehe des Kopfs: $k / $u")
            assertTrue(u.left > k.right, "Urteil nicht rechts vom Titel: $k / $u")
            assertTrue(g.pixelIn(u) { it == g.farben.imBand }.size > 20 * g.dichte, "Urteil nicht in der Farbe im Band")
        }

        // Keine Legende mehr unter der HRV.
        assertTrue(listOf("Personal range", "In range", "Out of range").none { it in g.texte }, "Legende: ${g.texte}")

        // DIE BILDER: HRV eine Nacht daneben (41 ms am vorletzten Tag), Blutdruck oben und unten am selben Tag.
        val hrv = g.bild("HRV, last 7 nights:").boundsInRoot
        val bd = g.bild("BLOOD PRESSURE, last 7 days:").boundsInRoot
        assertTrue(hrv.bottom < bd.top)
        val gross = (20 * g.dichte * g.dichte).toInt()
        val hrvDaneben = g.flecken(hrv, g.farben.ausserBand, gross)
        assertEquals(1, hrvDaneben.size, "HRV daneben: $hrvDaneben")
        val bdDaneben = g.flecken(bd, g.farben.ausserBand, gross)
        assertEquals(2, bdDaneben.size, "Blutdruck daneben: $bdDaneben")
        // Der vorletzte von sieben Tagen: bei 5,5 Siebteln der Flaeche links der Achse (30-50 dp breit).
        for ((r, daneben) in listOf(hrv to hrvDaneben, bd to bdDaneben)) for ((x, _) in daneben) {
            val anteil = (x - r.left) / r.width
            assertTrue(anteil in 0.65..0.77, "Punkt daneben bei ${"%.3f".format(anteil)} der Breite")
        }
        assertTrue(abs(bdDaneben[0].first - bdDaneben[1].first) < 2 * g.dichte, "oben und unten nicht am selben Tag: $bdDaneben")
        // Gruene Punkte gibt es in beiden Bildern.
        assertTrue(g.pixelIn(hrv) { it == g.farben.imBand }.size > gross && g.pixelIn(bd) { it == g.farben.imBand }.size > gross)

        // ZWEI BAENDER beim Blutdruck: die Bandflaeche liegt in zwei getrennten Hoehen, oben (116-129) und unten (74-83).
        val band = g.pixelIn(bd) { naheFarbe(it, g.farben.bandflaeche) }.map { it.second }.distinct().sorted()
        val stufen = band.zipWithNext().count { (a, b) -> b - a > 10 * g.dichte }
        assertEquals(1, stufen, "Bandflaeche in ${stufen + 1} Hoehen")
        // Die HRV hat eines.
        val hrvBand = g.pixelIn(hrv) { naheFarbe(it, g.farben.bandflaeche) }.map { it.second }.distinct().sorted()
        assertTrue(hrvBand.isNotEmpty() && hrvBand.zipWithNext().none { (a, b) -> b - a > 10 * g.dichte }, "HRV-Band")

        // DIE ZEILE TRENDS ist die letzte Karte, und ein Tipp darauf oeffnet die Trends - nicht die Nacht.
        val unter = g.mitText("What makes a difference")
        val titel = g.alle().filter { g.text(it) == context.getString(R.string.ks_h_trends) }
            .single { abs(it.boundsInRoot.bottom - unter.boundsInRoot.top) < 6 * g.dichte }
        assertTrue(g.alle().filter { g.text(it) != null }.all { it.boundsInRoot.top <= unter.boundsInRoot.top }, "nach Trends steht noch Text")
        var ziel: SemanticsNode? = unter
        while (ziel != null && ziel.config.getOrNull(SemanticsActions.OnClick) == null) ziel = ziel.parent
        val klick = assertNotNull(ziel, "Trends ist kein Ziel").config[SemanticsActions.OnClick]
        assertTrue(titel.boundsInRoot.left >= ziel.boundsInRoot.left && titel.boundsInRoot.right <= ziel.boundsInRoot.right)
        klick.action!!.invoke()
        assertEquals(1 to 0, trends to naechte)
    }

    /**
     * OHNE GENUG FRUEHERE WERTE NEUTRAL, wie bei der HRV: nur die Woche
     * selbst - kein Band, kein Urteil, keine gruenen oder bernsteinfarbenen
     * Punkte, aber die Werte, die Zeit und die Schnitte stehen da.
     */
    @Test
    fun ohne_fruehere_werte_steht_die_woche_neutral() {
        val g = zeichne("herzseite-neutral", Herzbeispiel.stand(mitFrueheren = false))
        assertTrue(urteile.none { it in g.texte }, "Urteil ohne Band: ${g.texte}")
        g.mitText("Last night · 7-night avg 49")
        assertTrue("122/78 mmHg" in g.texte && g.texte.any { it.endsWith(" · avg 125/80") }, "${g.texte}")
        for (anfang in listOf("HRV, last 7 nights:", "BLOOD PRESSURE, last 7 days:")) {
            val r = g.bild(anfang).boundsInRoot
            assertTrue(g.pixelIn(r) { it == g.farben.imBand || it == g.farben.ausserBand }.isEmpty(), "$anfang: gruen oder bernstein ohne Band")
            assertTrue(g.pixelIn(r) { naheFarbe(it, g.farben.bandflaeche) }.isEmpty(), "$anfang: Bandflaeche ohne Band")
            // Die Punkte stehen trotzdem da - neutral.
            assertTrue(g.flecken(r, g.farben.neutral, (10 * g.dichte * g.dichte).toInt()).isNotEmpty(), "$anfang: keine neutralen Punkte")
        }
    }

    /**
     * OHNE MESSUNG IN DER WOCHE nennt die Blutdruckkarte die Tage, aus denen
     * ihr Wert stammt: zuletzt vor sieben Tagen gemessen, also "last 30
     * days" mit dem Schnitt dieser Tage - nicht "last 7 days" ueber einem
     * Wert ausserhalb der Woche. Kein Bild der Woche, kein Urteil.
     */
    @Test
    fun ohne_messung_in_der_woche_nennt_die_karte_ihre_dreissig_tage() {
        val g = zeichne("herzseite-blutdruck-ohne-woche", Herzbeispiel.stand(blutdruckInDerWoche = false))
        g.mitText("BLOOD PRESSURE · last 30 days")
        assertTrue("BLOOD PRESSURE · last 7 days" !in g.texte, "${g.texte}")
        // Die juengste Sitzung von vor sieben Tagen; der Schnitt der 23 Sitzungen der letzten 30 Tage
        // (vor 7 bis 29 Tagen: zwoelfmal 129/83, elfmal 116/74) ist 123/79.
        assertTrue("129/83 mmHg" in g.texte, "${g.texte}")
        assertTrue(g.texte.any { it.endsWith(" · avg 123/79") }, "${g.texte}")
        assertTrue(g.alle().none { g.beschreibung(it)?.startsWith("BLOOD PRESSURE") == true }, "Bild der Woche ohne Messung darin")
        // Das eine Urteil ist das der HRV, rechts neben ihrem Kopf.
        val urteil = g.alle().filter { g.text(it) in urteile }
        assertEquals(1, urteil.size, "${g.texte}")
        assertTrue(abs(urteil.single().boundsInRoot.center.y - g.mitText("HRV · last 7 nights").boundsInRoot.center.y) < 4 * g.dichte)
    }
}
