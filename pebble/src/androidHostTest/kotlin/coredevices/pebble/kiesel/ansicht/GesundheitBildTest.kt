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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import org.robolectric.android.controller.ActivityController
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
 * build/tagesband-luecke.png (die Pulslinie ueber einer Luecke),
 * build/health-ohne-koffein*.png (Stack ohne Koffein),
 * build/tagesband-ruhig.png und -bewegt.png (die Bezugslinien hinter dem Puls)
 * und build/tagesband-ohne-nacht.png und -ohne-ruhepuls.png (ein Tag ohne Nacht).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GesundheitBildTest {

    private class Gezeichnet(
        val bild: Bitmap, val wurzel: SemanticsNode, val primaer: Int, val dichte: Float, val ansicht: View,
        val steuerung: ActivityController<ComponentActivity>,
    ) {
        val breite = bild.width
        val pixel = IntArray(bild.width * bild.height).also { bild.getPixels(it, 0, bild.width, 0, 0, bild.width, bild.height) }
        fun bei(x: Int, y: Int) = pixel[y * breite + x]

        fun alle(n: SemanticsNode = wurzel): List<SemanticsNode> = listOf(n) + n.children.flatMap { alle(it) }
        val texte: List<String> get() = alle().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        val beschreibungen: List<String> get() = alle().flatMap { n -> n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
    }

    private fun zeichne(name: String, inhalt: @Composable () -> Unit): Gezeichnet {
        val steuerung = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val aktivitaet = steuerung.get()
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
        return Gezeichnet(bild, wurzel.semanticsOwner.unmergedRootSemanticsNode, primaer, aktivitaet.resources.displayMetrics.density, ansicht, steuerung)
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

    /** Wo die gruene Jetzt-Linie im Band steht, als Anteil seiner sichtbaren Breite. */
    private fun jetztAnteil(g: Gezeichnet): Double {
        val r = band(g).boundsInRoot
        return (jetztLinie(g, r.left.toInt(), r.right.toInt(), r.top.toInt(), r.bottom.toInt()) - r.left) / r.width
    }

    /**
     * JEDES OEFFNEN DER APP stellt das Jetzt wieder auf drei Viertel, auch nach Stunden im
     * Hintergrund. Dazwischen bleibt das Band stehen, auch wenn die Zeit weiterlaeuft, und der
     * Aufbau der Seite zaehlt nicht als Oeffnen.
     */
    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h1500dp-xxhdpi")
    fun jedes_oeffnen_der_app_stellt_das_jetzt_auf_drei_viertel() {
        val b = Healthbeispiel
        var jetzt by mutableStateOf(b.jetzt)
        var oeffnung = -1
        val g = zeichne("health-oeffnen") {
            val o = rememberOeffnung()
            oeffnung = o
            GesundheitInhalt(b.stand, b.sport, 78, jetzt, imperial = false, b.wege, o) {}
        }
        assertEquals(0, oeffnung, "das ON_START beim Aufbau zaehlte als Oeffnen")
        assertEquals(0.75, jetztAnteil(g), 0.01)

        // Eine Stunde spaeter, die App blieb offen: das Band steht, das Jetzt rueckt 40 dp nach rechts.
        jetzt = b.jetzt.plusSeconds(3600)
        shadowOf(Looper.getMainLooper()).idle()
        val spaeter = nochmal(g, "health-oeffnen-spaeter")
        assertEquals(0.75 + 40 * g.dichte / band(spaeter).boundsInRoot.width, jetztAnteil(spaeter), 0.01)

        // In den Hintergrund und wieder geoeffnet: das Jetzt steht wieder bei drei Vierteln.
        // restart wie Android (onRestart, dann onStart): erst das hebt den Stopp des Fensters auf.
        g.steuerung.pause().stop()
        g.steuerung.restart().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, oeffnung)
        assertEquals(0.75, jetztAnteil(nochmal(g, "health-oeffnen-wieder")), 0.01)
        // NICHTS LIEGEN LASSEN: Das Zeichnen legt das Band neu aus und schreibt dabei Zustand. Bliebe
        // dessen Meldung im Loop, verwuerfe Robolectric sie, und Compose zeichnete in den Tests danach
        // nach keiner Aenderung mehr neu (GlobalSnapshotManager meldet nur, was noch nicht gemeldet ist).
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Dasselbe noch einmal gezeichnet, nach einer Geste: neues Bild, neue Semantik. */
    private fun nochmal(g: Gezeichnet, name: String): Gezeichnet {
        val bild = Bitmap.createBitmap(g.ansicht.width, g.ansicht.height, Bitmap.Config.ARGB_8888)
        g.ansicht.draw(Canvas(bild))
        File("build/$name.png").outputStream().use { bild.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val wurzel = (g.ansicht as ViewGroup).getChildAt(0) as ViewRootForTest
        return Gezeichnet(bild, wurzel.semanticsOwner.unmergedRootSemanticsNode, g.primaer, g.dichte, g.ansicht, g.steuerung)
    }

    /** Ein Finger wischt waagrecht von [vonX] nach [nachX] auf Hoehe [y] (Pixel im Bild). */
    private fun wische(g: Gezeichnet, vonX: Float, nachX: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        g.ansicht.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, vonX, y, 0))
        val schritte = 24
        for (i in 1..schritte) {
            g.ansicht.dispatchTouchEvent(MotionEvent.obtain(t, t + i * 16L, MotionEvent.ACTION_MOVE, vonX + (nachX - vonX) * i / schritte, y, 0))
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16))
        }
        g.ansicht.dispatchTouchEvent(MotionEvent.obtain(t, t + (schritte + 1) * 16L, MotionEvent.ACTION_UP, nachX, y, 0))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
    }

    /** Wie oft [farbe] in der Pixelzeile [y] zwischen [links] und [rechts] steht. */
    private fun zaehle(g: Gezeichnet, farbe: Int, links: Int, rechts: Int, y: Int): Int = (links until rechts).count { g.bei(it, y) == farbe }

    /** Die Pixelzeile zwischen [oben] und [unten], in der [farbe] am haeufigsten steht. */
    private fun zeileMit(g: Gezeichnet, farbe: Int, links: Int, rechts: Int, oben: Int, unten: Int): Int =
        (oben until unten).maxBy { zaehle(g, farbe, links, rechts, it) }

    /** Die Laengen der Laeufe von [farbe] in der Pixelzeile [y], in dp - Striche oder Punkte. */
    private fun laeufe(g: Gezeichnet, farbe: Int, links: Int, rechts: Int, y: Int): List<Float> {
        val aus = mutableListOf<Float>()
        var lauf = 0
        for (x in links until rechts) {
            if (g.bei(x, y) == farbe) lauf++ else { if (lauf > 0) aus += lauf / g.dichte; lauf = 0 }
        }
        return aus
    }

    /**
     * DIE BEZUGSLINIEN HINTER DEM PULS, an einem ruhigen und einem bewegten
     * Tag, so breit wie auf dem Telefon. Nachgesehen am Bild:
     * - Die Skala: unten Ruhepuls 55 minus 10, oben die erste Zonengrenze
     *   ueber dem hoechsten Wert plus Luft - ruhig (bis 104) die Grenze zu
     *   moderat 107, bewegt (bis gut 150) die zur Spitze 159. Jede Linie
     *   steht auf der Hoehe, die ihr Wert in dieser Skala hat.
     * - Der Ruhepuls gestrichelt (Striche 4 dp), in gedaempftem Herzrot -
     *   zwischen der Pulsfarbe und der Karte; die Grenzen gepunktet (2-dp-
     *   Punkte) in ihrer Zonenfarbe. Grenzen ueber der Skala fehlen.
     * - Die Zahlen stehen rechts NEBEN dem Band, je eine auf der Hoehe ihrer
     *   Linie; das Band blendet an seinem rechten Rand weiter aus.
     * - Beim Wischen bleiben die Zahlen, wo sie sind, waehrend das Band wandert.
     * - Die Beschreibung fuer TalkBack nennt den Ruhepuls.
     * - Die Linien stehen HINTER dem Puls: liegt er auf dem Ruhepuls, deckt er sie.
     */
    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h600dp-xxhdpi")
    fun bezugslinien_an_einem_ruhigen_und_einem_bewegten_tag() {
        val b = Healthbeispiel
        class Fall(val name: String, val puls: List<Pair<Long, Int>>, val hoch: Int, val grenzen: List<Int>, val fehlen: List<Int>)
        val faelle = listOf(
            Fall("tagesband-ruhig", b.bandPuls, 107 + 5, listOf(107), listOf(133, 159)),
            Fall("tagesband-bewegt", b.bewegterPuls, 159 + 5, listOf(107, 133, 159), emptyList()),
        )
        assertEquals(listOf(107, 133, 159), b.pulsZonen)
        assertEquals(55, b.nacht.ruhepuls)
        var ruhefarbeGesehen = 0
        for (f in faelle) {
            var pulsfarbe = 0
            var karte = 0
            var zonenfarben = emptyList<Int>()
            var grau = 0
            val g = zeichne(f.name) {
                pulsfarbe = Farben.puls.ton().toArgb()
                karte = Farben.karte().toArgb()
                grau = Farben.neutral().toArgb()
                zonenfarben = Farben.pulszonen.map { it.ton().toArgb() }
                Box(Modifier.padding(12.dp)) { Tagesband(b.stand.copy(bandPuls = f.puls), emptyList(), b.jetzt, imperial = false, b.wege) {} }
            }
            val band = band(g)
            val r = band.boundsInRoot
            val d = g.dichte
            // Die Pulszeile reicht von 26 dp (oben) bis 96 dp (unten) unter dem Kopf des Bands.
            fun y(bpm: Int): Float = r.top + (96 - 70f * (bpm - 45) / (f.hoch - 45)) * d
            val links = (r.left + 30 * d).toInt()
            val rechts = (r.right - 30 * d).toInt()
            // Links vom Jetzt liegt die Karte bloss; rechts davon ist die Zukunft blass hinterlegt.
            val vorJetzt = (jetztLinie(g, r.left.toInt(), r.right.toInt(), r.top.toInt(), r.bottom.toInt()) - 10 * d).toInt()
            fun naheBei(bpm: Int) = (y(bpm) - 3 * d).toInt() to (y(bpm) + 3 * d).toInt()

            // Die Ruhelinie: die haeufigste Farbe in ihrer Zeile vor dem Jetzt, weder Karte noch Puls,
            // und GEDAEMPFT - in jedem Kanal zwischen der Karte und dem Herzrot.
            val (o, u) = naheBei(55)
            val ruhefarbe = (o until u).flatMap { yy -> (links until vorJetzt).map { g.bei(it, yy) } }
                .filter { it != karte && it != pulsfarbe }.groupingBy { it }.eachCount().maxBy { it.value }.key
            for (k in listOf(16, 8, 0)) {
                val (a, z, w) = listOf(karte, ruhefarbe, pulsfarbe).map { (it shr k) and 0xFF }
                assertTrue(w != a && z in minOf(a, w) + 1 until maxOf(a, w), "Kanal $k: Karte $a, Ruhelinie $z, Puls $w")
            }
            val ruheZeile = zeileMit(g, ruhefarbe, links, rechts, o, u)
            assertEquals(y(55), ruheZeile.toFloat(), 1.5f * d, "${f.name}: Ruhelinie")
            // Gestrichelt: Striche von 4 dp, keine Punkte.
            val striche = laeufe(g, ruhefarbe, links, rechts, ruheZeile).filter { it > 1 }
            assertTrue(striche.size > 10 && striche.sorted()[striche.size / 2] in 3f..5f, "${f.name}: Striche $striche")

            // Die Grenzen: gepunktet in ihrer Zonenfarbe, auf ihrer Hoehe; die ueber der Skala fehlen.
            f.grenzen.forEach { w ->
                val farbe = zonenfarben[b.pulsZonen.indexOf(w) + 1]
                val (go, gu) = naheBei(w)
                val zeile = zeileMit(g, farbe, links, rechts, go, gu)
                assertEquals(y(w), zeile.toFloat(), 1.5f * d, "${f.name}: Grenze $w")
                val punkte = laeufe(g, farbe, links, rechts, zeile)
                assertTrue(punkte.size > 10 && punkte.sorted()[punkte.size / 2] < 2.6f, "${f.name}: Punkte $w $punkte")
            }
            // Am bewegten Tag laeuft die Grenze 107 durch den Puls von jetzt ("68 bpm"): er steht auf dem
            // Grund der Zukunft, kein Punkt der Linie zwischen seinen Ziffern.
            if (f.grenzen.size == 3) {
                val farbe = zonenfarben[1]
                val zeile = zeileMit(g, farbe, links, vorJetzt, naheBei(107).first, naheBei(107).second)
                val jx = vorJetzt + 10 * d
                assertEquals(0, zaehle(g, farbe, (jx + 14 * d).toInt(), (jx + 50 * d).toInt(), zeile), "Linie durch \"68 bpm\"")
            }
            val pulszeile = (r.top + 20 * d).toInt() until (r.top + 100 * d).toInt()
            f.fehlen.forEach { w ->
                val farbe = zonenfarben[b.pulsZonen.indexOf(w) + 1]
                assertEquals(0, pulszeile.sumOf { zaehle(g, farbe, r.left.toInt(), r.right.toInt(), it) }, "${f.name}: Grenze $w steht da")
            }

            // DAS AUSBLENDEN BLEIBT: in den letzten 8 dp des Bands kein voller Strich der Ruhelinie,
            // davor schon - und die Zahlen stehen erst rechts davon.
            assertEquals(0, zaehle(g, ruhefarbe, (r.right - 8 * d).toInt(), r.right.toInt(), ruheZeile), "${f.name}: Rand nicht ausgeblendet")
            assertTrue(zaehle(g, ruhefarbe, (r.right - 60 * d).toInt(), (r.right - 20 * d).toInt(), ruheZeile) > 0)

            // Die Zahlen: rechts neben dem Band, je eine Gruppe von Zeilen auf der Hoehe ihrer Linie.
            val spalte = (r.right.toInt() + 1) until (r.right + 28 * d).toInt()
            fun zahlzeilen(h: Gezeichnet) = (r.top.toInt() until r.bottom.toInt()).filter { yy -> spalte.any { h.bei(it, yy) != karte } }
            val zeilen = zahlzeilen(g)
            val gruppen = zeilen.fold(mutableListOf<MutableList<Int>>()) { acc, yy ->
                if (acc.isEmpty() || yy - acc.last().last() > 1) acc += mutableListOf(yy) else acc.last() += yy
                acc
            }
            val erwartet = listOf(55) + f.grenzen
            assertEquals(erwartet.size, gruppen.size, "${f.name}: Zahlen in Zeilen ${gruppen.map { it.first() to it.last() }}")
            gruppen.sortedByDescending { it.first() }.zip(erwartet).forEach { (gruppe, w) ->
                assertEquals(y(w), gruppe.average().toFloat(), 2f * d, "${f.name}: Zahl $w")
                // Klein wie die Achsen: Ziffern unter 10 dp hoch (labelSmall), nicht wie der Puls daneben.
                assertTrue(gruppe.size < 10 * d, "${f.name}: Zahl $w ist ${gruppe.size / d} dp hoch")
            }
            // Grau wie die Achsen: die haeufigste Schriftfarbe der Spalte ist das Grau der Nebenschrift.
            val schrift = zeilen.flatMap { yy -> spalte.map { g.bei(it, yy) } }.filter { it != karte }
                .groupingBy { it }.eachCount().maxBy { it.value }.key
            assertEquals(grau, schrift, "${f.name}: Farbe der Zahlen")

            // Was TalkBack hoert, nennt den Ruhepuls der Linie - einmal, im Text der Nacht, obwohl
            // daneben auch die Zonen einen haben.
            val satz = band.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().single()
            assertTrue("resting 55" in satz, satz)
            assertEquals(1, Regex("resting").findAll(satz).count(), satz)

            // BEIM WISCHEN: das Band wandert nach rechts (frueher am Tag), die Zahlen bleiben Pixel fuer Pixel.
            wische(g, r.left + r.width * 0.2f, r.left + r.width * 0.8f, r.top + 60 * d)
            val danach = nochmal(g, "${f.name}-gewischt")
            val vorher = jetztLinie(g, r.left.toInt(), r.right.toInt(), r.top.toInt(), r.bottom.toInt())
            val gruen = (r.left.toInt() until r.right.toInt()).filter { x -> danach.bei(x, (r.top + 60 * d).toInt()) == danach.primaer }
            assertTrue(gruen.isEmpty() || gruen.average() > vorher + 100 * d, "${f.name}: das Band ist nicht gewandert")
            for (yy in r.top.toInt() until r.bottom.toInt()) for (x in spalte) {
                if (g.bei(x, yy) != danach.bei(x, yy)) throw AssertionError("${f.name}: Zahlenspalte veraendert bei $x, $yy")
            }
            assertEquals(zeilen, zahlzeilen(danach))
            ruhefarbeGesehen = ruhefarbe
        }

        // HINTER DEM PULS: liegt der Puls genau auf dem Ruhepuls, deckt er die Ruhelinie - vor dem Jetzt
        // ist kein Strich von ihr zu sehen, dahinter (keine Pulslinie mehr) schon.
        val flach = b.bandPuls.map { it.first to 55 }
        val g = zeichne("tagesband-flach") {
            Box(Modifier.padding(12.dp)) { Tagesband(b.stand.copy(bandPuls = flach), emptyList(), b.jetzt, imperial = false, b.wege) {} }
        }
        val r = band(g).boundsInRoot
        val d = g.dichte
        val jx = jetztLinie(g, r.left.toInt(), r.right.toInt(), r.top.toInt(), r.bottom.toInt())
        // Puls nur 55: unten 45, oben die Grenze 107 plus 5.
        val zeile = (r.top + (96 - 70f * 10 / 67) * d).toInt()
        val nahe = (zeile - 2 * d).toInt()..(zeile + 2 * d).toInt()
        assertEquals(0, nahe.sumOf { zaehle(g, ruhefarbeGesehen, (r.left + 30 * d).toInt(), (jx - 10 * d).toInt(), it) }, "Ruhelinie vor dem Puls")
        assertTrue(nahe.sumOf { zaehle(g, ruhefarbeGesehen, (jx + 10 * d).toInt(), (r.right - 20 * d).toInt(), it) } > 0, "keine Ruhelinie nach dem Jetzt")
    }

    /**
     * OHNE NACHT VON HEUTE (Uhr nachts nicht getragen) zieht der Ruhepuls der
     * Zonen die Linie - derselbe, mit dem die Grenzen gerechnet sind. Am
     * ruhigen Tag des Beispiels, ab 22:00 gestern wie ein Band ohne Nacht:
     * - Mit ihm (55): die Ruhelinie gestrichelt auf ihrer Hoehe, unten 45
     *   (Ruhepuls - 10), Zahlen 55 und 107 rechts; TalkBack nennt "resting 55",
     *   ohne Schlaftext.
     * - Kennt Boulder keinen (die Zonen auf dem Ersatz): keine Ruhelinie,
     *   unten der tiefste Wert 52 - 10, nur die Zahl 107, kein "resting".
     * - Ohne Puls steht keine Linie da, und TalkBack nennt keinen Ruhepuls.
     */
    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h600dp-xxhdpi")
    fun ohne_nacht_zieht_der_ruhepuls_der_zonen_die_linie() {
        val b = Healthbeispiel
        val ohneNacht = b.stand.copy(nacht = null, bandPuls = b.bandPuls.filter { it.first >= b.um(22, 0, b.heute.minusDays(1)).epochSecond })
        assertEquals(55, ohneNacht.ruhepulsZonen)
        assertEquals(52, ohneNacht.bandPuls.minOf { it.second })
        assertEquals(104, ohneNacht.bandPuls.maxOf { it.second })
        // Die Farbe der Ruhelinie, am ersten Bild abgelesen wie in bezugslinien_an_einem_ruhigen...
        var ruhefarbe = 0
        for ((name, ruhe) in listOf("tagesband-ohne-nacht" to 55, "tagesband-ohne-ruhepuls" to null)) {
            var karte = 0
            var pulsfarbe = 0
            val g = zeichne(name) {
                pulsfarbe = Farben.puls.ton().toArgb()
                karte = Farben.karte().toArgb()
                Box(Modifier.padding(12.dp)) {
                    Tagesband(ohneNacht.copy(ruhepulsZonen = ruhe), emptyList(), b.jetzt, imperial = false, b.wege) {}
                }
            }
            val band = band(g)
            val r = band.boundsInRoot
            val d = g.dichte
            val tief = if (ruhe != null) 45 else 42
            fun y(bpm: Int): Float = r.top + (96 - 70f * (bpm - tief) / (112 - tief)) * d
            val links = (r.left + 30 * d).toInt()
            val rechts = (r.right - 30 * d).toInt()
            val satz = band.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().single()
            assertTrue(" h" !in satz, "Schlaftext ohne Nacht: $satz")
            // Die Zahlen rechts neben dem Band, je eine Gruppe von Zeilen auf der Hoehe ihrer Linie.
            val spalte = (r.right.toInt() + 1) until (r.right + 28 * d).toInt()
            val gruppen = (r.top.toInt() until r.bottom.toInt()).filter { yy -> spalte.any { g.bei(it, yy) != karte } }
                .fold(mutableListOf<MutableList<Int>>()) { acc, yy ->
                    if (acc.isEmpty() || yy - acc.last().last() > 1) acc += mutableListOf(yy) else acc.last() += yy
                    acc
                }
            val erwartet = listOfNotNull(ruhe, 107)
            assertEquals(erwartet.size, gruppen.size, "$name: Zahlen in Zeilen ${gruppen.map { it.first() to it.last() }}")
            gruppen.sortedByDescending { it.first() }.zip(erwartet).forEach { (gruppe, w) ->
                assertEquals(y(w), gruppe.average().toFloat(), 2f * d, "$name: Zahl $w")
            }
            if (ruhe != null) {
                val vorJetzt = (jetztLinie(g, r.left.toInt(), r.right.toInt(), r.top.toInt(), r.bottom.toInt()) - 10 * d).toInt()
                ruhefarbe = ((y(55) - 3 * d).toInt() until (y(55) + 3 * d).toInt())
                    .flatMap { yy -> (links until vorJetzt).map { g.bei(it, yy) } }
                    .filter { it != karte && it != pulsfarbe }.groupingBy { it }.eachCount().maxBy { it.value }.key
            }
            // Die Zeile mit den meisten Pixeln der Ruhefarbe in der Pulszeile.
            val pulszeile = (r.top + 20 * d).toInt() until (r.top + 100 * d).toInt()
            val meiste = pulszeile.maxBy { zaehle(g, ruhefarbe, links, rechts, it) }
            if (ruhe != null) {
                assertEquals(y(55), meiste.toFloat(), 1.5f * d, "$name: Ruhelinie")
                val striche = laeufe(g, ruhefarbe, links, rechts, meiste).filter { it > 1 }
                assertTrue(striche.size > 10 && striche.sorted()[striche.size / 2] in 3f..5f, "$name: Striche $striche")
                assertTrue("resting 55" in satz, satz)
            } else {
                assertTrue(laeufe(g, ruhefarbe, links, rechts, meiste).count { it > 1 } < 3, "$name: Ruhelinie ohne Ruhepuls")
                assertTrue("resting" !in satz, satz)
            }
        }
        // Ohne Puls keine Linie - und kein "resting" fuer eine Linie, die nicht dasteht.
        val ohnePuls = zeichne("tagesband-ohne-nacht-ohne-puls") {
            Box(Modifier.padding(12.dp)) { Tagesband(ohneNacht.copy(bandPuls = emptyList()), emptyList(), b.jetzt, imperial = false, b.wege) {} }
        }
        val satz = band(ohnePuls).config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().single()
        assertTrue("resting" !in satz, satz)
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
