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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.Glaseintrag
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import theme.greyScheme
import java.io.File
import java.time.Instant
import java.time.LocalTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * DIE SEITEN WATER UND STACK, GEZEICHNET - mit dem Code der App und den
 * Beispielwerten der Entwuerfe ([Healthbeispiel]: 0.5 von 2.5 l, zwei
 * Glaeser, die Woche 2.6/2.1/2.7/2.5/1.8/2.9 l; drei Praeparate um 8:00,
 * Magnesium um 20:00, Espresso 63 mg um 6:44), im grauen Schema der App.
 *
 * Nachgesehen wird am Bild und an dem, was TalkBack liest: die Menge und
 * ihr Balken, die Saeulen an der Ziellinie, was ein langer Druck aendern
 * darf, wo die Jetzt-Linie der Koffeinkurve steht, dass nur bis jetzt
 * Flaeche liegt und danach gestrichelt wird, und dass Bernstein erst
 * Versaeumtes zeigt.
 *
 * Die Bilder liegen danach unter build/wasser.png, build/stack.png und
 * build/stack-abend.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WasserStackBildTest {

    private class Farbwerte(val primaer: Int, val karte: Int, val wasser: Int, val wasserTon: Int, val koffeinTon: Int, val warnung: Int)

    private class Gezeichnet(val bild: Bitmap, val wurzel: SemanticsNode, val f: Farbwerte, val dichte: Float, val context: android.content.Context) {
        val breite = bild.width
        val pixel = IntArray(bild.width * bild.height).also { bild.getPixels(it, 0, bild.width, 0, 0, bild.width, bild.height) }
        fun bei(x: Int, y: Int) = pixel[y * breite + x]
        fun dp(n: Float) = (n * dichte).toInt()

        fun alle(n: SemanticsNode = wurzel): List<SemanticsNode> = listOf(n) + n.children.flatMap { alle(it) }
        fun texte(n: SemanticsNode): List<String> = n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        val texte: List<String> get() = alle().flatMap { texte(it) }
        val beschreibungen: List<String> get() = alle().flatMap { n -> n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

        /** Der Knoten, dessen Beschreibung mit [anfang] beginnt - ein Bild oder eine zusammengefasste Zeile. */
        fun beschrieben(anfang: String): SemanticsNode = assertNotNull(
            alle().firstOrNull { n -> n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith(anfang) } },
            "nichts beginnt mit \"$anfang\" in $beschreibungen",
        )

        /** Die Knoten mit langem Druck und ihre Texte darunter. */
        val langDruecke: List<SemanticsNode> get() = alle().filter { it.config.getOrNull(SemanticsActions.OnLongClick) != null }
        fun texteUnter(n: SemanticsNode): List<String> = alle(n).flatMap { texte(it) }
    }

    private fun zeichne(name: String, inhalt: @Composable () -> Unit): Gezeichnet {
        val aktivitaet = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        lateinit var f: Farbwerte
        aktivitaet.setContent {
            MaterialTheme(colorScheme = greyScheme) {
                f = Farbwerte(
                    MaterialTheme.colorScheme.primary.toArgb(), Farben.karte().toArgb(), Farben.wasser.flaeche().toArgb(),
                    Farben.wasser.ton().toArgb(), Farben.koffein.ton().toArgb(), Farben.warnung.ton().toArgb(),
                )
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { inhalt() }
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        val ansicht = aktivitaet.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val bild = Bitmap.createBitmap(ansicht.width, ansicht.height, Bitmap.Config.ARGB_8888)
        ansicht.draw(Canvas(bild))
        File("build/$name.png").outputStream().use { bild.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val wurzel = (ansicht as ViewGroup).getChildAt(0) as ViewRootForTest
        return Gezeichnet(bild, wurzel.semanticsOwner.unmergedRootSemanticsNode, f, aktivitaet.resources.displayMetrics.density, aktivitaet)
    }

    // --- Water ---

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h1100dp-xxhdpi")
    fun water_mit_menge_glaesern_und_woche() {
        val b = Healthbeispiel
        val bearbeitet = mutableListOf<Bearbeitung>()
        var trends = 0
        val g = zeichne("wasser") { WasserInhalt(b.stand, b.jetzt, { bearbeitet += it }, { trends++ }) }
        val texte = g.texte
        for (t in listOf("0.5 of 2.5 l", "2 glasses", "TODAY", "LAST 7 DAYS", "goal reached 4 ×", "Trends")) {
            assertTrue(t in texte, "\"$t\" fehlt in $texte")
        }
        assertEquals(2, texte.count { it == "Glass of water" }, "$texte")
        assertEquals(2, texte.count { it == "0.25 l" }, "$texte")
        // Keine Erinnerung: Drinktervall schickt keine Zeit dafuer.
        assertTrue(texte.none { it.contains("reminder", ignoreCase = true) }, "$texte")

        // DER BALKEN: 0.5 von 2.5 l sind ein Fuenftel - gemessen an der Wasserfarbe zwischen Menge und "2 glasses".
        val glaeser = g.alle().first { "2 glasses" in g.texte(it) }.boundsInRoot
        val zeile = (glaeser.top.toInt() - g.dp(30f) until glaeser.top.toInt()).maxBy { y -> (0 until g.breite).count { g.bei(it, y) == g.f.wasser } }
        val voll = (0 until g.breite).filter { g.bei(it, zeile) == g.f.wasser }
        assertTrue(voll.isNotEmpty(), "kein Balken ueber \"2 glasses\"")
        // Der Balken beginnt links mit dem Text darunter und endet rechts ebenso weit vom Rand.
        val links = glaeser.left
        val ganz = g.breite - 2 * links
        assertEquals(voll.first().toFloat(), links, 2f)
        assertEquals(0.2, voll.size / ganz.toDouble(), 0.01, "Balken ${voll.size} von $ganz")

        // DIE WOCHE: sieben Saeulen, heute in der Wasserfarbe; erreichte Tage blau, die anderen grau.
        val woche = g.beschrieben("Last 7 days").boundsInRoot
        assertTrue(g.beschrieben("Last 7 days").config[SemanticsProperties.ContentDescription].single().contains("goal 2.5 l"))
        val boden = woche.bottom.toInt() - g.dp(2f)
        val spalte = woche.width / 7
        fun saeule(i: Int) = g.bei((woche.left + spalte * (i + 0.5f)).toInt(), boden)
        assertEquals(g.f.wasser, saeule(6), "heute")
        val erreicht = listOf(0, 2, 3, 5).map { saeule(it) }.distinct()
        val darunter = listOf(1, 4).map { saeule(it) }.distinct()
        assertEquals(1, erreicht.size, "erreichte Tage verschieden: $erreicht")
        assertEquals(1, darunter.size, "andere Tage verschieden: $darunter")
        fun rgb(c: Int) = Triple(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF)
        val (er, eg, eb) = rgb(erreicht.single())
        val (dr, dg, db) = rgb(darunter.single())
        assertTrue(eb > er + 40 && eb > eg, "erreicht nicht blau: ${Integer.toHexString(erreicht.single())}")
        assertTrue(maxOf(dr, dg, db) - minOf(dr, dg, db) < 16, "darunter nicht grau: ${Integer.toHexString(darunter.single())}")
        assertNotEquals(g.f.wasser, erreicht.single())

        // DIE ZIELLINIE durchgezogen ueber die ganze Breite - und Samstag mit genau 2.5 l reicht genau bis zu ihr.
        val linie = (woche.top.toInt() until woche.bottom.toInt()).filter { y ->
            (woche.left.toInt() until woche.right.toInt()).count { g.bei(it, y) == g.f.wasserTon } > woche.width * 0.9
        }
        assertTrue(linie.isNotEmpty(), "keine durchgezogene Ziellinie")
        // Das Ziel steht 70 dp ueber dem Boden der Saeulen wie im Entwurf - auch Montag mit 2.9 l passt darunter ins Bild.
        assertEquals(70.0, (woche.bottom - linie.average()) / g.dichte, 2.0, "Ziellinie bei $linie")
        val samstag = (woche.left + spalte * 3.5f).toInt()
        val oben = (woche.top.toInt() until boden).first { y -> g.bei(samstag, y) == saeule(3) }
        assertTrue(abs(oben - linie.average()) <= g.dp(2.5f), "Samstag oben bei $oben, Linie bei $linie")

        // Ein langer Druck auf ein Glas oeffnet das Aendern - fuer beide eigenen Glaeser.
        val lang = g.langDruecke
        assertEquals(2, lang.size, "${lang.map { g.texteUnter(it) }}")
        lang.first().config[SemanticsActions.OnLongClick].action!!.invoke()
        assertEquals(listOf<Bearbeitung>(Bearbeitung.Glas("Glass of water", b.um(9, 20), "g1")), bearbeitet)
        g.alle().first { it.config.getOrNull(SemanticsActions.OnClick) != null && "Trends" in g.texteUnter(it) }
            .config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(1, trends)
    }

    /** Wasser einer anderen App zaehlt in der Menge und steht in der Liste - aendern darf Boulder es nicht. */
    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h1100dp-xxhdpi")
    fun fremdes_wasser_steht_da_laesst_sich_aber_nicht_aendern() {
        val b = Healthbeispiel
        val s = b.stand.copy(glasEintraege = b.stand.glasEintraege + Glaseintrag(b.um(11, 5), "f1", false, 330), wasserMl = 830)
        val g = zeichne("wasser-fremd") { WasserInhalt(s, b.jetzt, {}, {}) }
        assertTrue("Water" in g.texte && "0.33 l" in g.texte, "${g.texte}")
        assertEquals(2, g.langDruecke.size)
        assertTrue(g.langDruecke.none { "Water" in g.texteUnter(it) })
    }

    /**
     * Wer mehr trank, als ueber dem Ziel Platz hat (4 l bei 2.5 l), drueckt die
     * Skala: Linie und Saeulen ruecken zusammen nach unten, und Samstag mit
     * genau 2.5 l reicht weiter genau bis zur Linie.
     */
    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h1100dp-xxhdpi")
    fun ein_grosser_tag_drueckt_linie_und_saeulen_gemeinsam() {
        val b = Healthbeispiel
        val woche = b.wasserWoche.mapIndexed { i, (tag, ml) -> tag to if (i == 5) 4000 else ml }
        val g = zeichne("wasser-viel") { WasserInhalt(b.stand.copy(wasserWoche = woche), b.jetzt, {}, {}) }
        val r = g.beschrieben("Last 7 days").boundsInRoot
        val spalte = r.width / 7
        val linie = (r.top.toInt() until r.bottom.toInt()).filter { y ->
            (r.left.toInt() until r.right.toInt()).count { g.bei(it, y) == g.f.wasserTon } > r.width * 0.9
        }
        assertTrue(linie.isNotEmpty(), "keine durchgezogene Ziellinie")
        // Tiefer als die 70 dp eines gewoehnlichen Tags: 4 l fuellen das ganze Bild.
        assertTrue((r.bottom - linie.average()) / g.dichte < 60, "Ziellinie bei $linie")
        val boden = r.bottom.toInt() - g.dp(2f)
        fun oben(i: Int): Int {
            val x = (r.left + spalte * (i + 0.5f)).toInt()
            val farbe = g.bei(x, boden)
            return (r.top.toInt() until boden).first { y -> g.bei(x, y) == farbe }
        }
        assertTrue(abs(oben(3) - linie.average()) <= g.dp(2.5f), "Samstag oben bei ${oben(3)}, Linie bei $linie")
        assertTrue(oben(5) - r.top <= g.dp(2f), "Montag nicht bis oben: ${oben(5)} bei Bild ab ${r.top}")
    }

    // --- Stack ---

    /** Die Jetzt-Linie der Koffeinkurve: die Spalte, in der das Gruen ueber mehr als 100 dp senkrecht durchgeht. */
    private fun jetztLinie(g: Gezeichnet, r: Rect): Double {
        val spalten = (r.left.toInt() until r.right.toInt()).filter { x ->
            var lauf = 0
            var bester = 0
            for (y in r.top.toInt() until r.bottom.toInt()) {
                if (g.bei(x, y) == g.f.primaer) lauf++ else lauf = 0
                bester = maxOf(bester, lauf)
            }
            bester > 100 * g.dichte
        }
        assertTrue(spalten.isNotEmpty(), "keine Jetzt-Linie")
        assertTrue(spalten.last() - spalten.first() < 4 * g.dichte, "mehr als eine Linie: $spalten")
        return spalten.average()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h1200dp-xxhdpi")
    fun stack_mittags_mit_praeparaten_und_koffeinkurve() {
        val b = Healthbeispiel
        val bearbeitet = mutableListOf<Bearbeitung>()
        var dazu = 0
        val g = zeichne("stack") { StackInhalt(b.stand, b.jetzt, { bearbeitet += it }, { dazu++ }, {}) }
        val texte = g.texte
        for (t in listOf("SUPPLEMENTS", "3 of 4", "Multivitamin", "Zinc", "week 3 of 8", "D3", "Magnesium", "CAFFEINE", "63 mg today",
            "24 mg active now", "Espresso, sugar", "63 mg", "Add caffeine", "Trends")) {
            assertTrue(t in texte, "\"$t\" fehlt in $texte")
        }
        // Magnesium steht erst um 20:00 an: mittags nicht "still open", kein Bernstein.
        assertTrue("still open" !in texte, "$texte")
        val uhr = { h: Int, m: Int -> Formate.uhrzeit(g.context, LocalTime.of(h, m)) }
        val magnesium = g.beschrieben("Magnesium")
        assertEquals("Magnesium due, ${uhr(20, 0)}", magnesium.config[SemanticsProperties.ContentDescription].single())
        assertTrue(bernsteinLinks(g, magnesium.boundsInRoot) == 0, "Bernstein vor Magnesium um 13:40")
        // Die genommenen mit ihrer Zeit aus der Akte, Zink mit seiner Kurwoche.
        assertEquals("Zinc taken, ${uhr(8, 0)}, week 3 of 8", g.beschrieben("Zinc").config[SemanticsProperties.ContentDescription].single())

        // DIE KURVE: 6:00 bis 22:00 (Schlafenszeit 21:46 aus der Nacht), das Jetzt um 13:40 bei 460 von 960 Minuten.
        val bild = g.beschrieben("Caffeine,")
        val satz = bild.config[SemanticsProperties.ContentDescription].single()
        assertTrue("24 mg active now" in satz && "≈ 8 mg at ${uhr(21, 46)}" in satz, satz)
        val r = bild.boundsInRoot
        val x = jetztLinie(g, r)
        assertEquals(460.0 / 960, (x - r.left) / r.width, 0.01)
        // Bis jetzt liegt Flaeche unter der Kurve, danach nicht: knapp ueber dem Boden der Kurve
        // (120 dp unter ihrem Kopf) ist es links vom Jetzt nicht die Karte, rechts schon.
        val boden = r.top.toInt() + g.dp(117f)
        assertNotEquals(g.f.karte, g.bei((r.left + r.width * 0.3f).toInt(), boden), "keine Flaeche vor dem Jetzt")
        assertEquals(g.f.karte, g.bei((r.left + r.width * 0.7f).toInt(), boden), "Flaeche nach dem Jetzt")
        // Durchgezogen bis jetzt, gestrichelt danach: Spalten mit der Koffeinfarbe.
        fun anteil(von: Float, bis: Float): Double {
            val xs = ((r.left + r.width * von).toInt() until (r.left + r.width * bis).toInt())
            return xs.count { sx -> (r.top.toInt() until boden).any { g.bei(sx, it) == g.f.koffeinTon } } / xs.count().toDouble()
        }
        assertTrue(anteil(0.10f, 0.45f) > 0.95, "vor dem Jetzt nicht durchgezogen: ${anteil(0.10f, 0.45f)}")
        val danach = anteil(0.52f, 0.95f)
        assertTrue(danach in 0.3..0.75, "nach dem Jetzt nicht gestrichelt: $danach")

        // Langer Druck: drei eigene Praeparate und der Espresso - mehr nicht.
        val lang = g.langDruecke.map { g.texteUnter(it).first() }
        assertEquals(listOf("Multivitamin", "Zinc", "D3", "Espresso, sugar"), lang)
        g.langDruecke.last().config[SemanticsActions.OnLongClick].action!!.invoke()
        assertEquals(listOf<Bearbeitung>(Bearbeitung.Getraenk("Espresso, sugar", b.um(6, 44), "k1")), bearbeitet)
        g.langDruecke.first().config[SemanticsActions.OnLongClick].action!!.invoke()
        val p = bearbeitet.last() as Bearbeitung.Praeparate
        assertEquals(listOf("p0"), p.ids)
        // "Add caffeine" bleibt erreichbar: Koffein von Hand und die Eintraege von gestern.
        g.alle().first { it.config.getOrNull(SemanticsActions.OnClick) != null && "Add caffeine" in g.texteUnter(it) }
            .config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(1, dazu)
    }

    /** Die Pixel in Bernstein links in einer Zeile - dort steht der Ring eines offenen Praeparats. */
    private fun bernsteinLinks(g: Gezeichnet, r: Rect): Int =
        (r.top.toInt() until r.bottom.toInt()).sumOf { y -> (r.left.toInt() until (r.left + 60 * g.dichte).toInt()).count { g.bei(it, y) == g.f.warnung } }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h1200dp-xxhdpi")
    fun stack_abends_mit_versaeumtem_magnesium() {
        val b = Healthbeispiel
        val abends: Instant = b.um(20, 30)
        val g = zeichne("stack-abend") { StackInhalt(b.stand, abends, {}, {}, {}) }
        assertTrue("still open" in g.texte, "${g.texte}")
        val magnesium = g.beschrieben("Magnesium")
        assertEquals(
            "Magnesium still open, ${Formate.uhrzeit(g.context, LocalTime.of(20, 0))}",
            magnesium.config[SemanticsProperties.ContentDescription].single(),
        )
        assertTrue(bernsteinLinks(g, magnesium.boundsInRoot) > 50, "kein Bernstein-Ring vor Magnesium um 20:30")
        // Das Jetzt um 20:30: 870 von 960 Minuten.
        val r = g.beschrieben("Caffeine,").boundsInRoot
        assertEquals(870.0 / 960, (jetztLinie(g, r) - r.left) / r.width, 0.01)
    }

    /** Was keine Daten hat, entfaellt: ohne SupCycle keine Praeparate, ohne Drinktervall kein Koffein. */
    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h900dp-xxhdpi")
    fun ohne_uhr_app_entfaellt_ihr_teil() {
        val b = Healthbeispiel
        val ohneSupCycle = zeichne("stack-ohne-supcycle") { StackInhalt(b.stand.copy(mitSupCycle = false), b.jetzt, {}, {}, {}) }
        assertTrue("SUPPLEMENTS" !in ohneSupCycle.texte && "CAFFEINE" in ohneSupCycle.texte, "${ohneSupCycle.texte}")
        val ohneDrinktervall = zeichne("stack-ohne-drinktervall") { StackInhalt(b.stand.copy(mitDrinktervall = false), b.jetzt, {}, {}, {}) }
        assertTrue("SUPPLEMENTS" in ohneDrinktervall.texte && "CAFFEINE" !in ohneDrinktervall.texte, "${ohneDrinktervall.texte}")
    }
}
