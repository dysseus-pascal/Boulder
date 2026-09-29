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
import coredevices.pebble.kiesel.Nacht
import java.time.Instant
import java.time.ZoneId

/** Die Farben der Schlafphasen: wach, leicht, tief, REM, Schlaf ohne Phase. */
internal val PHASENFARBEN = listOf(
    Color(0xFFE0A33A), Color(0xFF7FA7D9), Color(0xFF2F5597), Color(0xFF9B6BC9), Color(0xFF6C8EBF),
)

/**
 * Saeulen mit Beschriftung darunter und einem gestrichelten Ziel. Die letzte
 * Saeule ist die juengste und steht kraeftig, die uebrigen blasser.
 */
@Composable
internal fun Saeulen(werte: List<Pair<String, Double>>, farbe: Color, ziel: Double? = null, hoehe: Dp = 110.dp) {
    if (werte.isEmpty()) return
    val messer = rememberTextMeasurer()
    val stil = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val fett = stil.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
    val leer = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val linie = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.fillMaxWidth().height(hoehe).padding(top = 8.dp)) {
        val fuss = 16.dp.toPx()
        val boden = size.height - fuss
        val spitze = maxOf(werte.maxOf { it.second }, ziel ?: 0.0, 1.0)
        val fach = size.width / werte.size
        val breite = minOf(fach * 0.55f, 24.dp.toPx())
        val ecke = CornerRadius(4.dp.toPx())
        werte.forEachIndexed { i, (name, wert) ->
            val mitte = fach * i + fach / 2
            drawRoundRect(leer, Offset(mitte - breite / 2, 0f), Size(breite, boden), ecke)
            if (wert > 0) {
                val h = (boden * (wert / spitze)).toFloat()
                drawRoundRect(farbe.copy(alpha = if (i == werte.lastIndex) 1f else 0.6f), Offset(mitte - breite / 2, boden - h), Size(breite, h), ecke)
            }
            val m = messer.measure(name, if (i == werte.lastIndex) fett else stil)
            drawText(m, topLeft = Offset(mitte - m.size.width / 2f, size.height - m.size.height))
        }
        ziel?.let {
            val y = (boden * (1 - it / spitze)).toFloat()
            drawLine(linie, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
        }
    }
}

/**
 * Die Nacht in vier Bahnen - wach oben, tief unten - wie das Hypnogramm in
 * Kiesel-Helper. Schlaf ohne Phase (eine Nacht ohne Puls) liegt in der Bahn
 * des leichten Schlafs.
 */
@Composable
internal fun Hypnogramm(nacht: Nacht, hoehe: Dp = 90.dp) {
    val messer = rememberTextMeasurer()
    val stil = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val bahnen = mapOf(0 to 0, 3 to 1, 1 to 2, 4 to 2, 2 to 3)
    Canvas(Modifier.fillMaxWidth().height(hoehe).padding(top = 8.dp)) {
        val fuss = 16.dp.toPx()
        val bahn = (size.height - fuss) / 4
        val von = nacht.abschnitte.minOfOrNull { it.von.epochSecond } ?: return@Canvas
        val bis = nacht.abschnitte.maxOf { it.bis.epochSecond }
        val spanne = (bis - von).coerceAtLeast(1).toFloat()
        fun x(t: Instant) = size.width * (t.epochSecond - von) / spanne
        nacht.abschnitte.forEach { a ->
            val b = bahnen[a.phase] ?: return@forEach
            drawRoundRect(
                PHASENFARBEN[a.phase], Offset(x(a.von), bahn * b + 2), Size((x(a.bis) - x(a.von)).coerceAtLeast(1f), bahn - 4),
                CornerRadius(2.dp.toPx()),
            )
        }
        stundenstriche(von, bis) { t, text ->
            val m = messer.measure(text, stil)
            drawText(m, topLeft = Offset((size.width * (t - von) / spanne - m.size.width / 2f).coerceIn(0f, size.width - m.size.width), size.height - m.size.height))
        }
    }
}

/**
 * Werte ueber die letzten 24 Stunden als Punkte, mit den Stunden darunter -
 * fuer Puls und SpO2. Punkte statt Linie: zwischen zwei Messungen weiss
 * niemand, was war.
 */
@Composable
internal fun Tageskurve(punkte: List<Pair<Long, Int>>, von: Long, bis: Long, farbe: Color, hoehe: Dp = 120.dp) {
    if (punkte.isEmpty()) return
    val messer = rememberTextMeasurer()
    val stil = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val raster = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(hoehe).padding(top = 8.dp)) {
        val links = 28.dp.toPx()
        val fuss = 16.dp.toPx()
        val boden = size.height - fuss
        val tief = punkte.minOf { it.second } - 2
        val hoch = punkte.maxOf { it.second } + 2
        val spanne = (bis - von).coerceAtLeast(1).toFloat()
        fun x(t: Long) = links + (size.width - links) * (t - von) / spanne
        fun y(w: Int) = boden * (1f - (w - tief).toFloat() / (hoch - tief))
        for (w in listOf(tief + 2, (tief + hoch) / 2, hoch - 2)) {
            drawLine(raster, Offset(links, y(w)), Offset(size.width, y(w)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
            val m = messer.measure("$w", stil)
            drawText(m, topLeft = Offset(links - 4.dp.toPx() - m.size.width, y(w) - m.size.height / 2f))
        }
        val r = if (punkte.size > 300) 1.5.dp.toPx() else 2.5.dp.toPx()
        punkte.forEach { (t, w) -> drawCircle(farbe.copy(alpha = 0.7f), r, Offset(x(t), y(w))) }
        stundenstriche(von, bis) { t, text ->
            val m = messer.measure(text, stil)
            drawText(m, topLeft = Offset((x(t) - m.size.width / 2f).coerceIn(links, size.width - m.size.width), size.height - m.size.height))
        }
    }
}

/** Volle Stunden zwischen [von] und [bis] (Sekunden), hoechstens fuenf Beschriftungen. */
private fun stundenstriche(von: Long, bis: Long, zeichne: (Long, String) -> Unit) {
    val zone = ZoneId.systemDefault()
    val stunden = ((bis - von) / 3600).coerceAtLeast(1)
    val schritt = when {
        stunden > 16 -> 6
        stunden > 8 -> 3
        stunden > 4 -> 2
        else -> 1
    }
    var t = Instant.ofEpochSecond(von).atZone(zone).withMinute(0).withSecond(0).plusHours(1)
    while (t.toEpochSecond() < bis) {
        if (t.hour % schritt == 0) zeichne(t.toEpochSecond(), "${t.hour}")
        t = t.plusHours(1)
    }
}
