package coredevices.pebble.kiesel.ansicht

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.Statistik
import coredevices.pebble.kiesel.Trainingsanalyse
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Die Zonen als waagrechte Baender (von, bis, Flaeche) fuer einen Pulsverlauf -
 * in den Farben der Uhr, damit das Band im Bild zur Zonenliste passt.
 */
@Composable
internal fun zonenbaender(maxpuls: Int): List<Triple<Double, Double, Color>> = (1..5).map { z ->
    val von = maxpuls * Trainingsanalyse.ZONEN_PROZENT[z - 1] / 100.0
    val bis = if (z == 5) 250.0 else maxpuls * Trainingsanalyse.ZONEN_PROZENT[z] / 100.0
    Triple(von, bis, Farben.trainingszonen[z].flaeche())
}

/** Die Werteachse eines [Verlauf]s: ihre Grenzen und die runden Striche dazwischen. */
private class Werteachse(val tief: Double, val hoch: Double, val striche: List<Double>)

/**
 * Die Werteachse in RUNDEN SCHRITTEN statt Tiefst-, Mittel- und Hoechstwert:
 * "84 130 176" liess sich nicht ablesen, "100 120 140 160" schon. Reichen
 * die runden Striche ueber die Daten hinaus, sind sie die Grenzen.
 */
private fun werteachse(punkte: List<Trainingsanalyse.Verlaufspunkt>, bereich: ClosedRange<Double>?): Werteachse {
    if (bereich != null) {
        val striche = Statistik.rasterMitRand(bereich.start, bereich.endInclusive).filter { it in bereich }
        return Werteachse(bereich.start, bereich.endInclusive, striche)
    }
    var tief = punkte.minOf { it.wert }
    var hoch = punkte.maxOf { it.wert }
    val luft = ((hoch - tief) * 0.1).coerceAtLeast(1.0)
    // Die Luft unter der Linie geht nicht unter null: ein Tempo von
    // "-4 km/h" gibt es nicht. Die Steigung darf, sie ist auch negativ.
    tief = if (tief >= 0) (tief - luft).coerceAtLeast(0.0) else tief - luft
    hoch += luft
    val striche = Statistik.rasterMitRand(tief, hoch)
    return Werteachse(minOf(tief, striche.min()), maxOf(hoch, striche.max()), striche)
}

/**
 * Eine Linie ueber der Strecke oder der Zeit - wie StreckenverlaufView in
 * Kiesel-Helper.
 *
 * [baender] sind waagrechte Streifen hinter der Linie (beim Puls die Zonen),
 * [xBaender] senkrechte (Saetze, Bahnen). [farbeJeWert] faerbt die Linie
 * Stueck fuer Stueck nach dem Wert - beim Tempo blau bis rot wie auf der
 * Karte. Mit [zeitachse] sind die `meter` der Punkte Sekunden, und die Achse
 * zeigt Minuten; mit [zaehlachse] zaehlt sie nur (Bahn 1, 2, ...), mit
 * [achsenText] dazu beschriftet (beim Akku das Datum jedes Tagesbeginns).
 * Sonst ist sie eine Strecke in km oder, mit [imperial], in mi.
 *
 * DIE WERTE STEHEN RECHTS, wie in allen Bildern des Sports; das Raster ist
 * durchgezogen - gestrichelt heisst in Boulder "Schnitt". [beschreibung] ist
 * der Satz fuer TalkBack, ohne ihn nennt das Bild Spanne und Laenge selbst.
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
    /** Feste Spanne der Werteachse - beim Akku 0 bis 100, sonst stuende dort 108 %. */
    bereich: ClosedRange<Double>? = null,
    achsenText: ((Int) -> String)? = null,
    imperial: Boolean = einheitenImperial(),
    beschreibung: String? = null,
) {
    if (punkte.size < 2) return
    val messer = rememberTextMeasurer()
    val stil = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val raster = Farben.raster()
    val achse = remember(punkte, bereich) { werteachse(punkte, bereich) }
    val anfang = if (zaehlachse) punkte.first().meter else 0.0
    val ende = punkte.last().meter.coerceAtLeast(anfang + 1.0)
    // Die Gesamtlaenge unter dem Ende der Kurve: Minuten oder Strecke, gezaehlt ohne.
    val gesamt = when {
        zaehlachse -> ""
        zeitachse -> Formate.menge(ende / 60, "min").text
        else -> Formate.distanz(ende, imperial).text
    }
    val spanne = stringResource(R.string.ks_v_bild_spanne, Formate.zahl(punkte.minOf { it.wert }, nachkomma), Formate.zahl(punkte.maxOf { it.wert }, nachkomma))
    val satz = beschreibung ?: listOf(Formate.Angabe(spanne, einheit).text, gesamt).filter { it.isNotEmpty() }.joinToString(", ")

    // Die Hoehe waechst mit der Schrift des Fusses, damit die Kurve nicht schrumpft.
    Canvas(Modifier.fillMaxWidth().height(hoeheMitSchrift(hoehe)).padding(top = 8.dp).bildBeschreibung(satz)) {
        // Der Fuss aus der Schrift gemessen: fest 16 dp liefen Kilometer und Minuten ab 1,15 ins Bild.
        val fuss = fussraum(messer, stil)
        val kopf = 8.dp.toPx()
        val boden = size.height - fuss
        val beschriftet = achse.striche.map { messer.measure(Formate.zahl(it, nachkomma), stil) }
        val rechts = size.width - ((beschriftet.maxOfOrNull { it.size.width } ?: 0) + 6.dp.toPx())
        val wspanne = (achse.hoch - achse.tief).coerceAtLeast(0.1)

        fun x(m: Double) = rechts * ((m - anfang) / (ende - anfang)).toFloat()
        fun y(w: Double) = kopf + (boden - kopf) * (1f - ((w - achse.tief) / wspanne).toFloat())

        // Baender hinter allem - erst die senkrechten, dann die waagrechten.
        xBaender.forEach { (von, bis) ->
            drawRect(ton.copy(alpha = 0.22f), Offset(x(von), kopf), Size(x(bis) - x(von), boden - kopf))
        }
        baender.forEach { (von, bis, farbe) ->
            val yo = y(bis.coerceAtMost(achse.hoch)).coerceAtLeast(kopf)
            val yu = y(von.coerceAtLeast(achse.tief)).coerceAtMost(boden)
            if (yu > yo) drawRect(farbe.copy(alpha = 0.26f), Offset(0f, yo), Size(rechts, yu - yo))
        }

        // Das Raster durchgezogen, die Zahlen rechts daneben.
        achse.striche.forEachIndexed { i, w ->
            val yy = y(w)
            drawLine(raster, Offset(0f, yy), Offset(rechts, yy), 1.dp.toPx())
            val m = beschriftet[i]
            drawText(m, topLeft = Offset(size.width - m.size.width, (yy - m.size.height / 2f).coerceIn(0f, size.height - m.size.height)))
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
            drawPath(f, Brush.verticalGradient(listOf(ton.copy(alpha = 0.4f), ton.copy(alpha = 0f)), kopf, boden))
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

        // Die Achse: Kilometer oder Meilen, Minuten - oder nur gezaehlt.
        val (bis, schritt) = when {
            zaehlachse -> ende to (if (ende > 40) 10 else if (ende > 10) 5 else 1)
            zeitachse -> (ende / 60).let { min -> min to (if (min > 120) 30 else if (min > 60) 15 else if (min > 30) 10 else if (min > 10) 5 else 0) }
            else -> Formate.streckeIn(ende, imperial).let { d -> d to (if (d > 40) 10 else if (d > 20) 5 else if (d > 8) 2 else if (d > 3) 1 else 0) }
        }
        val faktor = when {
            zaehlachse -> 1.0
            zeitachse -> 60.0
            imperial -> Formate.METER_JE_MEILE
            else -> 1000.0
        }
        // DER FUSS ENDET MIT DER KURVE, nicht am Rand: rechts daneben steht die
        // Werteachse, und ihre unterste Zahl ("0" beim Stillstand, beim Akku)
        // ragt eine halbe Zeile in den Fuss. Die Gesamtlaenge hat Vorrang - ein
        // Strich, der in sie hineinliefe, faellt weg.
        val gesamtBreite = if (gesamt.isEmpty()) 0 else messer.measure(gesamt, stil).size.width
        val grenze = rechts - gesamtBreite - 4.dp.toPx()
        if (zaehlachse && achsenText != null) {
            // Jeder wievielte ganze Wert ein Datum bekommt, aus der breitesten Beschriftung:
            // feste Schritte liessen "Sep 29" und "Sep 30" ueber zwei Wochen ineinanderlaufen.
            val ganze = (ceil(anfang).toInt()..floor(ende).toInt()).toList()
            val jeEinheit = rechts / (ende - anfang).toFloat()
            val breiteste = ganze.maxOfOrNull { messer.measure(achsenText(it), stil).size.width } ?: 0
            val jeder = listOf(1, 2, 3, 7, 14).firstOrNull { it * jeEinheit >= breiteste + 8.dp.toPx() } ?: 28
            ganze.filter { (it - ganze.first()) % jeder == 0 }.forEach { k ->
                val m = messer.measure(achsenText(k), stil)
                val xk = x(k.toDouble())
                // Am Rand faellt sie weg statt zu ruecken - verschoben stuende sie unter dem falschen Tag.
                if (xk - m.size.width / 2f >= 0f && xk + m.size.width / 2f <= rechts) {
                    drawText(m, topLeft = Offset(xk - m.size.width / 2f, size.height - m.size.height))
                }
            }
        } else if (schritt > 0) {
            var k = if (zaehlachse) schritt.coerceAtLeast(anfang.toInt()) else schritt
            while (k < bis) {
                val m = messer.measure(Formate.zahl(k.toLong()), stil)
                val xk = x(k * faktor)
                if (xk + m.size.width / 2f <= grenze) {
                    drawText(m, topLeft = Offset(xk - m.size.width / 2f, size.height - m.size.height))
                }
                k += schritt
            }
        }
        if (gesamt.isNotEmpty()) {
            val m = messer.measure(gesamt, stil)
            drawText(m, topLeft = Offset(rechts - m.size.width, size.height - m.size.height))
        }
        drawText(messer.measure(einheit, stil), topLeft = Offset(4.dp.toPx(), kopf))
    }
}

/** Die Zonen als ein Balken - die Anteile nebeneinander; die Zahlen dazu stehen in der [Zonenliste]. */
@Composable
internal fun Zonenbalken(sekunden: LongArray) {
    val summe = sekunden.sum().coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(6.dp))) {
        for (z in 0..5) {
            if (sekunden[z] > 0) Box(Modifier.weight(sekunden[z].toFloat() / summe).fillMaxHeight().background(Farben.trainingszonen[z].flaeche()))
        }
    }
}

/** Spaltenbreiten der Zonenliste: Name, Grenzen, Zeit, Anteil. */
private val ZONEN_SPALTEN = listOf<Dp?>(null, 72.dp, 60.dp, 44.dp)

/**
 * Eine Zeile je Zone: Farbe, Name, Grenzen, Zeit, Anteil - Zeit und Anteil
 * aus denselben Sekunden gerundet. Der Punkt verbindet die Zeile mit dem
 * [Zonenbalken]; die Zellen sind die der gemeinsamen [Tabellenzeile].
 */
@Composable
internal fun Zonenliste(sekunden: LongArray, maxpuls: Int) {
    val anteile = Trainingsanalyse.zonenAnteile(sekunden)
    val unterMinute = stringResource(R.string.ks_v_unter_minute)
    val namen = listOf(
        stringResource(R.string.ks_zone_unter), stringResource(R.string.ks_zone_1), stringResource(R.string.ks_zone_2),
        stringResource(R.string.ks_zone_3), stringResource(R.string.ks_zone_4), stringResource(R.string.ks_zone_5),
    )
    val fett = MaterialTheme.colorScheme.onSurface
    val messer = rememberTextMeasurer()
    val stil = LocalTextStyle.current.copy(fontSize = 14.sp)
    val schrift = LocalDensity.current.fontScale.coerceIn(1f, 1.3f)
    BoxWithConstraints {
        // Die freie Namensspalte wie in der Tabellenzeile: Breite minus Punkt, Luft und feste Spalten.
        val frei = maxWidth - 22.dp - ZONEN_SPALTEN.filterNotNull().fold(0.dp) { a, b -> a + b } * schrift
        val lang = (1..5).filter { sekunden[it] > 0 }.map { stringResource(R.string.ks_v_zone_n, it, namen[it]) }
        // PASST "Zone 3 · Endurance" NICHT (grosse Schrift, schmales Telefon), steht der Name in
        // einer zweiten Zeile - sonst brach er hinter dem Punkt um, und der hing am Zeilenende.
        val einzeilig = with(LocalDensity.current) { lang.all { messer.measure(it, stil).size.width <= frei.toPx() } }
        Column {
            for (z in 5 downTo 0) {
                if (sekunden[z] == 0L) continue
                val von = if (z == 0) 0 else maxpuls * Trainingsanalyse.ZONEN_PROZENT[z - 1] / 100
                val bis = when (z) {
                    0 -> maxpuls * 50 / 100 - 1
                    5 -> maxpuls
                    else -> maxpuls * Trainingsanalyse.ZONEN_PROZENT[z] / 100 - 1
                }
                val (minuten, prozent) = anteile[z]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(Farben.trainingszonen[z].flaeche(), CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        Tabellenzeile(
                            listOf(
                                when {
                                    z == 0 -> namen[0]
                                    einzeilig -> stringResource(R.string.ks_v_zone_n, z, namen[z])
                                    else -> stringResource(R.string.ks_v_zone_nr, z) + "\n" + namen[z]
                                },
                                Formate.zahl(von.toLong()) + "–" + Formate.zahl(bis.toLong()),
                                // Unter einer halben Minute stand "0 min" neben einem Anteil.
                                if (minuten == 0L) unterMinute else Formate.dauer(minuten),
                                Formate.prozent(prozent),
                            ),
                            ZONEN_SPALTEN, hervor = 2 to fett,
                        )
                    }
                }
            }
        }
    }
}

/** Die Legende zur Farbkarte: der Verlauf als Flaeche wie auf der Karte, die Werte an den Enden. */
@Composable
internal fun Farblegende(links: String, rechts: String) {
    val dunkel = Farben.dunkel()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            val stufen = 24
            val b = size.width / stufen
            for (k in 0 until stufen) drawRect(Farben.tempo(k / (stufen - 1f)).flaecheIm(dunkel), Offset(k * b, 0f), Size(b + 1, size.height))
        }
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) { Unterzeile(links) }
            Unterzeile(rechts)
        }
    }
}

/** Fuer osmdroid, das mit Android-Farben zeichnet. */
internal fun Color.alsInt(): Int = toArgb()
