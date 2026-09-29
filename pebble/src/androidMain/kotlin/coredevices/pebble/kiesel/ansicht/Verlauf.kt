package coredevices.pebble.kiesel.ansicht

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Trainingsanalyse
import java.util.Locale

/** Die Farben der Zonen - dieselben wie auf der Uhr (Kieselsport, thema.c). */
internal val ZONENFARBEN = listOf(
    Color(0xFFBDBDBD),   // unter Zone 1
    Color(0xFF55AAFF),   // PictonBlue
    Color(0xFF00AA55),   // JaegerGreen
    Color(0xFFAAAA00),   // Limerick
    Color(0xFFFFAA00),   // ChromeYellow
    Color(0xFFFF0055),   // Folly
)

/** Langsam blau, schnell rot - ueber Cyan, Gruen und Gelb, damit man die Mitte sieht. */
internal fun tempofarbe(anteil: Float): Color =
    Color.hsv(220f - 220f * anteil.coerceIn(0f, 1f), 0.85f, 0.9f)

/** Die Zonen als waagrechte Baender (von, bis, Farbe) fuer einen Pulsverlauf. */
internal fun zonenbaender(maxpuls: Int): List<Triple<Double, Double, Color>> = (1..5).map { z ->
    val von = maxpuls * Trainingsanalyse.ZONEN_PROZENT[z - 1] / 100.0
    val bis = if (z == 5) 250.0 else maxpuls * Trainingsanalyse.ZONEN_PROZENT[z] / 100.0
    Triple(von, bis, ZONENFARBEN[z])
}

/**
 * Eine Linie ueber der Strecke oder der Zeit - wie StreckenverlaufView in
 * Kiesel-Helper.
 *
 * [baender] sind waagrechte Streifen hinter der Linie (beim Puls die Zonen),
 * [xBaender] senkrechte (Saetze, Bahnen). [farbeJeWert] faerbt die Linie
 * Stueck fuer Stueck nach dem Wert - beim Tempo blau bis rot wie auf der
 * Karte. Mit [zeitachse] sind die `meter` der Punkte Sekunden, und die Achse
 * zeigt Minuten; mit [zaehlachse] zaehlt sie nur (Bahn 1, 2, ...).
 */
@Composable
internal fun Verlauf(
    punkte: List<Trainingsanalyse.Verlaufspunkt>,
    ton: Color,
    einheit: String,
    baender: List<Triple<Double, Double, Color>> = emptyList(),
    farbeJeWert: ((Double) -> Color)? = null,
    flaeche: Boolean = true,
    nachkomma: Int = 0,
    hoehe: Dp = 160.dp,
    zeitachse: Boolean = false,
    zaehlachse: Boolean = false,
    xBaender: List<Pair<Double, Double>> = emptyList(),
) {
    if (punkte.size < 2) return
    val messer = rememberTextMeasurer()
    val stil = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val raster = MaterialTheme.colorScheme.outlineVariant

    Canvas(Modifier.fillMaxWidth().height(hoehe).padding(top = 8.dp)) {
        val links = 30.dp.toPx()
        val fuss = 16.dp.toPx()
        val kopf = 8.dp.toPx()
        val boden = size.height - fuss
        val anfang = if (zaehlachse) punkte.first().meter else 0.0
        val ende = punkte.last().meter.coerceAtLeast(anfang + 1.0)
        var tief = punkte.minOf { it.wert }
        var hoch = punkte.maxOf { it.wert }
        val luft = ((hoch - tief) * 0.1).coerceAtLeast(1.0)
        tief -= luft
        hoch += luft
        val spanne = (hoch - tief).coerceAtLeast(0.1)

        fun x(m: Double) = links + (size.width - links) * ((m - anfang) / (ende - anfang)).toFloat()
        fun y(w: Double) = kopf + (boden - kopf) * (1f - ((w - tief) / spanne).toFloat())

        // Baender hinter allem - erst die senkrechten, dann die waagrechten.
        xBaender.forEach { (von, bis) ->
            drawRect(ton.copy(alpha = 0.16f), Offset(x(von), kopf), Size(x(bis) - x(von), boden - kopf))
        }
        baender.forEach { (von, bis, farbe) ->
            val yo = y(bis.coerceAtMost(hoch)).coerceAtLeast(kopf)
            val yu = y(von.coerceAtLeast(tief)).coerceAtMost(boden)
            if (yu > yo) drawRect(farbe.copy(alpha = 0.14f), Offset(links, yo), Size(size.width - links, yu - yo))
        }

        // Drei Rasterlinien mit Wert.
        val strich = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
        for (k in 0..2) {
            val w = tief + spanne * k / 2
            val yy = y(w)
            drawLine(raster, Offset(links, yy), Offset(size.width, yy), 1.dp.toPx(), pathEffect = strich)
            val m = messer.measure(String.format(Locale.getDefault(), "%.${nachkomma}f", w), stil)
            drawText(m, topLeft = Offset(links - 4.dp.toPx() - m.size.width, yy - m.size.height / 2f))
        }

        val pfad = Path()
        punkte.forEachIndexed { i, p ->
            if (i == 0) pfad.moveTo(x(p.meter), y(p.wert)) else pfad.lineTo(x(p.meter), y(p.wert))
        }
        if (flaeche) {
            val f = Path().apply {
                addPath(pfad)
                lineTo(x(punkte.last().meter), boden)
                lineTo(x(punkte.first().meter), boden)
                close()
            }
            drawPath(f, Brush.verticalGradient(listOf(ton.copy(alpha = 0.33f), ton.copy(alpha = 0f)), kopf, boden))
        }
        val breite = 2.5.dp.toPx()
        if (farbeJeWert == null) {
            drawPath(pfad, ton, style = Stroke(breite, cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            for (i in 1 until punkte.size) {
                drawLine(
                    farbeJeWert(punkte[i].wert),
                    Offset(x(punkte[i - 1].meter), y(punkte[i - 1].wert)),
                    Offset(x(punkte[i].meter), y(punkte[i].wert)),
                    breite, cap = StrokeCap.Round,
                )
            }
        }

        // Die Achse: Kilometer, Minuten - oder nur gezaehlt.
        val (bis, schritt, rechts) = when {
            zaehlachse -> Triple(ende, if (ende > 40) 10 else if (ende > 10) 5 else 1, "")
            zeitachse -> (ende / 60).let { min ->
                Triple(min, if (min > 120) 30 else if (min > 60) 15 else if (min > 30) 10 else if (min > 10) 5 else 0,
                    String.format(Locale.getDefault(), "%.0f min", min))
            }
            else -> (ende / 1000).let { km ->
                Triple(km, if (km > 40) 10 else if (km > 20) 5 else if (km > 8) 2 else if (km > 3) 1 else 0,
                    String.format(Locale.getDefault(), "%.1f km", km))
            }
        }
        val faktor = if (zaehlachse) 1.0 else if (zeitachse) 60.0 else 1000.0
        // Die Gesamtlaenge rechts hat Vorrang - ein Strich, der in sie
        // hineinliefe, faellt weg.
        val rechtsBreite = if (rechts.isEmpty()) 0 else messer.measure(rechts, stil).size.width
        val grenze = size.width - rechtsBreite - 4.dp.toPx()
        if (schritt > 0) {
            var k = if (zaehlachse) schritt.coerceAtLeast(anfang.toInt()) else schritt
            while (k < bis) {
                val m = messer.measure("$k", stil)
                val xk = x(k * faktor)
                if (xk + m.size.width / 2f <= grenze) {
                    drawText(m, topLeft = Offset(xk - m.size.width / 2f, size.height - m.size.height))
                }
                k += schritt
            }
        }
        if (rechts.isNotEmpty()) {
            val m = messer.measure(rechts, stil)
            drawText(m, topLeft = Offset(size.width - m.size.width, size.height - m.size.height))
        }
        drawText(messer.measure(einheit, stil), topLeft = Offset(links + 4.dp.toPx(), kopf))
    }
}

/** Die Zonen als ein Balken - die Anteile nebeneinander. */
@Composable
internal fun Zonenbalken(sekunden: LongArray) {
    val summe = sekunden.sum().coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp).height(18.dp).clip(RoundedCornerShape(6.dp))) {
        for (z in 0..5) {
            if (sekunden[z] > 0) Box(Modifier.weight(sekunden[z].toFloat() / summe).fillMaxHeight().background(ZONENFARBEN[z]))
        }
    }
}

/** Eine Zeile je Zone: Farbe, Name, Grenzen, Zeit, Anteil. */
@Composable
internal fun Zonenliste(sekunden: LongArray, maxpuls: Int) {
    val summe = sekunden.sum().coerceAtLeast(1)
    val namen = listOf(
        stringResource(R.string.ks_zone_unter), stringResource(R.string.ks_zone_1), stringResource(R.string.ks_zone_2),
        stringResource(R.string.ks_zone_3), stringResource(R.string.ks_zone_4), stringResource(R.string.ks_zone_5),
    )
    val zart = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        for (z in 5 downTo 0) {
            if (sekunden[z] == 0L) continue
            val von = if (z == 0) 0 else maxpuls * Trainingsanalyse.ZONEN_PROZENT[z - 1] / 100
            val bis = when (z) {
                0 -> maxpuls * 50 / 100 - 1
                5 -> maxpuls
                else -> maxpuls * Trainingsanalyse.ZONEN_PROZENT[z] / 100 - 1
            }
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(ZONENFARBEN[z], CircleShape))
                Text(
                    if (z == 0) namen[0] else stringResource(R.string.ks_v_zone_n, z, namen[z]),
                    Modifier.weight(1f).padding(start = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("$von–$bis", Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall, color = zart)
                Text(dauerText(sekunden[z] / 60), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${sekunden[z] * 100 / summe} %", Modifier.width(48.dp),
                    style = MaterialTheme.typography.bodySmall, color = zart,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
    }
}

/** Die Legende zur Farbkarte: der Verlauf mit den Werten an den Enden. */
@Composable
internal fun Farblegende(links: String, rechts: String) {
    Canvas(Modifier.fillMaxWidth().padding(top = 8.dp).height(8.dp)) {
        val stufen = 24
        val b = size.width / stufen
        for (k in 0 until stufen) drawRect(tempofarbe(k / (stufen - 1f)), Offset(k * b, 0f), Size(b + 1, size.height))
    }
    Row(Modifier.fillMaxWidth()) {
        val stil = MaterialTheme.typography.bodySmall
        val zart = MaterialTheme.colorScheme.onSurfaceVariant
        Text(links, Modifier.weight(1f), style = stil, color = zart)
        Text(rechts, style = stil, color = zart)
    }
}

/** Fuer osmdroid, das mit Android-Farben zeichnet. */
internal fun Color.alsInt(): Int = toArgb()
