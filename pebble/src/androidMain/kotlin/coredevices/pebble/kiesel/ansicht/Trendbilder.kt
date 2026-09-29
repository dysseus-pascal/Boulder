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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coredevices.pebble.kiesel.Auswertung

/** Eine Saeule im Trend: der Wert, ein Teil davon innen (Tiefschlaf im Schlaf), hervorgehoben oder nicht. */
internal data class TSaeule(val name: String, val wert: Double?, val innen: Double? = null, val hervor: Boolean = false)

/**
 * Saeulen je Wochentag oder Woche - wie saeulenbild in Kiesel-Helper. Die
 * gestrichelte Linie ist ueberall der Mittelwert aller Tage, die durchgezogene
 * ein Ziel (acht Stunden Schlaf).
 */
@Composable
internal fun Trendsaeulen(saeulen: List<TSaeule>, farbe: Color, mittel: Double? = null, marke: Double? = null, hoehe: Dp = 130.dp) {
    if (saeulen.none { it.wert != null }) return
    val messer = rememberTextMeasurer()
    val stil = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val fett = stil.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
    val leer = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val linie = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.fillMaxWidth().height(hoehe).padding(top = 8.dp)) {
        val fuss = 16.dp.toPx()
        val boden = size.height - fuss
        val spitze = maxOf(saeulen.maxOf { it.wert ?: 0.0 }, marke ?: 0.0, 1.0) * 1.05
        val fach = size.width / saeulen.size
        val breite = minOf(fach * 0.55f, 26.dp.toPx())
        val ecke = CornerRadius(4.dp.toPx())
        fun y(w: Double) = (boden * (1 - w / spitze)).toFloat()
        saeulen.forEachIndexed { i, s ->
            val links = fach * i + (fach - breite) / 2
            drawRoundRect(leer, Offset(links, 0f), Size(breite, boden), ecke)
            s.wert?.takeIf { it > 0 }?.let { w ->
                drawRoundRect(farbe.copy(alpha = if (s.hervor) 1f else 0.55f), Offset(links, y(w)), Size(breite, boden - y(w)), ecke)
            }
            s.innen?.takeIf { it > 0 }?.let { w ->
                drawRoundRect(farbe.copy(red = farbe.red * 0.6f, green = farbe.green * 0.6f, blue = farbe.blue * 0.6f), Offset(links + breite * 0.2f, y(w)), Size(breite * 0.6f, boden - y(w)), ecke)
            }
            val m = messer.measure(s.name, if (s.hervor) fett else stil)
            drawText(m, topLeft = Offset(fach * i + fach / 2 - m.size.width / 2f, size.height - m.size.height))
        }
        mittel?.let {
            drawLine(linie, Offset(0f, y(it)), Offset(size.width, y(it)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
        }
        marke?.let { drawLine(linie, Offset(0f, y(it)), Offset(size.width, y(it)), 1.5.dp.toPx()) }
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
