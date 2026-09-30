package coredevices.pebble.kiesel.ansicht

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Auswertung
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Eine Saeule im Trend: der Wert, ein Teil davon innen (genommen im faelligen),
 * hervorgehoben oder blass, die Spanne der Tage dahinter, und ein Hinweis
 * unter dem Namen ("so far" fuer die laufende Woche).
 */
internal data class TSaeule(
    val name: String,
    val wert: Double?,
    val innen: Double? = null,
    val hervor: Boolean = false,
    val kleinster: Double? = null,
    val groesster: Double? = null,
    val blass: Boolean = false,
    val hinweis: String? = null,
)

/** Eine waagrechte Linie mit ihrer Beschriftung: durchgezogen das Ziel, gestrichelt der Mittelwert. */
private class Linie(val y: Float, val text: String)

/**
 * Ziel und Mittelwert mit Beschriftung rechts ueber dem Linienende. Liegen
 * beide Beschriftungen zu nah beieinander, weicht "avg" aus - das Ziel ist
 * die feste Groesse, der Mittelwert die bewegliche: unter seine Linie, wenn
 * er unter dem Ziel liegt, sonst noch ueber das Ziel-Label, weil er darunter
 * genau auf "goal" laege.
 *
 * `belegt` sind die Zahlen ueber den Saeulen: am ersten Morgen ist der
 * einzige Wert zugleich der Mittelwert, und beide Beschriftungen landen auf
 * demselben Fleck. Dann rueckt das Linien-Label nach links, bis es frei steht.
 */
private fun DrawScope.linien(
    messer: TextMeasurer, stil: TextStyle, farbe: Color, von: Float, bis: Float, marke: Linie?, mittel: Linie?, belegt: List<Rect> = emptyList(),
) {
    val abstand = 2.dp.toPx()
    fun frei(oben: Float, m: Size): Offset {
        var x = bis - m.width
        repeat(3) {
            val stoerer = belegt.filter { it.overlaps(Rect(Offset(x, oben), m)) }.minOfOrNull { it.left } ?: return Offset(x, oben)
            x = stoerer - 2 * abstand - m.width
        }
        return Offset(x.coerceAtLeast(von), oben)
    }
    val zielMass = marke?.let { messer.measure(it.text, stil) }
    val zielOben = if (marke != null && zielMass != null) (marke.y - abstand - zielMass.size.height).coerceIn(0f, size.height - zielMass.size.height) else 0f
    if (marke != null && zielMass != null) {
        drawLine(farbe, Offset(von, marke.y), Offset(bis, marke.y), 1.5.dp.toPx())
        drawText(zielMass, topLeft = frei(zielOben, Size(zielMass.size.width.toFloat(), zielMass.size.height.toFloat())))
    }
    mittel?.let {
        drawLine(farbe, Offset(von, it.y), Offset(bis, it.y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
        val m = messer.measure(it.text, stil)
        val h = m.size.height
        val oben = when {
            marke == null || abs(marke.y - it.y) >= h + abstand -> it.y - abstand - h
            it.y > marke.y -> it.y + abstand
            else -> zielOben - abstand - h
        }
        drawText(m, topLeft = frei(oben.coerceIn(0f, size.height - h), Size(m.size.width.toFloat(), h.toFloat())))
    }
}

/**
 * Welche Saeulen ihre Zahl bekommen: alle, wenn die breiteste Zahl ins Fach
 * passt - sonst nur hoechste, tiefste und letzte, damit sich nichts ueberlappt.
 */
private fun beschriftete(werte: List<Double?>, passt: Boolean): Set<Int> {
    val mit = werte.withIndex().filter { it.value != null }
    if (passt) return mit.map { it.index }.toSet()
    return setOfNotNull(mit.maxByOrNull { it.value!! }?.index, mit.minByOrNull { it.value!! }?.index, mit.lastOrNull()?.index)
}

/**
 * Saeulen je Wochentag oder Woche. Ueber jeder Saeule ihre Zahl, ein hohles
 * Fach mit "–" fuer Wochentage ohne Mittel, ein duenner Strich fuer die Spanne
 * der Tage. `kurz` schreibt die Zahlen und die Linienbeschriftung; ohne sie
 * bleiben Saeulen und Linien stumm.
 */
@Composable
internal fun Trendsaeulen(
    saeulen: List<TSaeule>, farbe: Color, mittel: Double? = null, marke: Double? = null, hoehe: Dp = 130.dp,
    kurz: ((Double) -> String)? = null,
) {
    if (saeulen.none { it.wert != null }) return
    val messer = rememberTextMeasurer()
    val stil = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val fett = stil.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
    val leer = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val linie = MaterialTheme.colorScheme.onSurfaceVariant
    val mittelText = mittel?.let { m -> kurz?.let { stringResource(R.string.ks_trn_avg, it(m)) } }.orEmpty()
    val zielText = marke?.let { m -> kurz?.let { stringResource(R.string.ks_trn_goal, it(m)) } }.orEmpty()
    val zweizeilig = saeulen.any { it.hinweis != null }
    Canvas(Modifier.fillMaxWidth().height(if (zweizeilig) hoehe + 14.dp else hoehe).padding(top = 4.dp)) {
        val kopf = 14.dp.toPx()
        // Fussraum aus der Schrift, nicht fest: bei grosser Schriftskalierung ragt ein fester Fuss sonst aus dem Bild.
        val zeile = messer.measure("0", fett).size.height
        val boden = size.height - 4.dp.toPx() - zeile * (if (zweizeilig) 2 else 1)
        val spitze = maxOf(saeulen.maxOf { maxOf(it.wert ?: 0.0, it.groesster ?: 0.0) }, marke ?: 0.0, 1.0) * 1.2
        val fach = size.width / saeulen.size
        val breite = minOf(fach * 0.55f, 26.dp.toPx())
        val ecke = CornerRadius(4.dp.toPx())
        fun y(w: Double) = (kopf + (boden - kopf) * (1 - w / spitze)).toFloat()
        val texte = saeulen.map { s -> s.wert?.let { w -> kurz?.invoke(w) } }
        val passt = texte.maxOf { t -> t?.let { messer.measure(it, fett).size.width } ?: 0 } <= fach - 2.dp.toPx()
        val mitZahl = beschriftete(saeulen.mapIndexed { i, s -> s.wert.takeIf { texte[i] != null } }, passt)
        val belegt = mutableListOf<Rect>()
        // Neun Montagsdaten passen nicht nebeneinander: dann nur jedes k-te, der letzte Name bleibt.
        val namenSchritt = maxOf(1, Math.ceil(saeulen.maxOf { messer.measure(it.name, fett).size.width } / (fach - 2.dp.toPx()).toDouble()).toInt())
        saeulen.forEachIndexed { i, s ->
            val links = fach * i + (fach - breite) / 2
            val mitte = fach * i + fach / 2
            drawRoundRect(leer, Offset(links, kopf), Size(breite, boden - kopf), ecke)
            val w = s.wert
            if (w == null) {
                val m = messer.measure("–", stil)
                drawText(m, topLeft = Offset(mitte - m.size.width / 2f, (kopf + boden) / 2 - m.size.height / 2f))
            } else {
                val alpha = when { s.blass -> 0.35f; s.hervor -> 1f; else -> 0.55f }
                if (w > 0) drawRoundRect(farbe.copy(alpha = alpha), Offset(links, y(w)), Size(breite, boden - y(w)), ecke)
                s.innen?.takeIf { it > 0 }?.let {
                    drawRoundRect(farbe.copy(red = farbe.red * 0.6f, green = farbe.green * 0.6f, blue = farbe.blue * 0.6f), Offset(links + breite * 0.2f, y(it)), Size(breite * 0.6f, boden - y(it)), ecke)
                }
                val hoch = s.groesster
                if (s.kleinster != null && hoch != null && hoch > s.kleinster) drawLine(linie, Offset(mitte, y(hoch)), Offset(mitte, y(s.kleinster)), 1.dp.toPx())
                val text = texte[i]
                if (text != null && i in mitZahl) {
                    val m = messer.measure(text, if (s.hervor) fett else stil)
                    val oben = minOf(y(w), y(hoch ?: w)) - 2.dp.toPx() - m.size.height
                    val ecke0 = Offset(mitte - m.size.width / 2f, oben.coerceAtLeast(0f))
                    drawText(m, topLeft = ecke0)
                    belegt += Rect(ecke0, Size(m.size.width.toFloat(), m.size.height.toFloat()))
                }
            }
            val m = messer.measure(s.name, if (s.hervor) fett else stil)
            if ((saeulen.size - 1 - i) % namenSchritt == 0) drawText(m, topLeft = Offset(mitte - m.size.width / 2f, boden + 2.dp.toPx()))
            s.hinweis?.let {
                val h = messer.measure(it, stil)
                drawText(h, topLeft = Offset(mitte - h.size.width / 2f, boden + 2.dp.toPx() + m.size.height))
            }
        }
        linien(messer, stil, linie, 0f, size.width, marke?.let { Linie(y(it), zielText) }, mittel?.let { Linie(y(it), mittelText) }, belegt)
    }
}

/**
 * Vierzehn Kalendertage, ein Fach je Tag - auch fuer Tage ohne Wert, als
 * hohler Rahmen. Der juengste Tag steht kraeftig, das Wochenende leicht
 * schattiert, unter jedem Montag sein Datum.
 *
 * PEGELGROESSEN OHNE SAEULEN AB NULL. Ein Ruhepuls von 55 neben einem von 58
 * ist als Saeule nicht zu unterscheiden; als Punkt auf einer engen Achse
 * schon. Darum Punkte mit Verbindungslinie und die Achse von min-5 bis max+5.
 */
@Composable
internal fun Tagesbild(
    tage: List<Pair<LocalDate, Double?>>, farbe: Color, kurz: (Double) -> String,
    mittel: Double? = null, marke: Double? = null, pegel: Boolean = false, hoehe: Dp = 150.dp,
) {
    val werte = tage.mapNotNull { it.second }
    if (werte.isEmpty()) return
    val messer = rememberTextMeasurer()
    val stil = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val fett = stil.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
    val leer = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val rahmen = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
    val wochenende = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
    val linie = MaterialTheme.colorScheme.onSurfaceVariant
    val raster = MaterialTheme.colorScheme.outlineVariant
    val mittelText = mittel?.let { stringResource(R.string.ks_trn_avg, kurz(it)) }.orEmpty()
    val zielText = marke?.let { stringResource(R.string.ks_trn_goal, kurz(it)) }.orEmpty()
    val datum = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    Canvas(Modifier.fillMaxWidth().height(hoehe).padding(top = 4.dp)) {
        val kopf = 14.dp.toPx()
        val boden = size.height - 16.dp.toPx()
        val links = if (pegel) 28.dp.toPx() else 0f
        // Die Striche koennen ueber min-5..max+5 hinausreichen (runde Grenzen); dann sind sie die Achse.
        val striche = if (pegel) Auswertung.achsenschritte(werte.min() - 5, werte.max() + 5) else emptyList()
        val unten = if (pegel) minOf(werte.min() - 5, striche.min()) else 0.0
        val oben = if (pegel) maxOf(werte.max() + 5, striche.max()) else maxOf(werte.max(), marke ?: 0.0, 1.0) * 1.2
        fun y(w: Double) = (kopf + (boden - kopf) * (1 - (w - unten) / (oben - unten))).toFloat()
        val fach = (size.width - links) / tage.size
        val breite = minOf(fach * 0.6f, 22.dp.toPx())
        val ecke = CornerRadius(3.dp.toPx())
        val texte = tage.map { t -> t.second?.let(kurz) }
        val passt = texte.maxOf { t -> t?.let { messer.measure(it, fett).size.width } ?: 0 } <= fach - 2.dp.toPx()
        val mitZahl = beschriftete(tage.map { it.second }, passt)
        val letzter = tage.indexOfLast { it.second != null }
        val belegt = mutableListOf<Rect>()
        tage.forEachIndexed { i, (tag, _) ->
            if (tag.dayOfWeek == DayOfWeek.SATURDAY || tag.dayOfWeek == DayOfWeek.SUNDAY) {
                drawRect(wochenende, Offset(links + fach * i, kopf), Size(fach, boden - kopf))
            }
        }
        if (pegel) {
            striche.forEach { s ->
                drawLine(raster, Offset(links, y(s)), Offset(size.width, y(s)), 1.dp.toPx())
                val m = messer.measure(kurz(s), stil)
                drawText(m, topLeft = Offset(links - 4.dp.toPx() - m.size.width, (y(s) - m.size.height / 2f).coerceIn(0f, boden)))
            }
        }
        tage.forEachIndexed { i, (tag, w) ->
            val mitte = links + fach * i + fach / 2
            val alpha = if (i == letzter) 1f else 0.55f
            if (!pegel) {
                if (w == null) {
                    drawRoundRect(rahmen, Offset(mitte - breite / 2, kopf), Size(breite, boden - kopf), ecke, style = Stroke(1.dp.toPx()))
                } else {
                    drawRoundRect(leer, Offset(mitte - breite / 2, kopf), Size(breite, boden - kopf), ecke)
                    if (w > 0) drawRoundRect(farbe.copy(alpha = alpha), Offset(mitte - breite / 2, y(w)), Size(breite, boden - y(w)), ecke)
                }
            } else if (w != null) {
                tage.getOrNull(i + 1)?.second?.let { n -> drawLine(farbe.copy(alpha = 0.55f), Offset(mitte, y(w)), Offset(mitte + fach, y(n)), 1.5.dp.toPx())
                }
                drawCircle(farbe.copy(alpha = alpha), 3.dp.toPx(), Offset(mitte, y(w)))
            }
            val text = texte[i]
            if (w != null && text != null && i in mitZahl) {
                val m = messer.measure(text, if (i == letzter) fett else stil)
                val abstand = (if (pegel) 5.dp else 2.dp).toPx()
                val ecke0 = Offset(mitte - m.size.width / 2f, (y(w) - abstand - m.size.height).coerceAtLeast(0f))
                drawText(m, topLeft = ecke0)
                belegt += Rect(ecke0, Size(m.size.width.toFloat(), m.size.height.toFloat()))
            }
            if (tag.dayOfWeek == DayOfWeek.MONDAY) {
                val m = messer.measure(tag.format(datum), stil)
                drawText(m, topLeft = Offset((mitte - m.size.width / 2f).coerceIn(links, maxOf(links, size.width - m.size.width)), size.height - m.size.height))
            }
        }
        linien(messer, stil, linie, links, size.width, marke?.let { Linie(y(it), zielText) }, mittel?.let { Linie(y(it), mittelText) }, belegt)
    }
}

/** Zwei Groessen gegeneinander: jeder Tag ein Punkt, dazu die Ausgleichsgerade. */
@Composable
internal fun Streubild(z: Auswertung.Zusammenhang, farbe: Color, formX: (Double) -> String, formY: (Double) -> String) {
    if (z.punkte.size < 3) return
    val messer = rememberTextMeasurer()
    val stil = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val raster = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(170.dp).padding(top = 8.dp)) {
        val links = 36.dp.toPx()
        val fuss = 16.dp.toPx()
        val boden = size.height - fuss
        val xs = z.punkte.map { it.first }
        val ys = z.punkte.map { it.second }
        val (x0, x1) = xs.min() to xs.max().coerceAtLeast(xs.min() + 1e-6)
        val (y0, y1) = ys.min() to ys.max().coerceAtLeast(ys.min() + 1e-6)
        fun px(x: Double) = links + ((x - x0) / (x1 - x0)).toFloat() * (size.width - links - 4.dp.toPx())
        fun py(y: Double) = boden - ((y - y0) / (y1 - y0)).toFloat() * (boden - 6.dp.toPx())
        drawLine(raster, Offset(links, boden), Offset(size.width, boden), 1.dp.toPx())
        drawLine(raster, Offset(links, 0f), Offset(links, boden), 1.dp.toPx())
        z.punkte.forEach { (x, y) -> drawCircle(farbe.copy(alpha = 0.6f), 3.dp.toPx(), Offset(px(x), py(y))) }
        if (z.r != null) {
            drawLine(farbe, Offset(px(x0), py(z.achse + z.steigung * x0)), Offset(px(x1), py(z.achse + z.steigung * x1)), 2.dp.toPx())
        }
        listOf(y0, y1).forEach { y ->
            val m = messer.measure(formY(y), stil)
            drawText(m, topLeft = Offset(links - 4.dp.toPx() - m.size.width, (py(y) - m.size.height / 2f).coerceIn(0f, boden - m.size.height)))
        }
        listOf(x0, x1).forEach { x ->
            val m = messer.measure(formX(x), stil)
            drawText(m, topLeft = Offset((px(x) - m.size.width / 2f).coerceIn(links, size.width - m.size.width), size.height - m.size.height))
        }
    }
}
