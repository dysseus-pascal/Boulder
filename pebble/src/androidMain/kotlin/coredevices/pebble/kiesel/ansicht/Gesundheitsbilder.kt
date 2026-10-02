package coredevices.pebble.kiesel.ansicht

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Pulsbin
import kotlin.math.roundToInt
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.Nacht
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Die Farben der Schlafphasen: wach, leicht, tief, REM, Schlaf ohne Phase. */
internal val PHASENFARBEN = listOf(
    Color(0xFFE0A33A), Color(0xFF7FA7D9), Color(0xFF2F5597), Color(0xFF9B6BC9), Color(0xFF6C8EBF),
)

/** Gestrichelt ist ein Bezugswert (Mittel, Ruhepuls), durchgezogen das Ziel. */
private fun DrawScope.gestrichelt() = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))

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
    drawRoundRect(farbe, Offset(x(anfang), 0f), Size(x(ende) - x(anfang), boden), CornerRadius(4.dp.toPx()))
    val m = messer.measure(text, stil)
    // Mittig oben, wie im Entwurf: so liest man "Nacht" als Ueberschrift der Flaeche.
    if (m.size.width < x(ende) - x(anfang)) drawText(m, topLeft = Offset((x(anfang) + x(ende) - m.size.width) / 2f, 3.dp.toPx()))
}

/**
 * Werte ueber eine Zeitspanne als Punkte, mit den Stunden darunter - Puls
 * und SpO2 der Nachtansicht. Punkte statt Linie: zwischen zwei Messungen
 * weiss niemand, was war. Die Achse spannt sich um die Werte.
 */
@Composable
internal fun Tageskurve(
    punkte: List<Pair<Long, Int>>,
    von: Long,
    bis: Long,
    farbe: Color,
    hoehe: Dp = 120.dp,
) {
    if (punkte.isEmpty()) return
    val messer = rememberTextMeasurer()
    val stil = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val rasterFarbe = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(hoehe).padding(top = 8.dp)) {
        val links = 28.dp.toPx()
        val fuss = 16.dp.toPx()
        val boden = size.height - fuss
        val tief = punkte.minOf { it.second } - 2
        val hoch = maxOf(punkte.maxOf { it.second } + 2, tief + 1)
        val spanne = (bis - von).coerceAtLeast(1).toFloat()
        fun x(t: Long) = links + (size.width - links) * (t - von) / spanne
        fun y(w: Int) = boden * (1f - (w - tief).toFloat() / (hoch - tief))
        for (w in listOf(tief + 2, (tief + hoch) / 2, hoch - 2)) {
            drawLine(rasterFarbe, Offset(links, y(w)), Offset(size.width, y(w)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
            val m = messer.measure("$w", stil)
            drawText(m, topLeft = Offset(links - 4.dp.toPx() - m.size.width, y(w) - m.size.height / 2f))
        }
        val r = if (punkte.size > 300) 1.5.dp.toPx() else 2.5.dp.toPx()
        punkte.forEach { (t, w) ->
            drawCircle(farbe.copy(alpha = 0.7f), r, Offset(x(t), y(w)))
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
    stundeText: (ZonedDateTime) -> String = { "${it.hour}" },
) {
    val bins = Gesundheitsrechnung.fuenfMinuten(punkte)
    if (bins.isEmpty()) return
    val messer = rememberTextMeasurer()
    val stil = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val kraeftig = stil.copy(color = MaterialTheme.colorScheme.onSurface)
    val rasterFarbe = MaterialTheme.colorScheme.outlineVariant
    val linie = MaterialTheme.colorScheme.onSurfaceVariant
    // Die Nacht als deutliche Flaeche: bei 6 % sah man sie auf dem Telefon nicht.
    val schatten = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val nachtText = stringResource(R.string.ks_h_nacht_kurz)
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

        Gesundheitsrechnung.stuecke(bins).forEach { roh ->
            // Ein einzelner Bin haette weder Flaeche noch Strecke - er wird ein Punkt.
            if (roh.size == 1) {
                drawCircle(farbe, 2.dp.toPx(), Offset(x(roh[0].sekunde), y(roh[0].median)))
                return@forEach
            }
            // GEGLAETTET: ueber eine gute halbe Stunde gemittelt und weich
            // gezeichnet. Fuenf-Minuten-Zacken lesen sich als Unruhe, die es
            // nicht gab - gefragt ist der Verlauf des Tages.
            val st = glaetten(roh, 3)
            val oben = st.map { Offset(x(it.sekunde), y(it.hoechster)) }
            val unten = st.map { Offset(x(it.sekunde), y(it.tiefster)) }
            val band = Path().apply {
                moveTo(oben.first().x, oben.first().y)
                weich(oben)
                lineTo(unten.last().x, unten.last().y)
                weich(unten.asReversed())
                close()
            }
            drawPath(band, farbe.copy(alpha = 0.22f))
            val punkteMitte = st.map { Offset(x(it.sekunde), y(it.median)) }
            val mitte = Path().apply {
                moveTo(punkteMitte.first().x, punkteMitte.first().y)
                weich(punkteMitte)
            }
            drawPath(mitte, farbe, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
        val letzter = bins.last()
        val p = Offset(x(letzter.sekunde), y(letzter.median))
        // Ohne Beschriftung im Bild: die Legende unter dem Band nennt den Ruhepuls schon.
        if (ruhepuls != null) {
            drawLine(linie, Offset(links, y(ruhepuls)), Offset(rechts, y(ruhepuls)), 1.dp.toPx(), pathEffect = gestrichelt())
            achse(ruhepuls)
        }
        drawCircle(farbe, 3.dp.toPx(), p)
        val m = messer.measure("${letzter.median}", kraeftig)
        drawText(m, topLeft = Offset(p.x + 5.dp.toPx(), (p.y - m.size.height / 2f).coerceIn(0f, boden - m.size.height)))

        stundenstriche(von, bis, stundeText) { t, text ->
            val s = messer.measure(text, stil)
            drawText(s, topLeft = Offset((x(t) - s.size.width / 2f).coerceIn(links, rechts - s.size.width), size.height - s.size.height))
        }
    }
}

/** Gleitendes Mittel ueber [halb] Bins nach jeder Seite - Median, Tiefster und Hoechster je fuer sich. */
private fun glaetten(bins: List<Pulsbin>, halb: Int): List<Pulsbin> = bins.indices.map { i ->
    val fenster = bins.subList(maxOf(0, i - halb), minOf(bins.size, i + halb + 1))
    Pulsbin(
        bins[i].sekunde,
        fenster.map { it.median }.average().roundToInt(),
        fenster.map { it.tiefster }.average().roundToInt(),
        fenster.map { it.hoechster }.average().roundToInt(),
    )
}

/**
 * Durch die Punkte weich weiterzeichnen: Kurven von Mitte zu Mitte mit den
 * Punkten als Stuetzen. Der Pfad steht schon auf dem ersten Punkt.
 */
private fun Path.weich(p: List<Offset>) {
    if (p.size < 3) { p.drop(1).forEach { lineTo(it.x, it.y) }; return }
    for (i in 1 until p.lastIndex) {
        val mitte = Offset((p[i].x + p[i + 1].x) / 2f, (p[i].y + p[i + 1].y) / 2f)
        quadraticTo(p[i].x, p[i].y, mitte.x, mitte.y)
    }
    lineTo(p.last().x, p.last().y)
}

/** Volle Stunden zwischen [von] und [bis] (Sekunden), hoechstens fuenf Beschriftungen. */
private fun stundenstriche(von: Long, bis: Long, text: (ZonedDateTime) -> String = { "${it.hour}" }, zeichne: (Long, String) -> Unit) {
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
        if (t.hour % schritt == 0) zeichne(t.toEpochSecond(), text(t))
        t = t.plusHours(1)
    }
}

/**
 * Ein Zielring: die Spur blass, der Anteil kraeftig, oben beginnend. Was
 * ueber das Ziel hinausgeht, laeuft als hellere zweite Runde darueber,
 * abgesetzt durch eine Fuge in der Kartenfarbe - sonst verschmoelze das
 * Plus mit dem vollen Ring und waere nicht zu sehen.
 */
@Composable
internal fun Zielring(
    anteil: Float,
    ueberlauf: Float,
    farbe: Color,
    spur: Color,
    ueberlaufFarbe: Color,
    fuge: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(104.dp)) {
        val dicke = 9.dp.toPx()
        val r = 46.dp.toPx()
        val ecke = Offset(center.x - r, center.y - r)
        val feld = Size(2 * r, 2 * r)
        drawCircle(spur, r, style = Stroke(dicke))
        if (anteil >= 1f) drawCircle(farbe, r, style = Stroke(dicke))
        else if (anteil > 0f) drawArc(farbe, -90f, 360f * anteil, false, ecke, feld, style = Stroke(dicke, cap = StrokeCap.Round))
        if (ueberlauf > 0f) {
            // Die Fuge beginnt knapp vor dem Plus und ist breiter als der Ring, damit sie ihn ganz durchtrennt.
            val fugeGrad = 360f * 3.dp.toPx() / (2 * Math.PI.toFloat() * r)
            drawArc(fuge, -90f - fugeGrad, 360f * ueberlauf + 2 * fugeGrad, false, ecke, feld, style = Stroke(dicke + 2.dp.toPx(), cap = StrokeCap.Round))
            drawArc(ueberlaufFarbe, -90f, 360f * ueberlauf, false, ecke, feld, style = Stroke(dicke, cap = StrokeCap.Round))
        }
    }
}

/** Der leere Ring einer Nacht, die nicht gemessen wurde: gestrichelt, ohne Anteil. */
@Composable
internal fun Leerring(farbe: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(104.dp)) {
        drawCircle(
            farbe, 46.dp.toPx(),
            style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx()))),
        )
    }
}

/**
 * Die Phasen einer Nacht als schmaler Balken - Anteile statt Verlauf. Der
 * Verlauf steht im Hypnogramm der Nachtansicht; hier reicht, wieviel wovon.
 */
@Composable
internal fun Anteilsbalken(teile: List<Pair<Int, Color>>, modifier: Modifier = Modifier) {
    val summe = teile.sumOf { it.first }.coerceAtLeast(1)
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val luecke = 2.dp.toPx()
        val sichtbar = teile.filter { it.first > 0 }
        val platz = size.width - luecke * (sichtbar.size - 1).coerceAtLeast(0)
        var x = 0f
        sichtbar.forEach { (wert, farbe) ->
            val breite = platz * wert / summe
            drawRoundRect(farbe, Offset(x, 0f), Size(breite, size.height), CornerRadius(3.dp.toPx()))
            x += breite + luecke
        }
    }
}

/**
 * Strichzeichen der Gesundheitsseite, aus den Pfaden des Entwurfs. Eigene
 * statt Materials: Materials Zeichen sind gefuellt oder anders gewichtet
 * und passten nicht zu den duennen Strichen der Vorlage.
 */
internal object Zeichen {
    private fun kreis(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    private fun bild(breite: Float = 2f, drehung: Float = 0f, vararg pfade: String): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addGroup(rotate = drehung, pivotX = 12f, pivotY = 12f)
            pfade.forEach {
                addPath(
                    PathParser().parsePathString(it).toNodes(), fill = null, stroke = SolidColor(Color.Black),
                    strokeLineWidth = breite, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
            clearGroup()
        }.build()

    val aktiv = bild(2f, 0f, kreis(12f, 13f, 7f), "M12 9v4l2.5 2 M10 3h4")
    val herz = bild(2f, 0f, "M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z")
    val druck = bild(2f, 0f, "M4 17a8 8 0 0 1 16 0", "M12 17l3.5-5")
    val wasser = bild(2f, 0f, "M12 3.5s-6 6.6-6 10.5a6 6 0 0 0 12 0c0-3.9-6-10.5-6-10.5z")
    val kapsel = bild(2f, -45f, "M7 8.5h10a3.5 3.5 0 0 1 0 7H7a3.5 3.5 0 0 1 0-7z", "M12 8.5v7")
    val tasse = bild(2f, 0f, "M5 9h11v5a5 5 0 0 1-5 5h-1a5 5 0 0 1-5-5z M16 11h1.5a2.5 2.5 0 0 1 0 5H16 M8.5 3.5v2.5 M12.5 3.5v2.5")
    val trend = bild(2f, 0f, "M4 18l5-6 4 3 7-8 M15 7h5v5")
    val uhr = bild(1.8f, 0f, "M10 6h4a3 3 0 0 1 3 3v6a3 3 0 0 1-3 3h-4a3 3 0 0 1-3-3V9a3 3 0 0 1 3-3z", "M9 6l.5-3h5l.5 3 M9 18l.5 3h5l.5-3")
    val mond = bild(1.8f, 0f, "M19 14.5A7.5 7.5 0 0 1 9.5 5a7.5 7.5 0 1 0 9.5 9.5z")
    val info = bild(1.8f, 0f, kreis(12f, 12f, 9f), "M12 11v5 M12 7.5v.5")
    val weiter = bild(2f, 0f, "M9 6l6 6-6 6")
    val plus = bild(2f, 0f, "M12 5v14 M5 12h14")
    val hoch = bild(2.6f, 0f, "M12 19V5M6 11l6-6 6 6")
    val haken = bild(2f, 0f, "M5 12.5l4.5 4.5L19 7.5")
    val zu = bild(2f, 0f, "M7 7l10 10M17 7L7 17")
    val ort = bild(2f, 0f, "M12 21s-6.5-6.2-6.5-11.2a6.5 6.5 0 0 1 13 0C18.5 14.8 12 21 12 21z", kreis(12f, 9.8f, 2.3f))
}
