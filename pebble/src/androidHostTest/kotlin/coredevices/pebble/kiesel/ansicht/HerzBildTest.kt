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
import coredevices.pebble.health.schreibeNaechte
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.HrvReparatur
import coredevices.pebble.kiesel.Koerperzeile
import coredevices.pebble.kiesel.ablauf.FakeAkte
import coredevices.pebble.kiesel.ablauf.naechteApi
import coredevices.pebble.kiesel.ablauf.naechteImSpeicher
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import kotlinx.coroutines.runBlocking
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
 * Blutdruck zwei Baender, der Strich je Tag und die Punkte daneben
 * bernstein, die Karte Body vor der Zeile Trends am Ende - und mit wenig
 * Verlauf das Band schon da, die Punkte aber neutral (Band ab drei Werten,
 * Urteil ab sieben).
 *
 * Die Bilder liegen danach unter build/herzseite.png,
 * build/herzseite-hrv4-blutdruck1.png, build/herzseite-hrv8-blutdruck5.png,
 * build/herzseite-ohne-waage.png und build/herzseite-blutdruck-ohne-woche.png,
 * dazu die HRV-Woche vor und nach der Reparatur (0.52):
 * build/herzseite-hrv-vor-reparatur.png und build/herzseite-hrv-nach-reparatur.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h1720dp-xxhdpi")
class HerzBildTest {

    private val context get() = RuntimeEnvironment.getApplication()

    /** Die Farben, wie die Seite sie nimmt - gelesen, nicht nachgebaut. */
    private class Farbsatz(val imBand: Int, val ausserBand: Int, val bandflaeche: Int, val neutral: Int, val karte: Int)

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
    private fun zeichne(
        name: String,
        s: GesundheitsStand,
        koerper: Koerperzeile? = Herzbeispiel.koerper,
        oeffneTrend: () -> Unit = {},
        oeffneNacht: (Long) -> Unit = {},
        oeffneKoerper: () -> Unit = {},
    ): Gezeichnet {
        val aktivitaet = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        lateinit var farben: Farbsatz
        aktivitaet.setContent {
            MaterialTheme(colorScheme = greyScheme) {
                farben = Farbsatz(
                    Farben.imBand.ton().toArgb(), Farben.ausserBand.ton().toArgb(),
                    Farben.imBand.flaeche().copy(alpha = Farben.BAND_DECKUNG).compositeOver(Farben.karte()).toArgb(),
                    Farben.neutral().toArgb(), Farben.karte().toArgb(),
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
                    Box(Modifier.padding(innen)) { HerzInhalt(s, Herzbeispiel.jetzt, koerper, imperial = false, oeffneNacht, oeffneTrend, oeffneKoerper) }
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
        var koerper = 0
        val g = zeichne("herzseite", Herzbeispiel.stand(), oeffneTrend = { trends++ }, oeffneNacht = { naechte++ }, oeffneKoerper = { koerper++ })

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
            g.mitText("BODY"),
            g.mitText("What makes a difference"),
        ).map { it.boundsInRoot.top }
        assertEquals(folge.sorted(), folge, "Reihenfolge")

        // EIN WEG ZU DEN TRENDS: "Trends" steht nur noch in der letzten Zeile, nicht mehr als Link in YOUR USUAL.
        assertEquals(1, g.texte.count { it == context.getString(R.string.ks_h_trends) }, "${g.texte}")
        val gewohnt = g.mitText("YOUR USUAL")
        val nebenGewohnt = g.alle().filter { g.text(it) != null && it.id != gewohnt.id && abs(it.boundsInRoot.center.y - gewohnt.boundsInRoot.center.y) < 6 * g.dichte }
        assertTrue(nebenGewohnt.isEmpty(), "neben YOUR USUAL: ${nebenGewohnt.map { g.text(it) }}")

        // DIE KARTE BODY vor den Trends: Gewicht gross, Fett und Veraenderung wie bis 0.51 die Zeile auf Health.
        assertTrue("69.6 kg" in g.texte, "${g.texte}")
        g.mitText("21.6% fat · −0.1 kg in 30 days")
        // "Body >" fuehrt zur Koerperseite.
        var link: SemanticsNode? = g.mitText(context.getString(R.string.ks_kp_titel))
        while (link != null && link.config.getOrNull(SemanticsActions.OnClick) == null) link = link.parent
        assertNotNull(link, "Body ist kein Link").config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(1, koerper)

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
        assertEquals(Triple(1, 0, 1), Triple(trends, naechte, koerper))

        // DIE LINIE ZWISCHEN DEN PUNKTEN NEUTRAL wie im Entwurf: gruen sind nur die sechs Punkte im Band
        // (48 53 46 55 52 und 50 mit seinem Ring), keine Linie dazwischen - jeder gruene Fleck ist rund und klein.
        val gruen = g.flecken(hrv, g.farben.imBand, (10 * g.dichte * g.dichte).toInt())
        assertEquals(7, gruen.size, "gruene Flecken: $gruen")
        // GROSSE PUNKTE (6 dp Radius): die fuenf Punkte ohne Ring sind je ueber 85 dp² gross.
        val punktflaeche = g.pixelIn(hrv) { it == g.farben.imBand }.size
        assertTrue(punktflaeche > 6 * 85 * g.dichte * g.dichte, "Punkte zu klein: $punktflaeche px")
    }

    /** Ein Punkt im Bild: seine Mitte und ob er gruen, bernstein oder neutral ist. */
    private class Punkt(val x: Double, val y: Double, val farbe: Int)

    /**
     * Die Punkte (und Ringe) im Rechteck: zusammenhaengende Flecken genau einer
     * Punktfarbe ueber 40 dp² - groesser als jede Ziffer der Achse, die in
     * derselben grauen Farbe steht.
     */
    private fun punkte(g: Gezeichnet, r: Rect): List<Punkt> = listOf(g.farben.imBand, g.farben.ausserBand, g.farben.neutral).flatMap { f ->
        g.flecken(r, f, (40 * g.dichte * g.dichte).toInt()).map { (x, y) -> Punkt(x, y, f) }
    }

    /** Die Tagesmitten mit Punkt, von links: Punkte und Ringe desselben Tages zusammen. */
    private fun tagesmitten(g: Gezeichnet, punkte: List<Punkt>): List<Double> =
        punkte.map { it.x }.sorted().fold(listOf<Double>()) { l, x -> if (l.isNotEmpty() && x - l.last() < 3 * g.dichte) l else l + x }

    /**
     * Wie weit die Bandflaeche im Rechteck links und rechts reicht (Pixel).
     * Gezaehlt werden Spalten mit mindestens 3 dp Flaeche: der weiche Rand
     * eines gruenen Rings mischt sich mit der Karte vereinzelt zum Ton der
     * Flaeche, ist aber keine.
     */
    private fun bandBreite(g: Gezeichnet, r: Rect): Pair<Int, Int> {
        val xs = g.pixelIn(r) { naheFarbe(it, g.farben.bandflaeche) }.groupingBy { it.first }.eachCount()
            .filter { it.value >= 3 * g.dichte }.keys
        assertTrue(xs.isNotEmpty(), "keine Bandflaeche")
        return xs.min() to xs.max()
    }

    /** Ob ein Pixel grau ist und sich von der Karte abhebt - die neutrale Linie, nicht Band, nicht Karte. */
    private fun grauAufKarte(g: Gezeichnet, p: Int): Boolean {
        val (r, gr, b) = listOf(16, 8, 0).map { p shr it and 0xFF }
        val karte = g.farben.karte shr 8 and 0xFF
        return abs(r - gr) <= 8 && abs(gr - b) <= 8 && abs(r - b) <= 8 && gr - karte > 30
    }

    /**
     * Der senkrechte Strich eines Blutdrucktags: der Anteil grauer Pixel in
     * der Spalte [x] zwischen den beiden Punkten (ohne ihre Raender).
     */
    private fun strich(g: Gezeichnet, x: Double, oben: Double, unten: Double): Double {
        val von = (oben + 12 * g.dichte).toInt()
        val bis = (unten - 12 * g.dichte).toInt()
        assertTrue(bis > von, "Punkte zu nah: $oben / $unten")
        return (von until bis).count { y -> grauAufKarte(g, g.bei(x.toInt(), y)) } / (bis - von).toDouble()
    }

    /**
     * WENIG VERLAUF wie auf dem Telefon am 6.10.: vier Naechte mit HRV, eine
     * Blutdruckmessung. DAS HRV-BAND STEHT SCHON (ab drei Werten), aber kein
     * Punkt ist gruen oder bernstein und der Kopf urteilt nicht - die 50 ms
     * von heute liegen unter dem Band und bleiben neutral. Das Band ist eine
     * Flaeche von der Mitte des vierten bis zur Mitte des siebten Tags, keine
     * Treppe bis an die Raender. Beim Blutdruck verbindet ein senkrechter
     * Strich oben und unten der einen Messung; ein Band hat er nicht.
     */
    @Test
    fun wenig_verlauf_band_ohne_urteil_und_strich_der_messung() {
        val g = zeichne("herzseite-hrv4-blutdruck1", Herzbeispiel.stand(Herzbeispiel.hrvVier(), Herzbeispiel.blutdruckEinTag()))
        assertTrue(urteile.none { it in g.texte }, "Urteil ohne gelerntes Band: ${g.texte}")
        assertTrue("50 ms" in g.texte && "116/73 mmHg" in g.texte, "${g.texte}")

        val hrv = g.bild("HRV, last 7 nights:").boundsInRoot
        assertTrue(g.pixelIn(hrv) { it == g.farben.imBand || it == g.farben.ausserBand }.isEmpty(), "HRV: gruen oder bernstein ohne Urteil")
        // Drei Tage mit Punkt: der erste, der dritte und heute; daraus der Abstand der Tage.
        val mitten = tagesmitten(g, punkte(g, hrv))
        assertEquals(3, mitten.size, "Tage mit Punkt: $mitten")
        val tag = (mitten[2] - mitten[0]) / 6
        assertEquals(mitten[0] + 2 * tag, mitten[1], 2.0 * g.dichte)
        // Das Band von der Mitte des vierten Tags (drei Werte davor) bis zur Mitte von heute.
        val (links, rechts) = bandBreite(g, hrv)
        assertEquals(mitten[0] + 3 * tag, links.toDouble(), 2.0 * g.dichte, "Band beginnt nicht in der Mitte des vierten Tags")
        assertEquals(mitten[2], rechts.toDouble(), 2.0 * g.dichte, "Band endet nicht in der Mitte von heute")

        val bd = g.bild("BLOOD PRESSURE, last 7 days:").boundsInRoot
        assertTrue(g.pixelIn(bd) { naheFarbe(it, g.farben.bandflaeche) }.isEmpty(), "Blutdruck: Band aus einer Messung")
        assertTrue(g.pixelIn(bd) { it == g.farben.imBand || it == g.farben.ausserBand }.isEmpty(), "Blutdruck: gruen oder bernstein")
        // Oben und unten heute, je mit Ring: ein Tag, zwei Hoehen.
        val bdPunkte = punkte(g, bd).filter { it.farbe == g.farben.neutral }
        assertEquals(1, tagesmitten(g, bdPunkte).size, "Blutdrucktage: ${bdPunkte.map { it.x }}")
        val (oben, unten) = bdPunkte.minOf { it.y } to bdPunkte.maxOf { it.y }
        assertTrue(strich(g, bdPunkte.first().x, oben, unten) > 0.9, "kein Strich zwischen 116 und 73")
    }

    /**
     * ACHT NAECHTE UND FUENF BLUTDRUCKTAGE: die HRV hat ihr Band ab dem
     * dritten Tag der Woche, und nur heute (sieben Werte davor) ein Urteil -
     * gruen ist allein der Punkt von heute, "In your range" steht einmal.
     * Beim Blutdruck zwei Baender ab Sonntag (drei Tage davor), kein Urteil,
     * jeder der fuenf Tage mit seinem Strich.
     */
    @Test
    fun acht_naechte_urteil_nur_heute_fuenf_blutdrucktage_mit_band() {
        val g = zeichne("herzseite-hrv8-blutdruck5", Herzbeispiel.stand(Herzbeispiel.hrvAcht(), Herzbeispiel.blutdruckFuenfTage()))
        val urteil = g.alle().filter { g.text(it) in urteile }
        assertEquals(listOf(context.getString(R.string.ks_h_im_band)), urteil.map { g.text(it) }, "${g.texte}")
        assertTrue(abs(urteil.single().boundsInRoot.center.y - g.mitText("HRV · last 7 nights").boundsInRoot.center.y) < 4 * g.dichte)
        // Fuenf Sitzungen: der Schnitt steht im Kopf.
        assertTrue(g.texte.any { it.startsWith("Today") && "avg" in it }, "${g.texte}")

        val hrv = g.bild("HRV, last 7 nights:").boundsInRoot
        val hrvPunkte = punkte(g, hrv)
        val mitten = tagesmitten(g, hrvPunkte)
        assertEquals(7, mitten.size, "HRV-Tage: $mitten")
        val tag = (mitten[6] - mitten[0]) / 6
        // Gruen nur heute (Punkt und Ring), bernstein nirgends, die sechs Tage davor neutral.
        val gruen = hrvPunkte.filter { it.farbe == g.farben.imBand }
        assertTrue(gruen.isNotEmpty() && gruen.all { abs(it.x - mitten[6]) < 2 * g.dichte }, "gruen: ${gruen.map { it.x }} / heute ${mitten[6]}")
        assertTrue(hrvPunkte.none { it.farbe == g.farben.ausserBand })
        assertEquals(6, tagesmitten(g, hrvPunkte.filter { it.farbe == g.farben.neutral }).size)
        val (links, rechts) = bandBreite(g, hrv)
        assertEquals(mitten[0] + 2 * tag, links.toDouble(), 2.0 * g.dichte, "HRV-Band beginnt nicht am dritten Tag")
        assertEquals(mitten[6], rechts.toDouble(), 2.0 * g.dichte)

        val bd = g.bild("BLOOD PRESSURE, last 7 days:").boundsInRoot
        val bdPunkte = punkte(g, bd)
        assertTrue(bdPunkte.all { it.farbe == g.farben.neutral }, "Blutdruck ohne Urteil, aber farbig")
        val bdTage = tagesmitten(g, bdPunkte)
        assertEquals(5, bdTage.size, "Blutdrucktage: $bdTage")
        // Die Baender oben und unten ab Sonntag, dem fuenften Tag der Woche.
        val bdTag = (bdTage.last() - bdTage.first()) / 6
        val (bdLinks, bdRechts) = bandBreite(g, bd)
        assertEquals(bdTage.first() + 4 * bdTag, bdLinks.toDouble(), 2.0 * g.dichte, "Blutdruckband beginnt nicht am Sonntag")
        assertEquals(bdTage.last(), bdRechts.toDouble(), 2.0 * g.dichte)
        val hoehen = g.pixelIn(bd) { naheFarbe(it, g.farben.bandflaeche) }.map { it.second }.distinct().sorted()
        assertEquals(1, hoehen.zipWithNext().count { (a, b) -> b - a > 10 * g.dichte }, "Blutdruckband nicht in zwei Hoehen")
        // Jeder Tag mit seinem senkrechten Strich zwischen oben und unten.
        for (x in bdTage) {
            val amTag = bdPunkte.filter { abs(it.x - x) < 3 * g.dichte }
            assertTrue(strich(g, x, amTag.minOf { it.y }, amTag.maxOf { it.y }) > 0.9, "kein Strich am Tag bei x=$x")
        }
    }

    /** OHNE WAAGE KEINE KARTE BODY - wie bis 0.51 die Zeile auf Health. Die Trends bleiben die letzte Zeile. */
    @Test
    fun ohne_waage_keine_karte_body() {
        val g = zeichne("herzseite-ohne-waage", Herzbeispiel.stand(), koerper = null)
        assertTrue("BODY" !in g.texte && "Body" !in g.texte, "${g.texte}")
        g.mitText("What makes a difference")
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

    /**
     * DIE HRV-WOCHE VOR UND NACH DER EINMALIGEN REPARATUR (0.52), aus Boulders
     * echter Nachtdatenbank im Speicher: die Woche wie auf dem Telefon am
     * 6.10. Do (53) und Sa (61) waren mit HRV empfangen und vom Sync in die
     * Akte geschrieben, dann ohne HRV ersetzt (bis 0.51); So und Mo hatten
     * unter b24 nie eine. Die Reparatur liest Boulders eigene Saetze der Akte
     * zurueck - danach fuenf Punkte statt drei, das Band einen Tag frueher,
     * der Schnitt 57 statt 56. So und Mo bleiben leer.
     *
     * Die Woche rechnet die App aus den Naechten ([Gesundheitsrechnung.hrvWoche]),
     * gezeichnet wird mit ihrem Code; die Bilder liegen unter
     * build/herzseite-hrv-vor-reparatur.png und build/herzseite-hrv-nach-reparatur.png.
     */
    @Test
    fun hrv_woche_vor_und_nach_der_reparatur() {
        val zone = Healthbeispiel.zone
        val heute = Herzbeispiel.heute
        val akte = FakeAkte(context.packageName).apply { erteilt = setOf(HrvReparatur.RECHT) }
        val db = naechteImSpeicher(context)
        try {
            val dao = db.nachtDao()
            /** Die Nacht zum Morgen [k] Tage vor heute: ab 22:00 am Vorabend, aufgewacht 06:30. */
            fun nacht(k: Int, hrv: Int) = NachtEntity(
                heute.minusDays(k + 1L).atTime(22, 0).atZone(zone).toEpochSecond(), 7200,
                einschlafen = 30, aufwachen = 510, schlafMinuten = 450, ruhepuls = 54, hrv = hrv,
            )
            val woche = mapOf(9 to 58, 6 to 64, 5 to 53, 4 to 55, 3 to 61, 2 to 0, 1 to 0, 0 to 50).map { (k, hrv) -> k to nacht(k, hrv) }.toMap()
            runBlocking {
                for (n in woche.values) {
                    val phasen = listOf(NachtAbschnittEntity(n.beginnUtc, 30, 510, 1))
                    dao.speichereNachtTeil(n, ersterTeil = true, phasen)
                    assertTrue(schreibeNaechte(context, akte, listOf(n to phasen)))
                }
                // Do und Sa: nach dem Firmware-Update ohne HRV neu, ersetzt wie bis 0.51.
                for (k in listOf(5, 3)) dao.speichereNacht(woche.getValue(k).copy(hrv = 0))
            }
            fun hrvWoche() = runBlocking { Gesundheitsrechnung.hrvWoche(dao.naechte(0, Long.MAX_VALUE), heute, zone) }
            fun stand() = Herzbeispiel.stand(emptyMap(), Herzbeispiel.blutdruckEinTag()).copy(hrvTage = hrvWoche())

            val vorher = zeichne("herzseite-hrv-vor-reparatur", stand())
            val ausgang = runBlocking { HrvReparatur.einmal(context, naechteApi(dao), akte, Herzbeispiel.jetzt) }
            assertEquals(HrvReparatur.Ausgang.Gelaufen(repariert = 2, ohneHrv = 4), ausgang)
            val nachher = zeichne("herzseite-hrv-nach-reparatur", stand())

            // Wie auf dem Telefon: We 64, Fr 55, Tu 50, Schnitt 56 - danach dazu Th 53 und Sa 61, Schnitt 57.
            vorher.mitText("Last night · 7-night avg 56")
            nachher.mitText("Last night · 7-night avg 57")
            assertTrue("50 ms" in vorher.texte && "50 ms" in nachher.texte)
            val mittenVorher = tagesmitten(vorher, punkte(vorher, vorher.bild("HRV, last 7 nights:").boundsInRoot))
            val bildNachher = nachher.bild("HRV, last 7 nights:").boundsInRoot
            val mittenNachher = tagesmitten(nachher, punkte(nachher, bildNachher))
            assertEquals(3, mittenVorher.size, "vorher: $mittenVorher")
            assertEquals(5, mittenNachher.size, "nachher: $mittenNachher")
            // Nachher: die ersten vier Tage und heute; So und Mo ohne Punkt.
            val tag = (mittenNachher.last() - mittenNachher.first()) / 6
            for ((soll, ist) in ((0..3).map { mittenNachher.first() + it * tag } + mittenNachher.last()).zip(mittenNachher)) {
                assertEquals(soll, ist, 2.0 * nachher.dichte)
            }
            // Kein Urteil (weniger als sieben Werte), das Band nachher ab dem dritten Tag statt ab dem vierten.
            assertTrue(nachher.pixelIn(bildNachher) { it == nachher.farben.imBand || it == nachher.farben.ausserBand }.isEmpty())
            assertEquals(mittenNachher.first() + 2 * tag, bandBreite(nachher, bildNachher).first.toDouble(), 2.0 * nachher.dichte)
            val vorherBild = vorher.bild("HRV, last 7 nights:").boundsInRoot
            assertEquals(mittenVorher.first() + 3 * tag, bandBreite(vorher, vorherBild).first.toDouble(), 2.0 * vorher.dichte)
        } finally {
            db.close()
        }
    }
}
