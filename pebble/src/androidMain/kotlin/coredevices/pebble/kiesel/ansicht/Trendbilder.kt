package coredevices.pebble.kiesel.ansicht

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
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
 * Ziel und Mittelwert mit Beschriftung an ihrer Linie.
 *
 * DIE BESCHRIFTUNG SUCHT SICH EINEN FREIEN PLATZ: von rechts nach links, je
 * erst ueber, dann unter der Linie. `belegt` sind Saeulen, Punkte und die
 * Zahlen darueber, und die andere Linie zaehlt mit. Ein "avg 31" quer ueber
 * einer Saeule oder dicht an einer Zahl ("avg 55 54") liest sich als eine
 * Zahl. Ist nirgends Platz, steht sie dort, wo sie am wenigsten verdeckt.
 *
 * DIE LINIEN SETZEN UNTER JEDER SCHRIFT AUS: `zahlen` sind die Werte ueber
 * den Saeulen. Liegt eine Saeule knapp unter Ziel oder Schnitt, liefen die
 * Linien sonst mitten durch ihre Zahl ("6:46" durchgestrichen).
 */
private fun DrawScope.linien(
    messer: TextMeasurer, stil: TextStyle, farbe: Color, von: Float, bis: Float, marke: Linie?, mittel: Linie?,
    belegt: List<Rect> = emptyList(), zahlen: List<Rect> = emptyList(),
) {
    val abstand = 2.dp.toPx()
    // Der Mindestabstand zu allem Belegten - knapper liest es sich als eine Zeile.
    val luft = 6.dp.toPx()
    val besetzt = belegt.map { it.inflate(luft) }.toMutableList()
    val gesetzt = mutableListOf<Pair<TextLayoutResult, Offset>>()
    fun setze(l: Linie, andere: Linie?) {
        val m = messer.measure(l.text, stil)
        val w = m.size.width.toFloat()
        val h = m.size.height.toFloat()
        val hindernisse = besetzt + listOfNotNull(andere?.let { Rect(von, it.y - abstand, bis, it.y + abstand) })
        fun stoerung(o: Offset): Double {
            val r = Rect(o, Size(w, h))
            return hindernisse.sumOf { b -> r.intersect(b).let { i -> if (i.width > 0f && i.height > 0f) (i.width * i.height).toDouble() else 0.0 } }
        }
        val ueber = (l.y - abstand - h).coerceIn(0f, size.height - h)
        val unter = (l.y + abstand).coerceIn(0f, size.height - h)
        val xe = generateSequence(bis - w) { it - 4.dp.toPx() }.takeWhile { it >= von }.toList().ifEmpty { listOf(von) }
        // minBy nimmt bei Gleichstand den ersten: so weit rechts wie moeglich, ueber der Linie vor darunter.
        val ort = xe.flatMap { x -> listOf(Offset(x, ueber), Offset(x, unter)) }.minBy { stoerung(it) }
        gesetzt += m to ort
        besetzt += Rect(ort, Size(w, h)).inflate(luft)
    }
    // Das Ziel zuerst: es ist die feste Groesse, der Mittelwert weicht aus.
    marke?.let { setze(it, mittel) }
    mittel?.let { setze(it, marke) }
    // Nur seitlich erweitert: die eigene Beschriftung steht 2 dp ueber oder unter
    // ihrer Linie, hoeher ausgespart verloere sie dort ein Stueck.
    val luecken = Path()
    (zahlen + gesetzt.map { (m, o) -> Rect(o, Size(m.size.width.toFloat(), m.size.height.toFloat())) }).forEach {
        luecken.addRect(Rect(it.left - abstand, it.top, it.right + abstand, it.bottom))
    }
    clipPath(luecken, ClipOp.Difference) {
        marke?.let { drawLine(farbe, Offset(von, it.y), Offset(bis, it.y), 1.5.dp.toPx()) }
        mittel?.let { drawLine(farbe, Offset(von, it.y), Offset(bis, it.y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))) }
    }
    gesetzt.forEach { (m, o) -> drawText(m, topLeft = o) }
}

/**
 * Ein Tag kurz, in der Reihenfolge der Sprache ("Sep 28" oder "28 Sep") -
 * auf der Trendseite in Liste, Achse und Ankuendigung dasselbe Format.
 */
internal fun kurzDatumsformat(): DateTimeFormatter {
    val l = Locale.getDefault()
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(l, "MMMd"), l)
}

internal fun kurzDatum(t: LocalDate): String = t.format(kurzDatumsformat())

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
 *
 * `pegel` wie im [Tagesbild]: Ruhepuls und HRV als Punkte auf einer engen
 * Achse statt als Saeulen ab null - 54 und 57 bpm waeren sonst 79 % gegen
 * 83 % hoch und nicht zu unterscheiden.
 */
@Composable
internal fun Trendsaeulen(
    saeulen: List<TSaeule>, farbe: Color, mittel: Double? = null, marke: Double? = null, hoehe: Dp = 130.dp,
    kurz: ((Double) -> String)? = null, pegel: Boolean = false,
) {
    if (saeulen.none { it.wert != null }) return
    val messer = rememberTextMeasurer()
    val stil = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val fett = stil.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
    val leer = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val linie = MaterialTheme.colorScheme.onSurfaceVariant
    val raster = MaterialTheme.colorScheme.outlineVariant
    val mittelText = mittel?.let { m -> kurz?.let { stringResource(R.string.ks_trn_avg, it(m)) } }.orEmpty()
    val zielText = marke?.let { m -> kurz?.let { stringResource(R.string.ks_trn_goal, it(m)) } }.orEmpty()
    val zweizeilig = saeulen.any { it.hinweis != null }
    Canvas(Modifier.fillMaxWidth().height(if (zweizeilig) hoehe + 14.dp else hoehe).padding(top = 4.dp)) {
        val kopf = 14.dp.toPx()
        // Fussraum aus der Schrift, nicht fest: bei grosser Schriftskalierung ragt ein fester Fuss sonst aus dem Bild.
        val zeile = messer.measure("0", fett).size.height
        val boden = size.height - 4.dp.toPx() - zeile * (if (zweizeilig) 2 else 1)
        val alle = saeulen.flatMap { listOfNotNull(it.wert, it.kleinster, it.groesster) } + listOfNotNull(mittel, marke)
        // Die Striche koennen ueber min-5..max+5 hinausreichen (runde Grenzen); dann sind sie die Achse.
        val striche = if (pegel) Auswertung.achsenschritte(alle.min() - 5, alle.max() + 5) else emptyList()
        val unten = if (pegel) minOf(alle.min() - 5, striche.min()) else 0.0
        val spitze = if (pegel) maxOf(alle.max() + 5, striche.max()) else maxOf(saeulen.maxOf { maxOf(it.wert ?: 0.0, it.groesster ?: 0.0) }, marke ?: 0.0, 1.0) * 1.2
        val links = if (pegel) 28.dp.toPx() else 0f
        val fach = (size.width - links) / saeulen.size
        val breite = minOf(fach * 0.55f, 26.dp.toPx())
        val ecke = CornerRadius(4.dp.toPx())
        val punkt = 4.dp.toPx()
        fun y(w: Double) = (kopf + (boden - kopf) * (1 - (w - unten) / (spitze - unten))).toFloat()
        val texte = saeulen.map { s -> s.wert?.let { w -> kurz?.invoke(w) } }
        val passt = texte.maxOf { t -> t?.let { messer.measure(it, fett).size.width } ?: 0 } <= fach - 2.dp.toPx()
        val mitZahl = beschriftete(saeulen.mapIndexed { i, s -> s.wert.takeIf { texte[i] != null } }, passt)
        val belegt = mutableListOf<Rect>()
        val zahlen = mutableListOf<Rect>()
        if (pegel) {
            striche.forEach { s ->
                drawLine(raster, Offset(links, y(s)), Offset(size.width, y(s)), 1.dp.toPx())
                val m = messer.measure(kurz?.invoke(s) ?: "%.0f".format(s), stil)
                drawText(m, topLeft = Offset(links - 4.dp.toPx() - m.size.width, (y(s) - m.size.height / 2f).coerceIn(0f, boden)))
            }
        }
        // Neun Montagsdaten passen nicht nebeneinander: dann nur jedes k-te, der letzte Name bleibt.
        val namenSchritt = maxOf(1, Math.ceil(saeulen.maxOf { messer.measure(it.name, fett).size.width } / (fach - 2.dp.toPx()).toDouble()).toInt())
        saeulen.forEachIndexed { i, s ->
            val saeuleLinks = links + fach * i + (fach - breite) / 2
            val mitte = links + fach * i + fach / 2
            if (!pegel) drawRoundRect(leer, Offset(saeuleLinks, kopf), Size(breite, boden - kopf), ecke)
            val w = s.wert
            if (w == null) {
                val m = messer.measure("–", stil)
                drawText(m, topLeft = Offset(mitte - m.size.width / 2f, (kopf + boden) / 2 - m.size.height / 2f))
            } else {
                val alpha = when { s.blass -> 0.35f; s.hervor -> 1f; else -> 0.55f }
                val hoch = s.groesster
                if (s.kleinster != null && hoch != null && hoch > s.kleinster) {
                    drawLine(linie, Offset(mitte, y(hoch)), Offset(mitte, y(s.kleinster)), 1.dp.toPx())
                    belegt += Rect(mitte - 1.dp.toPx(), y(hoch), mitte + 1.dp.toPx(), y(s.kleinster))
                }
                if (pegel) {
                    drawCircle(farbe.copy(alpha = alpha), punkt, Offset(mitte, y(w)))
                    belegt += Rect(Offset(mitte, y(w)), punkt)
                } else if (w > 0) {
                    drawRoundRect(farbe.copy(alpha = alpha), Offset(saeuleLinks, y(w)), Size(breite, boden - y(w)), ecke)
                    belegt += Rect(Offset(saeuleLinks, y(w)), Size(breite, boden - y(w)))
                    s.innen?.takeIf { it > 0 }?.let {
                        drawRoundRect(farbe.copy(red = farbe.red * 0.6f, green = farbe.green * 0.6f, blue = farbe.blue * 0.6f), Offset(saeuleLinks + breite * 0.2f, y(it)), Size(breite * 0.6f, boden - y(it)), ecke)
                    }
                }
                val text = texte[i]
                if (text != null && i in mitZahl) {
                    val m = messer.measure(text, if (s.hervor) fett else stil)
                    val ueber = (if (pegel) punkt + 3.dp.toPx() else 2.dp.toPx())
                    val oben = minOf(y(w), y(hoch ?: w)) - ueber - m.size.height
                    val ecke0 = Offset(mitte - m.size.width / 2f, oben.coerceAtLeast(0f))
                    drawText(m, topLeft = ecke0)
                    belegt += Rect(ecke0, Size(m.size.width.toFloat(), m.size.height.toFloat()))
                    zahlen += belegt.last()
                }
            }
            val m = messer.measure(s.name, if (s.hervor) fett else stil)
            if ((saeulen.size - 1 - i) % namenSchritt == 0) drawText(m, topLeft = Offset(mitte - m.size.width / 2f, boden + 2.dp.toPx()))
            s.hinweis?.let {
                val h = messer.measure(it, stil)
                drawText(h, topLeft = Offset(mitte - h.size.width / 2f, boden + 2.dp.toPx() + m.size.height))
            }
        }
        linien(messer, stil, linie, links, size.width, marke?.let { Linie(y(it), zielText) }, mittel?.let { Linie(y(it), mittelText) }, belegt, zahlen)
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
    val datum = remember(Locale.getDefault()) { kurzDatumsformat() }
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
        val zahlen = mutableListOf<Rect>()
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
                    if (w > 0) {
                        drawRoundRect(farbe.copy(alpha = alpha), Offset(mitte - breite / 2, y(w)), Size(breite, boden - y(w)), ecke)
                        // Die Saeule selbst ist belegt: eine Linienbeschriftung darueber laese sich als ihre Zahl.
                        belegt += Rect(Offset(mitte - breite / 2, y(w)), Size(breite, boden - y(w)))
                    }
                }
            } else if (w != null) {
                tage.getOrNull(i + 1)?.second?.let { n -> drawLine(farbe.copy(alpha = 0.55f), Offset(mitte, y(w)), Offset(mitte + fach, y(n)), 1.5.dp.toPx())
                }
                drawCircle(farbe.copy(alpha = alpha), 3.dp.toPx(), Offset(mitte, y(w)))
                belegt += Rect(Offset(mitte, y(w)), 3.dp.toPx())
            }
            val text = texte[i]
            if (w != null && text != null && i in mitZahl) {
                val m = messer.measure(text, if (i == letzter) fett else stil)
                val abstand = (if (pegel) 5.dp else 2.dp).toPx()
                val ecke0 = Offset(mitte - m.size.width / 2f, (y(w) - abstand - m.size.height).coerceAtLeast(0f))
                drawText(m, topLeft = ecke0)
                belegt += Rect(ecke0, Size(m.size.width.toFloat(), m.size.height.toFloat()))
                zahlen += belegt.last()
            }
            if (tag.dayOfWeek == DayOfWeek.MONDAY) {
                val m = messer.measure(tag.format(datum), stil)
                drawText(m, topLeft = Offset((mitte - m.size.width / 2f).coerceIn(links, maxOf(links, size.width - m.size.width)), size.height - m.size.height))
            }
        }
        linien(messer, stil, linie, links, size.width, marke?.let { Linie(y(it), zielText) }, mittel?.let { Linie(y(it), mittelText) }, belegt, zahlen)
    }
}
