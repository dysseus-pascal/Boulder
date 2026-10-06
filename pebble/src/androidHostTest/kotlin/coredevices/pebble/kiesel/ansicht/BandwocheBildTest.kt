package coredevices.pebble.kiesel.ansicht

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import coredevices.pebble.kiesel.Gesundheitsrechnung
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import theme.greyScheme
import java.io.File
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DIE FLAECHE DES BANDS, GEZEICHNET mit [Bandwoche] der App: eine Woche,
 * jeden Tag 45, das Band an den Tagen 2 bis 4 (41-49, 31-59, 41-49) und am
 * sechsten Tag allein (37-53), am ersten, fuenften und siebten keines.
 *
 * Nachgesehen wird, was Fitbit und Garmin zeigen und der Entwurf verlangt:
 * die Flaeche geht von Tagesmitte zu Tagesmitte gerade durch - zwischen zwei
 * Mitten liegt ihr Rand auf der Verbindung, nicht auf einer Stufe -, ohne
 * dunklere Naehte, endet an den Mitten der Tage ohne Nachbarn mit Band und
 * steht am einzelnen Tag als kurzes Stueck. Die Linie zwischen den Punkten
 * ist grau, jeder Punkt hat einen Rand in der Farbe der Karte. Das Bild
 * liegt danach unter build/bandwoche-flaeche.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h400dp-xxhdpi")
class BandwocheBildTest {

    private val heute = LocalDate.of(2026, 10, 6)

    private fun tag(i: Int, band: Pair<Int, Int>?) = Gesundheitsrechnung.Bandtag(heute.minusDays(6L - i), 45, band, frueher = if (band == null) 2 else 3)

    private val woche = listOf(tag(0, null), tag(1, 41 to 49), tag(2, 31 to 59), tag(3, 41 to 49), tag(4, null), tag(5, 37 to 53), tag(6, null))

    @Test
    fun flaeche_durch_die_tagesmitten_ohne_stufen_und_naehte() {
        val aktivitaet = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var band = 0
        var doppelt = 0
        var neutral = 0
        var karte = 0
        aktivitaet.setContent {
            MaterialTheme(colorScheme = greyScheme) {
                val flaeche = Farben.imBand.ton().copy(alpha = Farben.BAND_DECKUNG)
                band = flaeche.compositeOver(Farben.karte()).toArgb()
                // So saehe eine Naht aus: zwei Stuecke der durchscheinenden Flaeche uebereinander.
                doppelt = flaeche.compositeOver(flaeche.compositeOver(Farben.karte())).toArgb()
                neutral = Farben.neutral().toArgb()
                karte = Farben.karte().toArgb()
                Box(Modifier.background(Farben.karte()).padding(14.dp)) {
                    Bandwoche(listOf(woche), Farben.imBand.ton(), Farben.ausserBand.ton(), hoehe = 190.dp) { it.dayOfMonth.toString() }
                }
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        val ansicht = aktivitaet.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val bild = Bitmap.createBitmap(ansicht.width, ansicht.height, Bitmap.Config.ARGB_8888)
        ansicht.draw(Canvas(bild))
        File("build/bandwoche-flaeche.png").outputStream().use { bild.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val dichte = aktivitaet.resources.displayMetrics.density
        val pixel = IntArray(bild.width * bild.height).also { bild.getPixels(it, 0, bild.width, 0, 0, bild.width, bild.height) }
        fun bei(x: Int, y: Int) = pixel[y * bild.width + x]
        fun nahe(a: Int, b: Int) = listOf(16, 8, 0).all { abs((a shr it and 0xFF) - (b shr it and 0xFF)) <= 3 }

        // Die Tagesmitten aus den neutralen Punkten (frueher = 3: das Band urteilt nicht).
        val punktzeilen = (0 until bild.height).filter { y -> (0 until bild.width).count { x -> bei(x, y) == neutral } > 7 * 4 * dichte }
        val zeile = punktzeilen[punktzeilen.size / 2]
        val spalten = (0 until bild.width).filter { bei(it, zeile) == neutral }
        val mitten = spalten.fold(listOf<MutableList<Int>>()) { l, x -> if (l.isNotEmpty() && x - l.last().last() <= 1) l.also { it.last() += x } else l + listOf(mutableListOf(x)) }
            .filter { it.size > 4 * dichte }.map { it.average() }
        assertEquals(7, mitten.size, "Tagesmitten: $mitten")
        val abstand = (mitten[6] - mitten[0]) / 6

        /** Der obere und untere Rand der Flaeche in der Spalte [x], oder null ohne Band dort. */
        fun raender(x: Double): Pair<Int, Int>? {
            val ys = (0 until bild.height).filter { y -> nahe(bei(x.toInt(), y), band) }
            return if (ys.isEmpty()) null else ys.min() to ys.max()
        }

        // GERADE VON MITTE ZU MITTE: zwischen Tag 2 (41-49) und Tag 3 (31-59) liegt der Rand
        // in der Mitte genau zwischen dem bei einem und dem bei drei Vierteln - eine Treppe sprang dort.
        for (i in listOf(1, 2)) {
            val (o1, u1) = raender(mitten[i] + abstand / 4)!!
            val (o2, u2) = raender(mitten[i] + abstand / 2)!!
            val (o3, u3) = raender(mitten[i] + abstand * 3 / 4)!!
            assertTrue(abs(o3 - o1) > 8 * dichte && abs(u3 - u1) > 8 * dichte, "kein Unterschied zwischen den Tagen $i und ${i + 1}")
            assertEquals((o1 + o3) / 2.0, o2.toDouble(), 1.5 * dichte, "oberer Rand zwischen Tag $i und ${i + 1}: $o1 $o2 $o3")
            assertEquals((u1 + u3) / 2.0, u2.toDouble(), 1.5 * dichte, "unterer Rand zwischen Tag $i und ${i + 1}: $u1 $u2 $u3")
        }
        // DURCH DIE MITTEN: links vom zweiten Tag und zwischen viertem und sechstem kein Band.
        assertEquals(null, raender(mitten[1] - abstand / 4), "Band vor der Mitte des ersten Tags mit Band")
        assertEquals(null, raender(mitten[3] + abstand / 4), "Band nach der Mitte des letzten Tags des Stuecks")
        assertEquals(null, raender(mitten[4]), "Band am Tag ohne Band")
        // DER EINZELNE TAG als kurzes Stueck, eine halbe Spalte breit um seine Mitte.
        assertTrue(raender(mitten[5] - abstand / 8) != null && raender(mitten[5] + abstand / 8) != null, "einzelner Tag ohne Band")
        assertEquals(null, raender(mitten[5] - abstand * 3 / 8))
        assertEquals(null, raender(mitten[5] + abstand * 3 / 8))
        assertEquals(null, raender(mitten[6] - abstand / 4), "Band am letzten Tag ohne Band")
        // OHNE NAEHTE: nirgends zwei Lagen der Flaeche uebereinander.
        assertTrue(pixel.none { nahe(it, doppelt) }, "Naht in der Flaeche")
        // DER KARTENRAND UM JEDEN PUNKT: 7 dp neben der Mitte, wo die Linie in den Punkt laeuft, steht die Karte.
        for (i in 0 until 6) assertEquals(karte, bei((mitten[i] + 7 * dichte).toInt(), zeile), "kein Kartenrand am Punkt $i")
        // DIE LINIE GRAU: zwischen zwei Punkten auf der Hoehe von 45, mitten im Band.
        for (i in 0 until 6) {
            val p = bei((mitten[i] + abstand / 2).toInt(), zeile)
            val (r, g, b) = listOf(16, 8, 0).map { p shr it and 0xFF }
            assertTrue(abs(r - g) <= 8 && abs(g - b) <= 8 && abs(r - b) <= 8, "Linie nicht grau zwischen Tag $i und ${i + 1}: ${Integer.toHexString(p)}")
        }
    }
}
