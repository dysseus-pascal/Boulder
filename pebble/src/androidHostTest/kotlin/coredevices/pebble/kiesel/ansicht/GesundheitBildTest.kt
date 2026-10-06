package coredevices.pebble.kiesel.ansicht

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import coredevices.pebble.R
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
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
 * oeffnet), build/tagesband-ganz.png (das ganze Band, ungeschnitten),
 * build/tagesband-luecke.png (die Pulslinie ueber einer Luecke) und
 * build/health-ohne-koffein*.png (Stack ohne Koffein).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GesundheitBildTest {

    private class Gezeichnet(val bild: Bitmap, val wurzel: SemanticsNode, val primaer: Int, val dichte: Float, val ansicht: View) {
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
                // Die Schriftfarbe, die in der App das Scaffold des Home-Bildschirms setzt
                // (onBackground): ohne sie stand der Gruss schwarz auf Grau, ein Fehler nur des Bilds.
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { inhalt() }
                }
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        val ansicht = aktivitaet.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val bild = Bitmap.createBitmap(ansicht.width, ansicht.height, Bitmap.Config.ARGB_8888)
        ansicht.draw(Canvas(bild))
        File("build/$name.png").outputStream().use { bild.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val wurzel = (ansicht as ViewGroup).getChildAt(0) as ViewRootForTest
        return Gezeichnet(bild, wurzel.semanticsOwner.unmergedRootSemanticsNode, primaer, aktivitaet.resources.displayMetrics.density, ansicht)
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
            GesundheitInhalt(b.stand, b.sport, 78, b.jetzt, imperial = false, b.wege) {}
        }
        val band = band(g)
        val r = band.boundsInRoot
        // DAS JETZT BEI DREI VIERTELN der sichtbaren Breite des Bands - die Zahl der Spezifikation, nicht die Konstante.
        val x = jetztLinie(g, r.left.toInt(), r.right.toInt(), r.top.toInt(), r.bottom.toInt())
        val anteil = (x - r.left) / r.width
        assertTrue(abs(anteil - 0.75) < 0.01, "Jetzt bei ${"%.3f".format(anteil)} der Breite")

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
        // KEINE ZEILE BODY MEHR (0.52): der Koerper steht als Karte auf Heart & Body. Die Zeile
        // Heart & Body bleibt, wie sie war - ohne Gewicht oder Fett darin.
        assertTrue("Body" !in texte, "Zeile Body in $texte")
        assertTrue(texte.none { " kg" in it || "fat" in it }, "Koerperwerte auf Health: $texte")
        // Der Satz oben mit seinem Leerzeichen zwischen "sleep" und "and", wie im Entwurf.
        assertTrue(texte.any { "7:59 h of sleep and a resting heart rate as usual." in it }, "$texte")
        // Stack: 3 von 4 genommen, Magnesium kommt erst um 20:00 - noch nicht versaeumt; 63 mg um 6:44
        // wirken um 13:40 noch 24 mg, um 21:46 noch etwa 8.
        val stack = g.beschreibungen.firstOrNull { it.startsWith("Stack") }
        assertEquals("Stack, Magnesium due, 3 of 4 supplements, 24 mg caffeine now, ≈ 8 mg at bedtime", stack)
        // Das Sport-Feld: der Spaziergang von heute, und die Trainings dieser Woche (Montag bis heute) -
        // die Fahrt von gestern zaehlt mit, ausser heute ist Montag; der Lauf vor acht Tagen nie.
        assertTrue(texte.any { it.startsWith("Walk 2.7 km · today") }, "$texte")
        val woche = if (b.heute.dayOfWeek == java.time.DayOfWeek.MONDAY) 1 else 2
        assertTrue("$woche this week" in texte, "$texte")
    }

    /**
     * Die Teile des Satzes, die an "of sleep" anschliessen, beginnen mit
     * ihrem Leerzeichen - alle drei, nicht nur der, den das Bild zeigt.
     */
    @Test
    @Config(sdk = [35])
    fun der_ruhepuls_im_satz_beginnt_mit_seinem_leerzeichen() {
        val c = RuntimeEnvironment.getApplication()
        assertEquals(" and a resting heart rate as usual", c.getString(R.string.ks_f_ruhe_ok))
        assertEquals(" and a resting heart rate 4 bpm above usual", c.getString(R.string.ks_f_ruhe_hoch, 4))
        assertEquals(" and a resting heart rate 4 bpm below usual", c.getString(R.string.ks_f_ruhe_tief, 4))
    }

    /**
     * STACK OHNE KOFFEIN: mit Drinktervall, aber ohne Getraenk mit Koffein
     * in den letzten 24 Stunden gibt es keine Koffeinwerte - kein "0 mg
     * caffeine now", kein "≈ 0 mg at bedtime". Die Praeparate bleiben. Ohne
     * SupCycle bleibt nur der Titel: die Zeile fuehrt weiter zu Stack, wo
     * Koffein eingetragen wird.
     */
    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h1500dp-xxhdpi")
    fun stack_ohne_koffein_nennt_keine_null_mg() {
        val b = Healthbeispiel
        val ohneKoffein = b.stand.copy(koffein = emptyList(), eintraege = b.stand.eintraege.filter { it.praeparat })
        assertTrue(ohneKoffein.mitDrinktervall)
        val g = zeichne("health-ohne-koffein") {
            GesundheitInhalt(ohneKoffein, b.sport, 78, b.jetzt, imperial = false, b.wege) {}
        }
        assertEquals("Stack, Magnesium due, 3 of 4 supplements", g.beschreibungen.firstOrNull { it.startsWith("Stack") })
        assertTrue("caffeine now" !in g.texte && "at bedtime" !in g.texte, "${g.texte}")
        assertTrue("supplements" in g.texte, "${g.texte}")

        val nurDrinktervall = ohneKoffein.copy(mitSupCycle = false)
        val h = zeichne("health-ohne-koffein-ohne-supcycle") {
            GesundheitInhalt(nurDrinktervall, b.sport, 78, b.jetzt, imperial = false, b.wege) {}
        }
        assertEquals("Stack", h.beschreibungen.firstOrNull { it.startsWith("Stack") })
        assertTrue(listOf("supplements", "caffeine now", "at bedtime").none { it in h.texte }, "${h.texte}")
    }

    /**
     * DIE PULSLINIE BRICHT UEBER EINER LUECKE, gemessen an den Messungen:
     * ohne Puls zwischen 10:00 und 10:19 steht um 10:10 keine Linie, davor
     * und danach schon. Die Fenster 10:00 und 10:15 allein haetten die
     * Luecke ueberbrueckt.
     */
    @Test
    @Config(sdk = [35], qualifiers = "w1100dp-h400dp-xxhdpi")
    fun die_pulslinie_bricht_ueber_einer_luecke() {
        val b = Healthbeispiel
        val luecke = b.stand.bandPuls.filter { it.first <= b.um(10, 0).epochSecond || it.first >= b.um(10, 19).epochSecond }
        assertTrue(luecke.any { it.first == b.um(10, 0).epochSecond } && luecke.any { it.first == b.um(10, 19).epochSecond })
        var pulsfarbe = 0
        val g = zeichne("tagesband-luecke") {
            pulsfarbe = Farben.puls.ton().toArgb()
            Box(Modifier.padding(12.dp)) { Tagesband(b.stand.copy(bandPuls = luecke), emptyList(), b.jetzt, imperial = false, b.wege) {} }
        }
        val r = band(g).boundsInRoot
        val von = b.nacht.einschlafen.minusSeconds(15 * 60)
        fun x(h: Int, m: Int) = r.left + (12 + java.time.Duration.between(von, b.um(h, m)).seconds / 3600f * 40) * g.dichte
        // Rot in der Hoehe der Pulslinie (26-96 dp unter dem Kopf des Bands), in einer Spalte von 2 dp.
        fun rot(h: Int, m: Int): Int {
            val mitte = x(h, m).toInt()
            val breit = g.dichte.toInt()
            return (mitte - breit..mitte + breit).sumOf { sx ->
                ((r.top + 22 * g.dichte).toInt()..(r.top + 100 * g.dichte).toInt()).count { sy -> g.bei(sx, sy) == pulsfarbe }
            }
        }
        assertEquals(0, rot(10, 10), "Linie ueber der Luecke")
        assertTrue(rot(9, 50) > 0 && rot(10, 30) > 0, "keine Linie vor oder nach der Luecke")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h600dp-xxhdpi")
    fun kein_alter_puls_neben_dem_jetzt() {
        val b = Healthbeispiel
        // Der letzte Puls ist von 13:38: um 13:53 ist er 15 Minuten alt und steht noch, um 13:54 nicht mehr.
        val frisch = band(zeichne("tagesband-1353") { Tagesband(b.stand, emptyList(), b.jetzt.plusSeconds(13 * 60), false, b.wege) {} })
        assertTrue(frisch.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().single().endsWith(" bpm"))
        val alt = band(zeichne("tagesband-1354") { Tagesband(b.stand, emptyList(), b.jetzt.plusSeconds(14 * 60), false, b.wege) {} })
        assertTrue("bpm" !in alt.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().single())
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

    /**
     * Ein Finger auf [x], [y] (Pixel im Bild), [lange] Millisekunden gehalten.
     * Die Wartezeit des langen Drucks laeuft in Compose auf der echten Uhr,
     * nicht auf der des Loopers - darum wird wirklich gewartet.
     */
    private fun tippe(g: Gezeichnet, x: Float, y: Float, lange: Long = 50) {
        val t = SystemClock.uptimeMillis()
        g.ansicht.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        val ende = System.currentTimeMillis() + lange
        while (System.currentTimeMillis() < ende) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
            Thread.sleep(20)
        }
        g.ansicht.dispatchTouchEvent(MotionEvent.obtain(t, t + lange, MotionEvent.ACTION_UP, x, y, 0))
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * DIE GESTEN DES BANDS, mit dem Finger auf dem gezeichneten Band: die
     * Nacht oeffnet die Schlafansicht, Wasser Water, Kaffee und Praeparat
     * Stack, ein Training sein Detail; der Mond tut nichts. Ein langer Druck
     * auf den eigenen Espresso oeffnet das Blatt zum Aendern - ein Tipp nicht.
     */
    @Test
    @Config(sdk = [35], qualifiers = "w1100dp-h400dp-xxhdpi")
    fun ein_tipp_fuehrt_wohin_das_symbol_gehoert() {
        val b = Healthbeispiel
        val ziele = mutableListOf<String>()
        val wege = Gesundheitswege(
            nacht = { ziele += "nacht $it" }, trend = { ziele += "trend $it" }, herz = { ziele += "herz" }, akku = { ziele += "akku" },
            sport = { ziele += "sport" }, training = { ziele += "training $it" },
            wasser = { ziele += "wasser" }, stack = { ziele += "stack" },
        )
        val g = zeichne("tagesband-gesten") {
            Box(Modifier.padding(12.dp)) { Tagesband(b.stand, b.sport.trainings, b.jetzt, imperial = false, wege) {} }
        }
        val r = band(g).boundsInRoot
        val von = b.nacht.einschlafen.minusSeconds(15 * 60)
        // Die Lage einer Uhrzeit im Band: 12 dp Rand, 40 dp je Stunde - die Masse des Entwurfs.
        fun x(h: Int, m: Int, tag: java.time.LocalDate = b.heute) =
            r.left + (12 + java.time.Duration.between(von, b.um(h, m, tag)).seconds / 3600f * 40) * g.dichte
        // Die Symbole stehen 152 dp unter dem Kopf des Bands, 26 dp hoch: ihre Mitte bei 165 dp.
        val symbol = r.top + 165 * g.dichte
        fun ziel(h: Int, m: Int, y: Float = symbol): String? {
            ziele.clear()
            tippe(g, x(h, m), y)
            return ziele.singleOrNull()
        }
        assertEquals("nacht ${b.nacht.beginnUtc}", ziel(2, 0, r.top + 150 * g.dichte))
        assertEquals("stack", ziel(6, 44))
        assertEquals("stack", ziel(8, 0))
        assertEquals("wasser", ziel(9, 20))
        assertEquals("training ${b.spaziergang.id}", ziel(10, 30))
        assertEquals("wasser", ziel(12, 10))
        assertEquals("stack", ziel(20, 0))
        assertEquals(null, ziel(21, 46))
        assertTrue(ShadowDialog.getLatestDialog()?.isShowing != true, "ein Tipp oeffnete ein Blatt")

        // Lange auf den Espresso: das Blatt zum Aendern, mit seinem Namen - und kein Weg weg von Health.
        ziele.clear()
        tippe(g, x(6, 44), symbol, lange = 1_000)
        assertTrue(ziele.isEmpty(), "$ziele")
        val blatt = assertNotNull(ShadowDialog.getLatestDialog(), "kein Blatt nach langem Druck")
        assertTrue(blatt.isShowing)
        val blattWurzel = (blatt.window!!.decorView as ViewGroup).let { finde(it) }
        val blattTexte = assertNotNull(blattWurzel, "kein Compose im Blatt").let { w ->
            fun texte(n: SemanticsNode): List<String> = n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + n.children.flatMap { texte(it) }
            texte(w.semanticsOwner.unmergedRootSemanticsNode)
        }
        // Der Kopf des Blatts in Versalien wie jeder Kopf (Baukasten), mit der Uhrzeit.
        assertTrue(blattTexte.any { it.startsWith("ESPRESSO, SUGAR") } && "Delete" in blattTexte, "$blattTexte")
    }

    /** Die Compose-Wurzel in einem Fenster (das Blatt ist ein eigenes). */
    private fun finde(v: View): ViewRootForTest? = when (v) {
        is ViewRootForTest -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { finde(v.getChildAt(it)) }
        else -> null
    }
}
