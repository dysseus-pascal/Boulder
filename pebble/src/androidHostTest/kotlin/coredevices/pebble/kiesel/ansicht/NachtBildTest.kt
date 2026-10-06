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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import coredevices.pebble.R
import coredevices.pebble.kiesel.Gesundheitsdaten
import coredevices.pebble.kiesel.Gesundheitsrechnung
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import theme.greyScheme
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * DIE SCHLAFANSICHT, GEZEICHNET - mit dem Code der App und der Nacht des
 * Entwurfs "Erlebnis-Schlaf" (Mo 5. auf Di 6.10.2026, 21:46 bis 6:00), im
 * grauen Schema der App.
 *
 * Nachgesehen wird am Bild und an dem, was TalkBack liest: die Karte
 * "Stages" ist weg, und unten in der Karte mit dem Hypnogramm steht eine
 * Zeile mit vier Spalten - Farbpunkt, Name, Dauer - in der Reihenfolge der
 * Spuren darueber. Die Reihenfolge wird nicht aus dem Code gelesen, sondern
 * am Bild gemessen: der Punkt jeder Spalte hat die Farbe der Spur, die im
 * gezeichneten Hypnogramm an ihrer Stelle liegt.
 *
 * Die Bilder liegen danach unter build/schlafansicht.png und
 * build/schlafansicht-ohne-phasen.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h1400dp-xxhdpi")
class NachtBildTest {

    private val context get() = RuntimeEnvironment.getApplication()

    /** Die Spalten, wie die Spezifikation sie verlangt: Wach, REM, Leicht, Tief - mit den Zeiten der Nacht des Entwurfs. */
    private val erwartet: List<Pair<String, String>> get() = listOf(
        context.getString(R.string.ks_h_wach) to "15 min",
        context.getString(R.string.ks_h_rem) to "1:13 h",
        context.getString(R.string.ks_h_leicht) to "5:17 h",
        context.getString(R.string.ks_h_tief) to "1:29 h",
    )

    private val abend: LocalDate = LocalDate.of(2026, 10, 5)

    /** Der Puls je halbe Stunde von 21:46 bis 6:00 aus dem Entwurf, dazwischen jede Minute gerade verbunden. */
    private fun puls(beginn: Long): List<Pair<Long, Int>> {
        val stuetzen = listOf(60, 57, 55, 54, 53, 52, 53, 54, 52, 53, 55, 54, 56, 55, 57, 56, 58)
        return (0..494).map { m ->
            val lage = m / 494.0 * (stuetzen.size - 1)
            val i = lage.toInt().coerceAtMost(stuetzen.size - 2)
            val a = stuetzen[i]
            val b = stuetzen[i + 1]
            beginn + m * 60L to (a + (b - a) * (lage - i)).roundToInt()
        }
    }

    /** SpO2 alle fuenf Minuten um 96 %, einmal 93 % - "lowest 93" wie im Entwurf. */
    private fun spo2(beginn: Long): List<Pair<Long, Int>> =
        (0..98).map { i -> beginn + i * 300L to if (i == 60) 93 else if (i % 3 == 0) 97 else 96 }

    /** Die sechs Naechte davor (Mi bis Mo), mit den Laengen des Entwurfs; Ruhepuls im Schnitt 55, HRV 52. */
    private fun davor(): List<NachtEntity> {
        val laengen = listOf(432, 396, 486, 468, 420, 414)
        val ruhe = listOf(55, 56, 54, 55, 56, 54)
        val hrv = listOf(52, 54, 50, 53, 51, 52)
        return laengen.indices.map { i ->
            val beginn = Healthbeispiel.um(22, 0, abend.minusDays(6L - i)).epochSecond
            NachtEntity(beginn, 7200, 0, laengen[i] + 15, laengen[i], ruhe[i], hrv[i])
        }
    }

    private fun detail(nacht: coredevices.pebble.kiesel.Nacht): Gesundheitsdaten.NachtDetail {
        val beginn = nacht.einschlafen.epochSecond
        return Gesundheitsdaten.NachtDetail(nacht, puls(beginn), spo2(beginn), vorige = beginn - 86_400, naechste = null, davor = davor())
    }

    private class Gezeichnet(val bild: Bitmap, val wurzel: SemanticsNode, val hintergrund: Int, val dichte: Float) {
        val breite = bild.width
        val pixel = IntArray(bild.width * bild.height).also { bild.getPixels(it, 0, bild.width, 0, 0, bild.width, bild.height) }
        fun bei(x: Int, y: Int) = pixel[y * breite + x]

        fun alle(n: SemanticsNode = wurzel): List<SemanticsNode> = listOf(n) + n.children.flatMap { alle(it) }
        fun text(n: SemanticsNode): String? = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        fun beschreibung(n: SemanticsNode): String? = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
        val texte: List<String> get() = alle().mapNotNull { text(it) }

        /** Das Bild, dessen Satz fuer TalkBack mit [anfang] beginnt. */
        fun bild(anfang: String): SemanticsNode = assertNotNull(
            alle().firstOrNull { beschreibung(it)?.startsWith(anfang) == true },
            "kein Bild \"$anfang…\" in ${alle().mapNotNull { beschreibung(it) }}",
        )

        /** Die Farben im Rechteck, die haeufigste zuerst. */
        fun farben(r: Rect): List<Pair<Int, Int>> {
            val zaehler = HashMap<Int, Int>()
            for (y in r.top.toInt() until r.bottom.toInt()) for (x in r.left.toInt() until r.right.toInt()) zaehler.merge(bei(x, y), 1, Int::plus)
            return zaehler.entries.sortedByDescending { it.value }.map { it.key to it.value }
        }
    }

    private fun zeichne(name: String, d: Gesundheitsdaten.NachtDetail): Gezeichnet {
        val aktivitaet = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var hintergrund = 0
        aktivitaet.setContent {
            MaterialTheme(colorScheme = greyScheme) {
                hintergrund = MaterialTheme.colorScheme.background.toArgb()
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { NachtInhalt(d, {}, {}) }
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        val ansicht = aktivitaet.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val bild = Bitmap.createBitmap(ansicht.width, ansicht.height, Bitmap.Config.ARGB_8888)
        ansicht.draw(Canvas(bild))
        File("build/$name.png").outputStream().use { bild.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val wurzel = (ansicht as ViewGroup).getChildAt(0) as ViewRootForTest
        return Gezeichnet(bild, wurzel.semanticsOwner.unmergedRootSemanticsNode, hintergrund, aktivitaet.resources.displayMetrics.density)
    }

    /** Das Hypnogramm: das Bild, dessen Satz nach der Zeit der Nacht alle vier Phasen nennt. */
    private fun hypnogramm(g: Gezeichnet): SemanticsNode = assertNotNull(
        g.alle().firstOrNull { n -> g.beschreibung(n)?.let { b -> ": " in b && erwartet.all { (name, _) -> "$name " in b } } == true },
        "kein Hypnogramm in ${g.alle().mapNotNull { g.beschreibung(it) }}",
    )

    /**
     * Die Spalten der Phasenzeile: die Knoten, die ihren Inhalt fuer TalkBack
     * zusammenfassen und deren erster Text der Name einer Phase ist - je
     * Spalte der Knoten selbst und seine Texte von oben nach unten.
     */
    private fun spalten(g: Gezeichnet): List<Pair<SemanticsNode, List<SemanticsNode>>> {
        val namen = erwartet.map { it.first }.toSet()
        return g.alle().filter { it.config.isMergingSemanticsOfDescendants }
            .map { s -> s to g.alle(s).drop(1).filter { g.text(it) != null }.sortedBy { it.boundsInRoot.top } }
            .filter { (_, texte) -> texte.firstOrNull()?.let { g.text(it) } in namen }
            .sortedBy { (s, _) -> s.boundsInRoot.left }
    }

    /**
     * DIE STAGES-KARTE IST WEG, DIE ZEITEN STEHEN UNTEN IN "THROUGH THE NIGHT":
     * vier Spalten nebeneinander, gleich breit, Wach - REM - Leicht - Tief,
     * je Name und Dauer; unter allen Bildern der Karte, durch eine Linie
     * abgesetzt, und in derselben Karte - zwischen den Bildern und der Zeile
     * liegt kein Stueck Seite.
     */
    @Test
    fun die_phasen_stehen_unten_in_der_karte_mit_dem_hypnogramm() {
        val g = zeichne("schlafansicht", detail(Healthbeispiel.nachtAb(abend)))

        // Keine Karte "Stages" mehr, kein "in bed" - und der Rest der Seite steht noch.
        assertTrue(g.texte.none { it.startsWith("STAGES") || it.contains(" in bed") }, "${g.texte}")
        val durch = g.alle().single { g.text(it)?.startsWith(context.getString(R.string.ks_n_durch_die_nacht)) == true }
        val koerper = g.alle().single { g.text(it)?.startsWith(context.getString(R.string.ks_n_koerper)) == true }
        val woche = g.alle().single { g.text(it)?.startsWith(context.getString(R.string.ks_n_sieben)) == true }
        assertTrue(durch.boundsInRoot.top < koerper.boundsInRoot.top && koerper.boundsInRoot.top < woche.boundsInRoot.top)

        // Vier Spalten in der verlangten Reihenfolge, je Name und Dauer.
        val sp = spalten(g)
        assertEquals(erwartet, sp.map { (_, t) -> t.map { g.text(it) } }.map { it[0] to it.getOrNull(1) }, "Spalten")
        assertTrue(sp.all { (_, t) -> t.size == 2 }, "mehr als Name und Dauer: ${sp.map { (_, t) -> t.map { g.text(it) } }}")

        // Nebeneinander auf einer Hoehe, gleich breit.
        val r = sp.map { it.first.boundsInRoot }
        assertTrue(r.all { abs(it.top - r[0].top) < 1f }, "nicht auf einer Hoehe: $r")
        assertTrue(r.zipWithNext().all { (a, b) -> b.left > a.right }, "nicht nebeneinander: $r")
        assertTrue(r.all { abs(it.width - r[0].width) <= 1f }, "nicht gleich breit: $r")

        // Unter allen Bildern der Karte (Hypnogramm, Puls, SpO2), ueber der naechsten Karte.
        val bilder = listOf(
            hypnogramm(g),
            g.bild(context.getString(R.string.ks_n_puls) + ","),
            g.bild(context.getString(R.string.ks_h_spo2_n) + ","),
        ).filter { it.boundsInRoot.top > durch.boundsInRoot.top }
        assertEquals(3, bilder.size, "Bilder der Karte")
        val bilderUnten = bilder.maxOf { it.boundsInRoot.bottom }
        val zeileOben = r.minOf { it.top }
        assertTrue(zeileOben > bilderUnten, "Zeile bei $zeileOben, Bilder bis $bilderUnten")
        assertTrue(r.maxOf { it.bottom } < koerper.boundsInRoot.top, "Zeile reicht in die naechste Karte")

        // In derselben Karte: zwischen dem letzten Bild und der Zeile keine Seite (deren Hintergrund).
        val links = r.first().left.toInt()
        val rechts = r.last().right.toInt()
        val luecke = (bilderUnten.toInt() + 1 until zeileOben.toInt())
        assertTrue(luecke.none { y -> g.bei(links + 2, y) == g.hintergrund }, "zwischen Bild und Zeile liegt Seite")
        // Und dort eine Linie quer ueber die Zeile, nicht in der Farbe der Karte.
        val karte = g.farben(Rect(links.toFloat(), bilderUnten + 1, rechts.toFloat(), zeileOben)).first().first
        val linie = luecke.filter { y -> g.bei(links, y) != karte && (links until rechts).all { x -> g.bei(x, y) == g.bei(links, y) } }
        assertTrue(linie.isNotEmpty() && linie.size <= 2 * g.dichte, "Linie ueber der Zeile: Zeilen $linie")
    }

    /**
     * DER PUNKT JEDER SPALTE HAT DIE FARBE IHRER SPUR - am Bild gemessen:
     * links vom Namen die Farbe des Punkts, und im gezeichneten Hypnogramm
     * liegen diese Farben von oben nach unten in der Reihenfolge der Spalten
     * von links nach rechts.
     */
    @Test
    fun jeder_farbpunkt_hat_die_farbe_seiner_spur() {
        val g = zeichne("schlafansicht", detail(Healthbeispiel.nachtAb(abend)))
        val punkte = spalten(g).map { (s, t) ->
            val name = t.first().boundsInRoot
            val feld = Rect(s.boundsInRoot.left, name.top, name.left, name.bottom)
            // Haeufigste Farbe ist die Karte, die zweite der Punkt (die Kanten sind geglaettet und einzeln selten).
            val f = g.farben(feld)
            assertTrue(f.size >= 2 && f[1].second > 20 * g.dichte * g.dichte, "kein Punkt links von ${g.text(t.first())}: $f")
            f[1].first
        }
        assertEquals(4, punkte.toSet().size, "nicht vier verschiedene Farben: $punkte")

        val hypno = hypnogramm(g).boundsInRoot
        val mitten = punkte.map { farbe ->
            val ys = (hypno.top.toInt() until hypno.bottom.toInt()).flatMap { y ->
                (hypno.left.toInt() until hypno.right.toInt()).filter { x -> g.bei(x, y) == farbe }.map { y }
            }
            assertTrue(ys.size > 100, "Farbe ${Integer.toHexString(farbe)} kaum im Hypnogramm (${ys.size} px)")
            ys.average()
        }
        assertTrue(mitten.zipWithNext().all { (a, b) -> b > a }, "Spuren von oben nach unten liegen bei $mitten")
    }

    /** Eine Nacht ohne erkannte Phasen: die Karte mit der einen Bahn steht, eine Zeile voller Nullen nicht. */
    @Test
    fun ohne_phasen_keine_phasenzeile() {
        val einschlafen = Healthbeispiel.um(21, 46, abend).epochSecond
        val nacht = Gesundheitsrechnung.nacht(
            NachtEntity(einschlafen, 7200, 0, 494, 479, 55, 50),
            listOf(NachtAbschnittEntity(einschlafen, 0, 494, Farben.OHNE_PHASE)),
        )
        val g = zeichne("schlafansicht-ohne-phasen", detail(nacht))
        assertTrue(g.texte.any { it.startsWith(context.getString(R.string.ks_n_durch_die_nacht)) }, "${g.texte}")
        val namen = erwartet.map { it.first }
        assertTrue(g.texte.none { it in namen }, "Phasenzeile ohne Phasen: ${g.texte}")
    }

    private fun abs(f: Float) = kotlin.math.abs(f)
}
