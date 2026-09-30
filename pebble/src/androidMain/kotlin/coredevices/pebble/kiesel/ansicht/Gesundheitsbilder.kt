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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import coredevices.pebble.R
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.Nacht
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** Die Farben der Schlafphasen: wach, leicht, tief, REM, Schlaf ohne Phase. */
internal val PHASENFARBEN = listOf(
    Color(0xFFE0A33A), Color(0xFF7FA7D9), Color(0xFF2F5597), Color(0xFF9B6BC9), Color(0xFF6C8EBF),
)

/** Gestrichelt ist ein Bezugswert (Mittel, Ruhepuls), durchgezogen das Ziel. */
private fun DrawScope.gestrichelt() = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))

/**
 * Wo die Beschriftung einer waagrechten Linie steht: rechts oben ueber ihrem
 * Ende. [unten] legt sie unter die Linie, [links] an deren Anfang - beides,
 * wenn oben rechts schon eine andere sitzt.
 */
private fun DrawScope.linienlabel(y: Float, von: Float, bis: Float, m: TextLayoutResult, unten: Boolean, links: Boolean): Rect {
    val oben = if (unten) y + 2.dp.toPx() else y - 2.dp.toPx() - m.size.height
    val x = if (links) von else bis - m.size.width
    return Rect(Offset(x, oben.coerceIn(0f, size.height - m.size.height)), m.size.toSize())
}

private fun DrawScope.beschrifteteLinie(
    y: Float, von: Float, bis: Float, farbe: Color, m: TextLayoutResult?, gestrichelt: Boolean,
    unten: Boolean = false, links: Boolean = false,
) {
    drawLine(farbe, Offset(von, y), Offset(bis, y), 1.dp.toPx(), pathEffect = if (gestrichelt) gestrichelt() else null)
    if (m != null) drawText(m, topLeft = linienlabel(y, von, bis, m, unten, links).topLeft)
}

/**
 * Saeulen mit Beschriftung darunter und ihrem Wert darueber. Die letzte
 * Saeule ist die juengste und steht kraeftig, die uebrigen blasser. Das Ziel
 * ist eine durchgezogene Linie, der Mittelwert eine gestrichelte; beide
 * tragen ihren Namen am rechten Ende. Die Spitze liegt ein Fuenftel ueber
 * dem hoechsten Wert und dem Ziel, damit weder Zahl noch Linie am Rand klebt.
 * Eine Saeulenzahl, die einer Linienbeschriftung in die Quere kaeme, faellt
 * weg - bei der letzten Saeule der Normalfall, ihr Wert steht ohnehin gross
 * in der Wertezeile.
 */
@Composable
internal fun Saeulen(
    werte: List<Pair<String, Double>>,
    farbe: Color,
    ziel: Double? = null,
    hoehe: Dp = 110.dp,
    beschrifte: ((Double) -> String)? = null,
    zielText: String? = null,
    mittel: Double? = null,
    mittelText: String? = null,
    spitzeMindestens: Double = 0.0,
) {
    if (werte.isEmpty()) return
    val messer = rememberTextMeasurer()
    val stil = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val fett = stil.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
    val leer = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val linie = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.fillMaxWidth().height(hoehe).padding(top = 8.dp)) {
        val fuss = 16.dp.toPx()
        val kopf = if (beschrifte != null || zielText != null || mittelText != null) 14.dp.toPx() else 0f
        val boden = size.height - fuss
        val spitze = maxOf(werte.maxOf { it.second }, ziel ?: 0.0, mittel ?: 0.0, spitzeMindestens, 1.0) * 1.2
        val fach = size.width / werte.size
        val breite = minOf(fach * 0.55f, 24.dp.toPx())
        val ecke = CornerRadius(4.dp.toPx())
        fun y(w: Double) = (kopf + (boden - kopf) * (1 - w / spitze)).toFloat()

        // Wo die Linienbeschriftungen stehen, entscheidet sich vor den Saeulen: ihre Zahlen weichen ihnen aus.
        // Liegen Ziel und Mittel nah beieinander, rutscht "avg" unter seine Linie und an den linken Rand,
        // denn rechts unter der Linie stuende es in der vollen Farbe der heutigen Saeule.
        val yZiel = ziel?.let { y(it) }
        val yMittel = mittel?.let { y(it) }
        val nah = yZiel != null && yMittel != null && abs(yZiel - yMittel) < 12.dp.toPx()
        val zielM = zielText?.let { messer.measure(it, stil) }
        val mittelM = mittelText?.let { messer.measure(it, stil) }
        val belegt = listOfNotNull(
            if (yZiel != null && zielM != null) linienlabel(yZiel, 0f, size.width, zielM, unten = false, links = false) else null,
            if (yMittel != null && mittelM != null) linienlabel(yMittel, 0f, size.width, mittelM, unten = nah, links = nah) else null,
        )

        // Passt das breiteste Label nicht ins Fach, bekommen nur hoechste, tiefste und letzte Saeule eines.
        val beschriftungen = werte.map { (_, w) -> if (beschrifte != null && w > 0) beschrifte(w) else null }
        val gemessen = beschriftungen.mapIndexed { i, t -> t?.let { messer.measure(it, if (i == werte.lastIndex) fett else stil) } }
        val eng = gemessen.any { it != null && it.size.width > fach - 2.dp.toPx() }
        val mitWert = werte.withIndex().filter { it.value.second > 0 }
        val zeigen = if (!eng) mitWert.map { it.index }.toSet() else setOfNotNull(
            mitWert.maxByOrNull { it.value.second }?.index, mitWert.minByOrNull { it.value.second }?.index,
            werte.lastIndex.takeIf { werte.last().second > 0 },
        )

        werte.forEachIndexed { i, (name, wert) ->
            val mitte = fach * i + fach / 2
            drawRoundRect(leer, Offset(mitte - breite / 2, kopf), Size(breite, boden - kopf), ecke)
            if (wert > 0) {
                val oben = y(wert)
                drawRoundRect(farbe.copy(alpha = if (i == werte.lastIndex) 1f else 0.6f), Offset(mitte - breite / 2, oben), Size(breite, boden - oben), ecke)
                val m = gemessen[i]
                if (m != null && i in zeigen) {
                    val platz = Rect(
                        Offset((mitte - m.size.width / 2f).coerceIn(0f, size.width - m.size.width), (oben - 2.dp.toPx() - m.size.height).coerceAtLeast(0f)),
                        m.size.toSize(),
                    )
                    if (belegt.none { it.overlaps(platz) }) drawText(m, topLeft = platz.topLeft)
                }
            }
            val m = messer.measure(name, if (i == werte.lastIndex) fett else stil)
            drawText(m, topLeft = Offset(mitte - m.size.width / 2f, size.height - m.size.height))
        }
        yZiel?.let { beschrifteteLinie(it, 0f, size.width, linie, zielM, gestrichelt = false) }
        yMittel?.let { beschrifteteLinie(it, 0f, size.width, linie, mittelM, gestrichelt = true, unten = nah, links = nah) }
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

/** Das Nachtfenster als leichte Schattierung ueber die volle Hoehe, "night" oben links darin. */
private fun DrawScope.nachtschatten(
    nacht: Pair<Long, Long>?, von: Long, bis: Long, x: (Long) -> Float, boden: Float, farbe: Color, text: String, messer: TextMeasurer, stil: TextStyle,
) {
    val (a, b) = nacht ?: return
    val anfang = maxOf(a, von)
    val ende = minOf(b, bis)
    if (ende <= anfang) return
    drawRect(farbe, Offset(x(anfang), 0f), Size(x(ende) - x(anfang), boden))
    val m = messer.measure(text, stil)
    if (m.size.width < x(ende) - x(anfang)) drawText(m, topLeft = Offset(x(anfang) + 4.dp.toPx(), 2.dp.toPx()))
}

/**
 * Werte ueber die letzten 24 Stunden als Punkte, mit den Stunden darunter -
 * fuer Puls und SpO2. Punkte statt Linie: zwischen zwei Messungen weiss
 * niemand, was war. Ohne [bereich] und [raster] spannt sich die Achse um die
 * Werte, so wie die Nachtansicht es braucht; mit ihnen steht sie fest, und
 * Werte unter [warnUnter] stechen als groesserer Punkt in der Warnfarbe hervor.
 */
@Composable
internal fun Tageskurve(
    punkte: List<Pair<Long, Int>>,
    von: Long,
    bis: Long,
    farbe: Color,
    hoehe: Dp = 120.dp,
    bereich: IntRange? = null,
    raster: List<Int>? = null,
    nacht: Pair<Long, Long>? = null,
    warnUnter: Int? = null,
) {
    if (punkte.isEmpty()) return
    val messer = rememberTextMeasurer()
    val stil = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val rasterFarbe = MaterialTheme.colorScheme.outlineVariant
    val warnFarbe = MaterialTheme.colorScheme.error
    val schatten = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val nachtText = stringResource(R.string.ks_h_nacht_kurz)
    Canvas(Modifier.fillMaxWidth().height(hoehe).padding(top = 8.dp)) {
        val links = 28.dp.toPx()
        val fuss = 16.dp.toPx()
        val boden = size.height - fuss
        val tief = bereich?.first ?: (punkte.minOf { it.second } - 2)
        val hoch = maxOf(bereich?.last ?: (punkte.maxOf { it.second } + 2), tief + 1)
        val spanne = (bis - von).coerceAtLeast(1).toFloat()
        fun x(t: Long) = links + (size.width - links) * (t - von) / spanne
        fun y(w: Int) = boden * (1f - (w - tief).toFloat() / (hoch - tief))
        nachtschatten(nacht, von, bis, ::x, boden, schatten, nachtText, messer, stil)
        for (w in raster ?: listOf(tief + 2, (tief + hoch) / 2, hoch - 2)) {
            drawLine(rasterFarbe, Offset(links, y(w)), Offset(size.width, y(w)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
            val m = messer.measure("$w", stil)
            drawText(m, topLeft = Offset(links - 4.dp.toPx() - m.size.width, y(w) - m.size.height / 2f))
        }
        val r = if (punkte.size > 300) 1.5.dp.toPx() else 2.5.dp.toPx()
        punkte.forEach { (t, w) ->
            val p = Offset(x(t), y(w.coerceIn(tief, hoch)))
            if (warnUnter != null && w < warnUnter) drawCircle(warnFarbe, 3.5.dp.toPx(), p) else drawCircle(farbe.copy(alpha = 0.7f), r, p)
        }
        stundenstriche(von, bis) { t, text ->
            val m = messer.measure(text, stil)
            drawText(m, topLeft = Offset((x(t) - m.size.width / 2f).coerceIn(links, size.width - m.size.width), size.height - m.size.height))
        }
    }
}

/**
 * Der Puls der letzten 24 Stunden als Band: fuenf Minuten je Bin, die
 * Medianlinie darueber, die Spanne tiefster..hoechster als Flaeche. Ein
 * Band statt tausend Punkte: die Punkte verschmieren zu einem roten Nebel,
 * das Band zeigt Ruhe und Belastung auf einen Blick. Luecken ueber einer
 * Viertelstunde brechen Linie und Band - dort war die Uhr nicht am Arm.
 * Der Ruhepuls liegt gestrichelt darin und ersetzt die Rasterlinien, die ihm
 * zu nah kaemen.
 */
@Composable
internal fun Pulsband(
    punkte: List<Pair<Long, Int>>,
    von: Long,
    bis: Long,
    farbe: Color,
    ruhepuls: Int? = null,
    nacht: Pair<Long, Long>? = null,
    hoehe: Dp = 130.dp,
) {
    val bins = Gesundheitsrechnung.fuenfMinuten(punkte)
    if (bins.isEmpty()) return
    val messer = rememberTextMeasurer()
    val stil = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val kraeftig = stil.copy(color = MaterialTheme.colorScheme.onSurface)
    val rasterFarbe = MaterialTheme.colorScheme.outlineVariant
    val linie = MaterialTheme.colorScheme.onSurfaceVariant
    val schatten = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val nachtText = stringResource(R.string.ks_h_nacht_kurz)
    val ruheM = ruhepuls?.let { messer.measure(stringResource(R.string.ks_h_ruhe_kurz, it), stil) }
    Canvas(Modifier.fillMaxWidth().height(hoehe).padding(top = 8.dp)) {
        val links = 28.dp.toPx()
        // Rechts bleibt Platz fuer die Zahl neben dem letzten Punkt - der liegt am "jetzt", also am Rand.
        val rechts = size.width - 26.dp.toPx()
        val fuss = 16.dp.toPx()
        // Oben etwas Luft: eine Rasterlinie genau am Rand truege ihre Zahl halb ausserhalb.
        val kopf = 6.dp.toPx()
        val boden = size.height - fuss
        val tief = minOf(bins.minOf { it.tiefster }, ruhepuls ?: Int.MAX_VALUE) - 2
        // Mindestens 30 Schlaege Spanne, damit drei Zehnerlinien hineinpassen.
        val hoch = (maxOf(bins.maxOf { it.hoechster }, ruhepuls ?: 0) + 2).coerceAtLeast(tief + 30)
        val spanne = (bis - von).coerceAtLeast(1).toFloat()
        fun x(t: Long) = links + (rechts - links) * (t - von) / spanne
        fun y(w: Int) = kopf + (boden - kopf) * (1f - (w - tief).toFloat() / (hoch - tief))
        nachtschatten(nacht, von, bis, ::x, boden, schatten, nachtText, messer, stil)

        // Der Abstand in Schlaegen, unter dem sich zwei Achsenzahlen beruehren.
        val zuNah = 12.dp.toPx() * (hoch - tief) / (boden - kopf)
        val raster = Gesundheitsrechnung.rasterOhne(Gesundheitsrechnung.raster(tief, hoch, 10), ruhepuls, zuNah)
        fun achse(w: Int) {
            val m = messer.measure("$w", stil)
            drawText(m, topLeft = Offset(links - 4.dp.toPx() - m.size.width, y(w) - m.size.height / 2f))
        }
        raster.forEach { w ->
            drawLine(rasterFarbe, Offset(links, y(w)), Offset(rechts, y(w)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
            achse(w)
        }

        Gesundheitsrechnung.stuecke(bins).forEach { st ->
            // Ein einzelner Bin haette weder Flaeche noch Strecke - er wird ein Punkt.
            if (st.size == 1) {
                drawCircle(farbe, 2.dp.toPx(), Offset(x(st[0].sekunde), y(st[0].median)))
                return@forEach
            }
            val band = Path().apply {
                st.forEachIndexed { i, b -> if (i == 0) moveTo(x(b.sekunde), y(b.hoechster)) else lineTo(x(b.sekunde), y(b.hoechster)) }
                st.asReversed().forEach { b -> lineTo(x(b.sekunde), y(b.tiefster)) }
                close()
            }
            drawPath(band, farbe.copy(alpha = 0.18f))
            val mitte = Path().apply {
                st.forEachIndexed { i, b -> if (i == 0) moveTo(x(b.sekunde), y(b.median)) else lineTo(x(b.sekunde), y(b.median)) }
            }
            drawPath(mitte, farbe, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
        val letzter = bins.last()
        val p = Offset(x(letzter.sekunde), y(letzter.median))
        // Der Ruhepuls erst ueber dem Band, damit seine Beschriftung lesbar bleibt; laeuft die
        // Medianlinie am Ende durch sie hindurch, rutscht sie unter die Linie.
        if (ruhepuls != null && ruheM != null) {
            val yRuhe = y(ruhepuls)
            val unten = p.y > yRuhe - ruheM.size.height - 6.dp.toPx() && p.y < yRuhe + 4.dp.toPx()
            beschrifteteLinie(yRuhe, links, rechts, linie, ruheM, gestrichelt = true, unten = unten)
            achse(ruhepuls)
        }
        drawCircle(farbe, 3.dp.toPx(), p)
        val m = messer.measure("${letzter.median}", kraeftig)
        drawText(m, topLeft = Offset(p.x + 5.dp.toPx(), (p.y - m.size.height / 2f).coerceIn(0f, boden - m.size.height)))

        stundenstriche(von, bis) { t, text ->
            val s = messer.measure(text, stil)
            drawText(s, topLeft = Offset((x(t) - s.size.width / 2f).coerceIn(links, rechts - s.size.width), size.height - s.size.height))
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
