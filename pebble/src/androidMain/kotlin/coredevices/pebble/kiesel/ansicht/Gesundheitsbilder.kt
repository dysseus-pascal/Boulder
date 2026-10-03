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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Blutdruck
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.Nacht
import coredevices.pebble.kiesel.Pulsbin
import coredevices.pebble.kiesel.Statistik
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/*
 * Die Bilder der Gesundheitsseiten (Zeitstrahl, Herz, Nacht). Fuer alle gilt
 * dieselbe Sprache: die Zahlen der Achse RECHTS ([achsSpalte]), das Raster
 * durchgezogen und blass ([Farben.raster]), gestrichelt nur ein Schnitt,
 * gepunktet nur Zonengrenzen. Der Fuss ist aus der Schrift gemessen
 * ([fussraum], [hoeheMitSchrift]), und jedes Bild sagt TalkBack in einem
 * Satz, was es zeigt ([bildBeschreibung]).
 */

/** Die Schrift der Achsen und Stunden aller Bilder: labelSmall in Grau. */
@Composable
private fun achsStil(): TextStyle = MaterialTheme.typography.labelSmall.copy(color = Farben.neutral())

/**
 * Die Nacht in vier Bahnen - wach oben, tief unten - wie das Hypnogramm in
 * Kiesel-Helper. Schlaf ohne Phase (eine Nacht ohne Puls) liegt in der Bahn
 * des leichten Schlafs. [mitAchse]: rechts so viel frei wie die Kurven
 * darunter fuer ihre Zahlen brauchen, damit dieselbe Uhrzeit in allen
 * Bildern einer Karte senkrecht uebereinander steht. [stumm]: ohne Satz
 * fuer TalkBack, wo Text daneben dieselben Zahlen nennt.
 */
@Composable
internal fun Hypnogramm(nacht: Nacht, hoehe: Dp = 90.dp, mitAchse: Boolean = false, stumm: Boolean = false) {
    val messer = rememberTextMeasurer()
    val stil = achsStil()
    val stunde = stundenText()
    // Die Bahnen aus derselben Liste wie die Legenden, Schlaf ohne Phase zu "leicht".
    val bahnen = PHASEN_VON_OBEN.mapIndexed { i, (p, _) -> p to i }.toMap().let { it + (Farben.OHNE_PHASE to it.getValue(Farben.LEICHT)) }
    val satz = if (stumm) null else hypnogrammSatz(nacht)
    Canvas(Modifier.fillMaxWidth().height(hoeheMitSchrift(hoehe)).padding(top = 8.dp).bildBeschreibung(satz)) {
        val bahn = (size.height - fussraum(messer, stil)) / 4
        val breite = size.width - if (mitAchse) achsSpalte(messer, stil) else 0f
        val von = nacht.abschnitte.minOfOrNull { it.von.epochSecond } ?: return@Canvas
        val bis = nacht.abschnitte.maxOf { it.bis.epochSecond }
        val spanne = (bis - von).coerceAtLeast(1).toFloat()
        fun x(t: Instant) = breite * (t.epochSecond - von) / spanne
        nacht.abschnitte.forEach { a ->
            val b = bahnen[a.phase] ?: return@forEach
            drawRoundRect(
                Farben.phasen[a.phase], Offset(x(a.von), bahn * b + 2), Size((x(a.bis) - x(a.von)).coerceAtLeast(1f), bahn - 4),
                CornerRadius(2.dp.toPx()),
            )
        }
        stundenachse(von, bis, stunde, messer, stil, breite) { x(Instant.ofEpochSecond(it)) }
    }
}

/**
 * Die Phasen in der Reihenfolge der Bahnen des [Hypnogramm]s, von oben nach
 * unten. Legenden und Phasenzeilen folgen ihr: standen sie anders als die
 * Bahnen, musste man zwischen Punkt und Block hin- und herspringen.
 */
internal val PHASEN_VON_OBEN = listOf(
    Farben.WACH to R.string.ks_h_wach, Farben.REM to R.string.ks_h_rem,
    Farben.LEICHT to R.string.ks_h_leicht, Farben.TIEF to R.string.ks_h_tief,
)

/** Fuer TalkBack: die Zeit der Nacht und die Phasen mit ihrer Dauer - was das Bild zeigt, in Zahlen. */
@Composable
private fun hypnogrammSatz(nacht: Nacht): String {
    val context = LocalContext.current
    val zeit = stringResource(R.string.ks_h_zeitraum, Formate.uhrzeit(context, nacht.einschlafen), Formate.uhrzeit(context, nacht.aufwachen))
    if (!nacht.mitPhasen) return zeit
    val namen = PHASEN_VON_OBEN.map { (p, name) -> p to stringResource(name) }
    return zeit + ": " + namen.joinToString(", ") { (p, name) -> name + " " + Formate.dauer(nacht.phasen[p].toLong()) }
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
 * Runde Rasterwerte strikt zwischen [tief] und [hoch]: der kleinste Schritt
 * aus 1, 2, 5, 10 ..., bei dem zwei Zahlen der Achse mindestens gut eine
 * Zeile ([zeile], Pixel) auseinander stehen und hoechstens vier Linien
 * entstehen. [platz]: die Hoehe der Achse in Pixeln.
 */
private fun achsenlinien(tief: Int, hoch: Int, platz: Float, zeile: Float): List<Int> {
    val spanne = (hoch - tief).coerceAtLeast(1)
    for (schritt in listOf(1, 2, 5, 10, 20, 50, 100)) {
        if (platz * schritt / spanne < zeile * 1.2f) continue
        val linien = generateSequence(Math.floorDiv(tief, schritt) * schritt + schritt) { it + schritt }.takeWhile { it < hoch }.toList()
        if (linien.size <= 4) return linien
    }
    return emptyList()
}

/**
 * Werte ueber eine Zeitspanne als Punkte, mit den Stunden darunter - Puls
 * und SpO2 der Nachtansicht. Die Punkte blass, darueber die Trendlinie
 * ([Gesundheitsrechnung.trend] mit [trendFenster] Sekunden nach jeder Seite):
 * die Punkte zeigen, was gemessen wurde, die Linie, wohin es ging. Die Achse
 * spannt sich um die Werte, beschriftet mit runden Zahlen wie jedes Raster.
 */
@Composable
internal fun Tageskurve(
    punkte: List<Pair<Long, Int>>,
    von: Long,
    bis: Long,
    farbe: Color,
    hoehe: Dp = 120.dp,
    trendFenster: Long = 900,
    beschreibung: String? = null,
) {
    if (punkte.isEmpty()) return
    val messer = rememberTextMeasurer()
    val stil = achsStil()
    val raster = Farben.raster()
    // Deckend gemischt statt Alpha: 300 Punkte uebereinander ergaben sonst dunkle Flecken.
    val blass = farbe.aufKarte(0.4f)
    val stunde = stundenText()
    Canvas(Modifier.fillMaxWidth().height(hoeheMitSchrift(hoehe)).padding(top = 8.dp).bildBeschreibung(beschreibung)) {
        val rechts = size.width - achsSpalte(messer, stil)
        val boden = size.height - fussraum(messer, stil)
        val tief = punkte.minOf { it.second } - 2
        val hoch = maxOf(punkte.maxOf { it.second } + 2, tief + 1)
        val spanne = (bis - von).coerceAtLeast(1).toFloat()
        fun x(t: Long) = rechts * (t - von) / spanne
        fun y(w: Int) = boden * (1f - (w - tief).toFloat() / (hoch - tief))
        achsenlinien(tief, hoch, boden, messer.measure("0", stil).size.height.toFloat()).forEach { w ->
            drawLine(raster, Offset(0f, y(w)), Offset(rechts, y(w)), 1.dp.toPx())
            val m = messer.measure(Formate.zahl(w.toLong()), stil)
            drawText(m, topLeft = Offset(size.width - m.size.width, y(w) - m.size.height / 2f))
        }
        val r = if (punkte.size > 300) 1.5.dp.toPx() else 2.5.dp.toPx()
        punkte.forEach { (t, w) ->
            drawCircle(blass, r, Offset(x(t), y(w)))
        }
        fun yd(w: Double) = boden * (1f - ((w - tief) / (hoch - tief)).toFloat())
        Gesundheitsrechnung.trend(punkte, trendFenster).forEach { stueck ->
            val p = stueck.map { (t, w) -> Offset(x(t), yd(w)) }
            if (p.size == 1) drawCircle(farbe, 2.dp.toPx(), p[0])
            else drawPath(
                Path().apply { moveTo(p.first().x, p.first().y); weich(p) }, farbe,
                style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        stundenachse(von, bis, stunde, messer, stil, rechts, ::x)
    }
}

/**
 * Der Puls der letzten 24 Stunden wie bei Google Health und Garmin: graue
 * Saeulen fuer die Spanne jeder halben Stunde, darueber die Linie in der
 * Farbe ihrer Zone, die Zonengrenzen als gepunktete Linien mit ihrer Zahl
 * rechts. Die Linie ist nur leicht geglaettet (eine Viertelstunde): staerker
 * gemittelt erreichte ein kurzer Anstieg seine Zone nicht mehr, und gerade
 * der soll zu sehen sein. Luecken ueber einer Viertelstunde brechen die Linie
 * - dort war die Uhr nicht am Arm.
 *
 * [zonen]: die Grenzen moderat, kraeftig, Spitze; [farben]: leicht, moderat,
 * kraeftig, Spitze.
 */
@Composable
internal fun Pulsband(
    punkte: List<Pair<Long, Int>>,
    von: Long,
    bis: Long,
    zonen: List<Int>,
    farben: List<Color>,
    nacht: Pair<Long, Long>? = null,
    hoehe: Dp = 200.dp,
    stundeText: (ZonedDateTime) -> String = stundenText(),
    beschreibung: String? = null,
) {
    val bins = Gesundheitsrechnung.fuenfMinuten(punkte)
    if (bins.isEmpty() || zonen.size != 3 || farben.size != 4) return
    val messer = rememberTextMeasurer()
    val stil = achsStil()
    // Durchscheinend, nicht deckend (aufKarte): die Saeulen liegen ueber den
    // gepunkteten Zonengrenzen und dem Nachtschatten, beide sollen durchscheinen.
    val saeule = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    // Die Nacht als deutliche Flaeche: bei 6 % sah man sie auf dem Telefon nicht.
    val schatten = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val nachtText = stringResource(R.string.ks_h_nacht_kurz)
    Canvas(Modifier.fillMaxWidth().height(hoeheMitSchrift(hoehe)).padding(top = 8.dp).bildBeschreibung(beschreibung)) {
        // Die Zahlen rechts, wie bei Google: links beginnt die Kurve am Rand.
        val rechts = size.width - achsSpalte(messer, stil)
        val kopf = 6.dp.toPx()
        val boden = size.height - fussraum(messer, stil, luft = 2.dp)
        // Unten auf einer Zehnerlinie, hoechstens 40; oben ueber der Spitzengrenze,
        // damit alle drei Grenzen im Bild stehen - auch an einem ruhigen Tag.
        val tief = (minOf(bins.minOf { it.tiefster } - 5, 40) / 10) * 10
        val hoch = maxOf(zonen[2] + 8, bins.maxOf { it.hoechster } + 4)
        val spanne = (bis - von).coerceAtLeast(1).toFloat()
        fun x(t: Long) = rechts * (t - von) / spanne
        fun y(w: Int) = kopf + (boden - kopf) * (1f - (w - tief).toFloat() / (hoch - tief))
        nachtschatten(nacht, von, bis, ::x, boden, schatten, nachtText, messer, stil)

        // Gepunktet heisst bei Boulder Zonengrenze: runde Punkte statt Striche, wie die Vorlage.
        val punktiert = PathEffect.dashPathEffect(floatArrayOf(0.1f, 9.dp.toPx()))
        fun grenze(w: Int, farbe: Color) {
            drawLine(farbe, Offset(0f, y(w)), Offset(rechts, y(w)), 2.dp.toPx(), cap = StrokeCap.Round, pathEffect = punktiert)
            val m = messer.measure(Formate.zahl(w.toLong()), stil)
            drawText(m, topLeft = Offset(size.width - m.size.width, y(w) - m.size.height / 2f))
        }
        grenze(tief, farben[0])
        zonen.forEachIndexed { i, w -> grenze(w, farben[i + 1]) }

        // Die Spanne je halbe Stunde als Saeule hinter der Linie.
        bins.groupBy { it.sekunde / 1800 }.forEach { (_, l) ->
            val mitte = x(l.first().sekunde / 1800 * 1800 + 900)
            val oben = y(l.maxOf { it.hoechster })
            val unten = y(l.minOf { it.tiefster })
            val breite = 5.dp.toPx()
            drawRoundRect(saeule, Offset(mitte - breite / 2, oben), Size(breite, (unten - oben).coerceAtLeast(breite)), CornerRadius(breite / 2))
        }

        // Jede Zone zeichnet dieselbe Linie, beschnitten auf ihren Streifen:
        // so wechselt die Farbe genau an der Grenze, mitten im Strich.
        val streifen = listOf(boden + 20f) + zonen.map { y(it) } + listOf(-20f)
        val dicke = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        Gesundheitsrechnung.stuecke(bins).forEach { roh ->
            if (roh.size == 1) {
                val z = Gesundheitsrechnung.pulsZone(roh[0].median, zonen)
                drawCircle(farben[z], 2.5.dp.toPx(), Offset(x(roh[0].sekunde), y(roh[0].median)))
                return@forEach
            }
            val st = glaetten(roh, 1)
            val p = st.map { Offset(x(it.sekunde), y(it.median)) }
            val linie = Path().apply {
                moveTo(p.first().x, p.first().y)
                weich(p)
            }
            for (z in 0..3) {
                clipRect(left = -10f, top = streifen[z + 1], right = size.width, bottom = streifen[z]) {
                    drawPath(linie, farben[z], style = dicke)
                }
            }
        }
        val letzter = bins.last()
        drawCircle(farben[Gesundheitsrechnung.pulsZone(letzter.median, zonen)], 3.5.dp.toPx(), Offset(x(letzter.sekunde), y(letzter.median)))

        stundenachse(von, bis, stundeText, messer, stil, rechts, ::x)
    }
}

/**
 * Die HRV der letzten sieben Naechte wie bei Google Health und Garmin: das
 * persoenliche Band als Flaeche, Tag fuer Tag so breit, wie es an dem Tag
 * galt, darueber die Naechte als verbundene Punkte - gruen im Band, in
 * [ausserhalb] daneben. Eine Nacht ohne Band (zu wenig fruehere Naechte)
 * steht neutral da, und die Linie zu ihr auch: gruen hiesse "in range", und
 * ohne Band ist ueber sie nichts gesagt.
 */
@Composable
internal fun HrvWoche(
    tage: List<Gesundheitsrechnung.HrvTag>,
    farbe: Color,
    ausserhalb: Color,
    hoehe: Dp = 170.dp,
    beschreibung: String? = null,
    tagText: (LocalDate) -> String,
) {
    val werte = tage.mapNotNull { it.wert }
    if (werte.isEmpty()) return
    val messer = rememberTextMeasurer()
    val stil = achsStil()
    // Das Band darf durchscheinen (Farben.band): es liegt hinter Punkten und Linie.
    val flaeche = farbe.copy(alpha = Farben.BAND_DECKUNG)
    val raster = Farben.raster()
    val ohneUrteil = Farben.neutral()
    Canvas(Modifier.fillMaxWidth().height(hoeheMitSchrift(hoehe)).padding(top = 8.dp).bildBeschreibung(beschreibung)) {
        val rechts = size.width - achsSpalte(messer, stil)
        val kopf = 6.dp.toPx()
        val boden = size.height - fussraum(messer, stil, luft = 2.dp)
        val alle = werte + tage.mapNotNull { it.band }.flatMap { listOf(it.first, it.second) }
        val tief = ((alle.min() - 6).coerceAtLeast(0) / 5) * 5
        val hoch = maxOf(alle.max() + 6, tief + 20)
        val spalte = rechts / tage.size
        fun x(i: Int) = spalte * (i + 0.5f)
        fun y(w: Int) = kopf + (boden - kopf) * (1f - (w - tief).toFloat() / (hoch - tief))

        // Durchgezogen und blass: gestrichelt hiesse bei Boulder "Schnitt".
        Statistik.rasterInnen(tief, hoch, 5).takeLast(4).forEach { w ->
            drawLine(raster, Offset(0f, y(w)), Offset(rechts, y(w)), 1.dp.toPx())
            val m = messer.measure(Formate.zahl(w.toLong()), stil)
            drawText(m, topLeft = Offset(size.width - m.size.width, y(w) - m.size.height / 2f))
        }
        // Das Band als Treppe: jede Spalte so hoch wie das Band ihres Tages,
        // die erste und letzte bis an den Rand.
        tage.forEachIndexed { i, t ->
            val (u, o) = t.band ?: return@forEachIndexed
            val links = if (i == 0) 0f else spalte * i
            val breite = if (i == tage.lastIndex) rechts - links else spalte * (i + 1) - links
            drawRect(flaeche, Offset(links, y(o)), Size(breite + 0.5f, y(u) - y(o)))
        }
        val punkte = tage.mapIndexedNotNull { i, t -> t.wert?.let { Offset(x(i), y(it)) to t.imBand } }
        for (i in 1 until punkte.size) {
            val (von, vonImBand) = punkte[i - 1]
            val (bis, bisImBand) = punkte[i]
            drawLine(if (vonImBand == null || bisImBand == null) ohneUrteil else farbe, von, bis, 2.dp.toPx(), cap = StrokeCap.Round)
        }
        tage.forEachIndexed { i, t ->
            val w = t.wert ?: return@forEachIndexed
            val f = when (t.imBand) {
                true -> farbe
                false -> ausserhalb
                null -> ohneUrteil
            }
            val p = Offset(x(i), y(w))
            val letzte = i == tage.lastIndex
            drawCircle(f, 4.dp.toPx(), p)
            // Die letzte Nacht mit einem Ring darum, wie der Auswahlpunkt der Vorlage.
            if (letzte) drawCircle(f, 7.5.dp.toPx(), p, style = Stroke(2.dp.toPx()))
        }
        tage.forEachIndexed { i, t ->
            val m = messer.measure(tagText(t.tag), stil)
            drawText(m, topLeft = Offset(x(i) - m.size.width / 2f, size.height - m.size.height))
        }
    }
}

/**
 * Die Blutdrucksitzungen zwischen [von] und [bis]: je Sitzung ein Strich von
 * diastolisch bis systolisch mit einem Punkt an jedem Ende - beide Werte
 * und ihr Abstand auf einen Blick. Die Zeit laeuft durch, nicht in
 * Tagesfaechern: morgens und abends gemessen stuenden sonst zwei Striche
 * aufeinander. Die letzte Sitzung kraeftig, die frueheren blasser; die
 * Zahlen rechts wie bei [HrvWoche], unter jedem Montag sein Datum.
 */
@Composable
internal fun Blutdruckbild(
    sitzungen: List<Blutdruck>,
    von: Instant,
    bis: Instant,
    farbe: Color,
    hoehe: Dp = 150.dp,
    beschreibung: String? = null,
    tagText: (LocalDate) -> String,
) {
    val (tief, hoch) = Gesundheitsrechnung.blutdruckachse(sitzungen) ?: return
    val messer = rememberTextMeasurer()
    val stil = achsStil()
    val raster = Farben.raster()
    val zone = ZoneId.systemDefault()
    // Blass als deckende Mischung mit der Karte: mit Alpha laege der Strich im Punkt doppelt und dunkelte ihn fleckig.
    val blass = farbe.aufKarte(0.5f)
    Canvas(Modifier.fillMaxWidth().height(hoeheMitSchrift(hoehe)).padding(top = 8.dp).bildBeschreibung(beschreibung)) {
        val rechts = size.width - achsSpalte(messer, stil)
        val kopf = 6.dp.toPx()
        val boden = size.height - fussraum(messer, stil, luft = 2.dp)
        // Ein Punkt Abstand zu beiden Seiten: eine Sitzung von eben laege sonst halb unter den Zahlen.
        val rand = 6.dp.toPx()
        val spanne = (bis.epochSecond - von.epochSecond).coerceAtLeast(1)
        fun x(t: Instant) = rand + (rechts - 2 * rand) * (t.epochSecond - von.epochSecond).coerceIn(0, spanne) / spanne.toFloat()
        fun y(w: Int) = kopf + (boden - kopf) * (1f - (w - tief).toFloat() / (hoch - tief))

        Statistik.rasterInnen(tief, hoch, 10).forEach { w ->
            drawLine(raster, Offset(0f, y(w)), Offset(rechts, y(w)), 1.dp.toPx())
            val m = messer.measure(Formate.zahl(w.toLong()), stil)
            drawText(m, topLeft = Offset(size.width - m.size.width, y(w) - m.size.height / 2f))
        }
        sitzungen.forEachIndexed { i, s ->
            val f = if (i == sitzungen.lastIndex) farbe else blass
            val oben = Offset(x(s.zeit), y(s.oben))
            val unten = Offset(x(s.zeit), y(s.unten))
            drawLine(f, oben, unten, 2.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(f, 3.dp.toPx(), oben)
            drawCircle(f, 3.dp.toPx(), unten)
        }
        // Die Montage als Zeitachse, mittig unter ihrem Tag - wie im Tagesbild der Trends.
        var tag = von.atZone(zone).toLocalDate()
        while (!tag.isAfter(bis.atZone(zone).toLocalDate())) {
            if (tag.dayOfWeek == DayOfWeek.MONDAY) {
                val mitte = x(tag.atTime(12, 0).atZone(zone).toInstant())
                val m = messer.measure(tagText(tag), stil)
                drawText(m, topLeft = Offset((mitte - m.size.width / 2f).coerceIn(0f, maxOf(0f, rechts - m.size.width)), size.height - m.size.height))
            }
            tag = tag.plusDays(1)
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

/**
 * Die vollen Stunden zwischen [von] und [bis] (Sekunden) unten ins Bild,
 * hoechstens fuenf. [text] kommt vom Aufrufer ([stundenText]): eine feste
 * 24-Stunden-Achse neben "11:02 PM" las sich wie zwei Uhren. [x]: die Lage
 * einer Sekunde, [rechts]: bis wohin eine Zahl reichen darf.
 *
 * Laeuft eine Zahl in die naechste, nimmt die Achse den naechsten Schritt:
 * mit 12-h-Uhr ("11 PM", "12 AM") und Schrift 1,3 standen sie bei einer
 * kurzen Nacht sonst ineinander. Ausgelassen wird nie einzeln - die Achse
 * bleibt gleichmaessig.
 */
private fun DrawScope.stundenachse(
    von: Long, bis: Long, text: (ZonedDateTime) -> String, messer: TextMeasurer, stil: TextStyle, rechts: Float, x: (Long) -> Float,
) {
    val stunden = ((bis - von) / 3600).coerceAtLeast(1)
    val erster = when {
        stunden > 16 -> 6
        stunden > 8 -> 3
        stunden > 4 -> 2
        else -> 1
    }
    val luft = 4.dp.toPx()
    for (schritt in listOf(1, 2, 3, 6, 12).filter { it >= erster }) {
        val marken = stundenstriche(von, bis, schritt).map { t ->
            val m = messer.measure(text(t), stil)
            (x(t.toEpochSecond()) - m.size.width / 2f).coerceIn(0f, maxOf(0f, rechts - m.size.width)) to m
        }
        val frei = marken.zipWithNext().all { (a, b) -> a.first + a.second.size.width + luft <= b.first }
        // Zwoelf Stunden ist der groesste Schritt: dort stehen hoechstens drei Zahlen, die zeichnet sie in jedem Fall.
        if (frei || schritt == 12) {
            marken.forEach { (links, m) -> drawText(m, topLeft = Offset(links, size.height - m.size.height)) }
            return
        }
    }
}

/** Die vollen Stunden nach [von] und vor [bis], deren Stunde durch [schritt] teilbar ist. */
private fun stundenstriche(von: Long, bis: Long, schritt: Int): List<ZonedDateTime> {
    var t = Instant.ofEpochSecond(von).atZone(ZoneId.systemDefault()).withMinute(0).withSecond(0).plusHours(1)
    return buildList {
        while (t.toEpochSecond() < bis) {
            if (t.hour % schritt == 0) add(t)
            t = t.plusHours(1)
        }
    }
}
