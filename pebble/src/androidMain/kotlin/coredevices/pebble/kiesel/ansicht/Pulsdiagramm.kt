package coredevices.pebble.kiesel.ansicht

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import coredevices.pebble.kiesel.Pulspunkt
import coredevices.pebble.kiesel.Trainingsanalyse

/** Die Farben der Zonen - dieselben wie auf der Uhr (Kieselsport, thema.c). */
internal val ZONENFARBEN = listOf(
    Color(0xFFBDBDBD),   // unter Zone 1
    Color(0xFF55AAFF),   // PictonBlue
    Color(0xFF00AA55),   // JaegerGreen
    Color(0xFFAAAA00),   // Limerick
    Color(0xFFFFAA00),   // ChromeYellow
    Color(0xFFFF0055),   // Folly
)

/**
 * Der Puls ueber die Zeit, dahinter die Zonen als Baender.
 *
 * DIE BAENDER STATT EINER LEGENDE: wer die Kurve sieht, sieht sofort, in
 * welcher Zone sie lief - ohne Zahlen abzulesen. Die Grenzen haengen am
 * Maximalpuls, den die Uhr gemeldet hat.
 */
@Composable
internal fun Pulsdiagramm(puls: List<Pulspunkt>, maxpuls: Int, linie: Color, modifier: Modifier = Modifier) {
    if (puls.size < 2) return
    val tief = ((puls.minOf { it.bpm } - 5) / 10 * 10).coerceAtLeast(40)
    val hoch = ((puls.maxOf { it.bpm } + 14) / 10 * 10).coerceAtLeast(tief + 30)
    val dauer = puls.last().sekunde.coerceAtLeast(1)
    Canvas(modifier) {
        fun y(bpm: Long): Float = size.height * (1f - (bpm - tief).toFloat() / (hoch - tief))
        fun x(s: Long): Float = size.width * s.toFloat() / dauer
        // Die Zonen als Baender, jede bis zur naechsten Grenze.
        for (z in 1..5) {
            val unten = maxpuls * Trainingsanalyse.ZONEN_PROZENT[z - 1] / 100L
            val oben = if (z < 5) maxpuls * Trainingsanalyse.ZONEN_PROZENT[z] / 100L else hoch
            val yo = y(oben.coerceIn(tief, hoch))
            val yu = y(unten.coerceIn(tief, hoch))
            if (yu > yo) {
                drawRect(ZONENFARBEN[z].copy(alpha = 0.14f), Offset(0f, yo), Size(size.width, yu - yo))
            }
        }
        // Die Kurve - mit Luecken, wo mehr als eine Minute kein Wert kam.
        val pfad = Path()
        var offen = false
        puls.forEachIndexed { i, p ->
            val luecke = i > 0 && p.sekunde - puls[i - 1].sekunde > 60
            if (!offen || luecke) pfad.moveTo(x(p.sekunde), y(p.bpm)) else pfad.lineTo(x(p.sekunde), y(p.bpm))
            offen = true
        }
        drawPath(pfad, linie, style = Stroke(width = 2.5f * density))
    }
}
