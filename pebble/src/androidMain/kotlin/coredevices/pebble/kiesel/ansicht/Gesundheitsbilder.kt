package coredevices.pebble.kiesel.ansicht

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
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.Kurvenzug
import coredevices.pebble.kiesel.Nacht
import coredevices.pebble.kiesel.Pulsbin
import coredevices.pebble.kiesel.Statistik
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/*
 * Die Bilder der Gesundheitsseiten (Zeitstrahl, Herz, Nacht). Fuer alle gilt
 * dieselbe Sprache: die Zahlen der Achse RECHTS ([achsSpalte]), das Raster
 * durchgezogen und blass ([Farben.raster]), gestrichelt nur ein Schnitt,
 * gepunktet nur Zonengrenzen. Der Fuss ist aus der Schrift gemessen
 * ([fussraum], [hoeheMitSchrift]), und jedes Bild sagt TalkBack in einem
 * Satz, was es zeigt ([bildBeschreibung]).
 */

/** Die Schrift der Achsen und Stunden aller Bilder: labelSmall in Grau - auch die Zahlen am Tagesband. */
@Composable
internal fun achsStil(): TextStyle = MaterialTheme.typography.labelSmall.copy(color = Farben.neutral())

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
    // Vor dem Bild gerechnet, einmal je Punkte: im Zeichnen lief trend() bei
    // jedem Neuzeichnen (Groesse, Thema, Rueckkehr) ueber die ganze Nacht.
    val linien = remember(punkte, trendFenster) { Gesundheitsrechnung.trend(punkte, trendFenster) }
    val tief = remember(punkte) { punkte.minOf { it.second } - 2 }
    val hoch = remember(punkte) { maxOf(punkte.maxOf { it.second } + 2, tief + 1) }
    Canvas(Modifier.fillMaxWidth().height(hoeheMitSchrift(hoehe)).padding(top = 8.dp).bildBeschreibung(beschreibung)) {
        val rechts = size.width - achsSpalte(messer, stil)
        val boden = size.height - fussraum(messer, stil)
        val spanne = (bis - von).coerceAtLeast(1).toFloat()
        fun x(t: Long) = rechts * (t - von) / spanne
        fun y(w: Int) = boden * (1f - (w - tief).toFloat() / (hoch - tief))
        Statistik.rasterNachPlatz(tief, hoch, boden, messer.measure("0", stil).size.height.toFloat()).forEach { w ->
            drawLine(raster, Offset(0f, y(w)), Offset(rechts, y(w)), 1.dp.toPx())
            val m = messer.measure(Formate.zahl(w.toLong()), stil)
            drawText(m, topLeft = Offset(size.width - m.size.width, y(w) - m.size.height / 2f))
        }
        val r = if (punkte.size > 300) 1.5.dp.toPx() else 2.5.dp.toPx()
        punkte.forEach { (t, w) ->
            drawCircle(blass, r, Offset(x(t), y(w)))
        }
        fun yd(w: Double) = boden * (1f - ((w - tief) / (hoch - tief)).toFloat())
        linien.forEach { stueck ->
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
 * rechts. Die Linie geht ungeglaettet durch jeden Fuenf-Minuten-Median: so erreicht
 * ein kurzer Anstieg seine Zone, und gerade
 * der soll zu sehen sein. Luecken ueber einer Viertelstunde brechen die Linie
 * - dort war die Uhr nicht am Arm.
 *
 * [bins]: die [Gesundheitsrechnung.fuenfMinuten] des Pulses - die Herzseite
 * bildet sie einmal fuer Satz und Bild. [zonen]: die Grenzen moderat,
 * kraeftig, Spitze; [farben]: leicht, moderat, kraeftig, Spitze.
 */
@Composable
internal fun Pulsband(
    bins: List<Pulsbin>,
    von: Long,
    bis: Long,
    zonen: List<Int>,
    farben: List<Color>,
    nacht: Pair<Long, Long>? = null,
    hoehe: Dp = 200.dp,
    stundeText: (ZonedDateTime) -> String = stundenText(),
    beschreibung: String? = null,
) {
    if (bins.isEmpty() || zonen.size != 3 || farben.size != 4) return
    // Saeulen und Stuecke der Linie einmal je Bins, nicht in jedem Zeichnen:
    // Sekunde der Mitte, hoechster und tiefster Wert je halbe Stunde.
    val saeulen = remember(bins) {
        bins.groupBy { it.sekunde / 1800 }.values.map { l -> Triple(l.first().sekunde / 1800 * 1800 + 900, l.maxOf { it.hoechster }, l.minOf { it.tiefster }) }
    }
    // Ein einzelner Bin bleibt ein Punkt, laengere Stuecke eine Linie durch alle ihre Mediane.
    val stuecke = remember(bins) { Gesundheitsrechnung.pulslinie(bins) }
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
        saeulen.forEach { (sekunde, hoechster, tiefster) ->
            val mitte = x(sekunde)
            val oben = y(hoechster)
            val unten = y(tiefster)
            val breite = 5.dp.toPx()
            drawRoundRect(saeule, Offset(mitte - breite / 2, oben), Size(breite, (unten - oben).coerceAtLeast(breite)), CornerRadius(breite / 2))
        }

        // Jede Zone zeichnet dieselbe Linie, beschnitten auf ihren Streifen:
        // so wechselt die Farbe genau an der Grenze, mitten im Strich.
        val streifen = listOf(boden + 20f) + zonen.map { y(it) } + listOf(-20f)
        val dicke = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        stuecke.forEach { st ->
            if (st.size == 1) {
                val (sekunde, median) = st[0]
                drawCircle(farben[Gesundheitsrechnung.pulsZone(median, zonen)], 2.5.dp.toPx(), Offset(x(sekunde), y(median)))
                return@forEach
            }
            val linie = Path().apply {
                moveTo(x(st[0].first), y(st[0].second))
                Kurvenzug.durch(st.map { x(it.first) }, st.map { y(it.second) }).forEach { k -> cubicTo(k.c1x, k.c1y, k.c2x, k.c2y, k.x1, k.y1) }
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
 * Eine Woche am persoenlichen Band wie bei Fitbit und Garmin: das Band als
 * EINE durchgehende Flaeche durch die Tagesmitten - an jeder Mitte so breit,
 * wie es an dem Tag galt -, darueber die Tage als Punkte mit einer neutralen
 * Linie dazwischen, wie im Entwurf "Erlebnis-Herz". Die Punkte gruen im
 * Band, in [ausserhalb] daneben; solange das Band noch nicht urteilt
 * ([Gesundheitsrechnung.Bandtag.urteilt]) oder fehlt, neutral - gruen hiesse
 * "in range", und darueber ist dann nichts gesagt. Das Band selbst steht
 * gleich da, ob es urteilt oder nicht.
 *
 * [reihen]: je sieben Tage mit eigenem Band - eine fuer die HRV, zwei fuer
 * den Blutdruck (oben und unten). DIESELBE ZEICHNUNG FUER BEIDE, damit
 * dieselbe Farbe auf beiden Karten dasselbe heisst. [rasterSchritt]: der
 * kleinste Abstand der Rasterlinien - die HRV will Fuenfer, der Blutdruck
 * ueber 60 mmHg Spanne Zehner oder mehr. [tagesstrich]: die ersten beiden
 * Reihen gehoeren zu einer Messung (Blutdruck oben und unten) - ein
 * senkrechter Strich verbindet sie je Tag, wie bei Garmin.
 */
@Composable
internal fun Bandwoche(
    reihen: List<List<Gesundheitsrechnung.Bandtag>>,
    farbe: Color,
    ausserhalb: Color,
    hoehe: Dp = 170.dp,
    rasterSchritt: Int = 5,
    tagesstrich: Boolean = false,
    beschreibung: String? = null,
    tagText: (LocalDate) -> String,
) {
    val tage = reihen.firstOrNull() ?: return
    val werte = reihen.flatten().mapNotNull { it.wert }
    if (werte.isEmpty()) return
    val messer = rememberTextMeasurer()
    val stil = achsStil()
    // Das Band darf durchscheinen (Farben.BAND_DECKUNG): es liegt hinter Punkten und Linie.
    val flaeche = farbe.copy(alpha = Farben.BAND_DECKUNG)
    val raster = Farben.raster()
    val ohneUrteil = Farben.neutral()
    // Die Linie und der Tagesstrich gedaempft und deckend wie im Entwurf (Schrift zu 50 %):
    // sie tragen kein Urteil, das tun allein die Punkte. Deckend, damit sich Strich und
    // Linie, wo sie sich kreuzen, nicht zu einem dunkleren Fleck addieren.
    val linie = ohneUrteil.aufKarte(0.6f)
    // Der Rand um jeden Punkt in der Farbe der Karte: so steht er frei vor Band und Linie.
    val kartenrand = Farben.karte()
    Canvas(Modifier.fillMaxWidth().height(hoeheMitSchrift(hoehe)).padding(top = 8.dp).bildBeschreibung(beschreibung)) {
        val rechts = size.width - achsSpalte(messer, stil)
        val kopf = 6.dp.toPx()
        val boden = size.height - fussraum(messer, stil, luft = 2.dp)
        val alle = werte + reihen.flatten().mapNotNull { it.band }.flatMap { listOf(it.first, it.second) }
        val tief = ((alle.min() - 6).coerceAtLeast(0) / 5) * 5
        val hoch = maxOf(alle.max() + 6, tief + 20)
        val spalte = rechts / tage.size
        fun x(i: Int) = spalte * (i + 0.5f)
        fun y(w: Int) = kopf + (boden - kopf) * (1f - (w - tief).toFloat() / (hoch - tief))

        // Durchgezogen und blass: gestrichelt hiesse bei Boulder "Schnitt".
        Statistik.rasterInnen(tief, hoch, rasterSchritt).takeLast(4).forEach { w ->
            drawLine(raster, Offset(0f, y(w)), Offset(rechts, y(w)), 1.dp.toPx())
            val m = messer.measure(Formate.zahl(w.toLong()), stil)
            drawText(m, topLeft = Offset(size.width - m.size.width, y(w) - m.size.height / 2f))
        }
        // Erst alle Baender, dann Striche, Linien und Punkte: beim Blutdruck laege sonst
        // das obere Band ueber den Punkten des unteren, wo sie sich naehern.
        reihen.forEach { reihe ->
            // Jede Folge von Tagen mit Band ist EIN Pfad: oben durch die Mitten hin,
            // unten zurueck. Einzelne Rechtecke je Tag gaben eine Treppe und, wo sie
            // sich ueberlappten, dunklere Naehte in der durchscheinenden Flaeche.
            Gesundheitsrechnung.bandstuecke(reihe).forEach { stueck ->
                if (stueck.size == 1) {
                    // Ein Tag allein haette durch seine Mitte keine Breite: dann ein Stueck
                    // von einer halben Spalte um sie herum, sonst fehlte sein Band ganz.
                    val (i, b) = stueck.single()
                    drawRect(flaeche, Offset(x(i) - spalte / 4, y(b.second)), Size(spalte / 2, y(b.first) - y(b.second)))
                    return@forEach
                }
                val pfad = Path().apply {
                    moveTo(x(stueck[0].first), y(stueck[0].second.second))
                    stueck.drop(1).forEach { (i, b) -> lineTo(x(i), y(b.second)) }
                    stueck.asReversed().forEach { (i, b) -> lineTo(x(i), y(b.first)) }
                    close()
                }
                drawPath(pfad, flaeche)
            }
        }
        // Blutdruck: oben und unten eines Tages sind eine Messung - der Strich zeigt ihre
        // Spanne, wie bei Garmin. Neutral und hinter den Punkten: er urteilt nicht.
        if (tagesstrich && reihen.size >= 2) {
            reihen[0].zip(reihen[1]).forEachIndexed { i, (oben, unten) ->
                val o = oben.wert ?: return@forEachIndexed
                val u = unten.wert ?: return@forEachIndexed
                drawLine(linie, Offset(x(i), y(o)), Offset(x(i), y(u)), 4.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        reihen.forEach { reihe ->
            // Von Tag zu Tag innerhalb der Reihe (oben mit oben, unten mit unten), ueber Tage ohne Wert hinweg.
            val punkte = reihe.mapIndexedNotNull { i, t -> t.wert?.let { Offset(x(i), y(it)) } }
            for (i in 1 until punkte.size) drawLine(linie, punkte[i - 1], punkte[i], 2.dp.toPx(), cap = StrokeCap.Round)
        }
        reihen.forEach { reihe ->
            reihe.forEachIndexed { i, t ->
                val w = t.wert ?: return@forEachIndexed
                val f = when (t.imBand) {
                    true -> farbe
                    false -> ausserhalb
                    null -> ohneUrteil
                }
                val p = Offset(x(i), y(w))
                // Der juengste Tag mit einem Ring darum, wie der Auswahlpunkt der Vorlage;
                // sein Kartenrand reicht bis unter den Ring, damit nichts zwischen beiden durchscheint.
                val juengster = i == reihe.lastIndex
                drawCircle(kartenrand, (if (juengster) 11 else 8).dp.toPx(), p)
                if (juengster) drawCircle(f, 9.5.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                drawCircle(f, 6.dp.toPx(), p)
            }
        }
        tage.forEachIndexed { i, t ->
            val m = messer.measure(tagText(t.tag), stil)
            drawText(m, topLeft = Offset(x(i) - m.size.width / 2f, size.height - m.size.height))
        }
    }
}

/**
 * Durch die Punkte weich weiterzeichnen: Kurven von Mitte zu Mitte mit den
 * Punkten als Stuetzen. Der Pfad steht schon auf dem ersten Punkt.
 * Schneidet Gipfel ab: nur fuer Linien ohne Zahl, die sie erreichen muessten.
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
