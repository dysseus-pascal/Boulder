package coredevices.pebble.kiesel.ansicht

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coredevices.pebble.kiesel.Koerperrechnung
import coredevices.pebble.kiesel.Koerpertag
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Violett: unter den Farben der Gesundheitsseite ist es noch frei. */
internal val FLAECHE_KOERPER = Color(0xFF7E57C2)
private val KOERPER_DUNKEL = Color(0xFFB39DDB)
private val KOERPER_HELL = Color(0xFF5E35B1)

/** Der Ton fuer Striche und Punkte - aufgehellt auf dunklem Grund, wie in [toene]. */
@Composable
internal fun koerperTon(): Color = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) KOERPER_DUNKEL else KOERPER_HELL

/** Eine Personenwaage mit Zeiger, im Strich der [Zeichen]. */
internal val WAAGE: ImageVector = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
    listOf(
        "M7 4h10a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3H7a3 3 0 0 1-3-3V7a3 3 0 0 1 3-3z",
        "M8.5 11a3.5 3.5 0 0 1 7 0",
        "M12 11l1.6-2.2",
    ).forEach {
        addPath(
            PathParser().parsePathString(it).toNodes(), fill = null, stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
    }
}.build()

/**
 * Ein Koerperwert als blasse Tagespunkte mit dem gleitenden Median darueber, im Stil von
 * [HrvWoche]: die Punkte zeigen, was die Waage sagte, die Linie, wohin es geht.
 * Die Werte kommen schon in der Einheit der Anzeige.
 */
@Composable
internal fun Koerperverlauf(
    tage: List<Koerpertag>,
    linie: List<List<Pair<LocalDate, Double>>>,
    von: LocalDate,
    bis: LocalDate,
    farbe: Color,
    monatText: (LocalDate) -> String,
    hoehe: Dp = 170.dp,
) {
    if (tage.isEmpty()) return
    val messer = rememberTextMeasurer()
    val stil = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val rasterFarbe = MaterialTheme.colorScheme.outlineVariant
    val alle = tage.map { it.wert } + linie.flatten().map { it.second }
    val achse = Koerperrechnung.achse(alle.min(), alle.max())
    // Eine Nachkommastelle nur bei Schritten unter 1: "78.0" neben "79.0" ist Rauschen.
    val zahlen = achse.linien.map { if (achse.schritt < 1.0) Koerperrechnung.eineStelle(it) else String.format(Locale.getDefault(), "%.0f", it) }
    Canvas(Modifier.fillMaxWidth().height(hoehe).padding(top = 8.dp)) {
        val beschriftet = zahlen.map { messer.measure(it, stil) }
        val rechts = size.width - (beschriftet.maxOf { it.size.width } + 6.dp.toPx())
        val fuss = 18.dp.toPx()
        val kopf = 6.dp.toPx()
        val boden = size.height - fuss
        val spalten = (ChronoUnit.DAYS.between(von, bis) + 1).coerceAtLeast(1).toFloat()
        fun x(tag: LocalDate) = rechts * (ChronoUnit.DAYS.between(von, tag) + 0.5f) / spalten
        fun y(w: Double) = kopf + (boden - kopf) * (1f - ((w - achse.von) / (achse.bis - achse.von)).toFloat())

        val gestrichelt = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
        achse.linien.forEachIndexed { i, w ->
            drawLine(rasterFarbe, Offset(0f, y(w)), Offset(rechts, y(w)), 1.dp.toPx(), pathEffect = gestrichelt)
            val m = beschriftet[i]
            drawText(m, topLeft = Offset(size.width - m.size.width, y(w) - m.size.height / 2f))
        }

        // Bei wenigen Wiegungen duerfen die Punkte groesser sein - sie tragen dann das Bild.
        val r = if (tage.size > 45) 2.dp.toPx() else 3.dp.toPx()
        tage.forEach { drawCircle(farbe.copy(alpha = 0.4f), r, Offset(x(it.tag), y(it.wert))) }
        val dicke = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        linie.forEach { stueck ->
            val p = stueck.map { (tag, w) -> Offset(x(tag), y(w)) }
            if (p.size == 1) drawCircle(farbe, 2.dp.toPx(), p[0])
            else drawPath(Path().apply { moveTo(p[0].x, p[0].y); p.drop(1).forEach { lineTo(it.x, it.y) } }, farbe, style = dicke)
        }
        val letzte = tage.last()
        val punkt = Offset(x(letzte.tag), y(letzte.wert))
        drawCircle(farbe, 4.dp.toPx(), punkt)
        drawCircle(farbe, 7.5.dp.toPx(), punkt, style = Stroke(2.dp.toPx()))

        // Die Monatsanfaenge als Zeitachse; der erste Tag des Fensters zaehlt nicht, er ist kein Wechsel.
        generateSequence(von.plusMonths(1).withDayOfMonth(1)) { it.plusMonths(1) }.takeWhile { it <= bis }.forEach { tag ->
            val m = messer.measure(monatText(tag), stil)
            drawText(m, topLeft = Offset((x(tag) - m.size.width / 2f).coerceIn(0f, maxOf(0f, rechts - m.size.width)), size.height - m.size.height))
        }
    }
}
