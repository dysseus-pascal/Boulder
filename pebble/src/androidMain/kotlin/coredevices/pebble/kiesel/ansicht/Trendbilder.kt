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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Auswertung
import coredevices.pebble.kiesel.Formate
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/*
 * Die Bilder der Trendseiten. Fuer alle gilt dieselbe Sprache wie in den
 * anderen Bildern der App: durchgezogen das Ziel, gestrichelt der Schnitt,
 * das Raster durchgezogen und blass (Farben.raster), die Zahlen einer
 * Pegelachse RECHTS wie auf der Herzseite. Der Fuss und der Kopf fuer die
 * Zahlen sind aus der Schrift gemessen, und jedes Bild sagt TalkBack in einem
 * Satz, was es zeigt.
 */

/**
 * Eine Saeule im Trend: der Wert, ein Teil davon innen (genommen im faelligen),
 * hervorgehoben oder blass, die Spanne der Tage dahinter, und ein Hinweis
 * unter dem Namen ("so far" fuer die laufende Woche). [gesprochen] ist der
 * Name fuer TalkBack - "Mo" vorgelesen ist kein Wochentag.
 */
internal data class TSaeule(
    val name: String,
    val wert: Double?,
    val innen: Double? = null,
    val hervor: Boolean = false,
    val kleinster: Double? = null,
    val groesster: Double? = null,
    val blass: Boolean = false,
    val hinweis: String? = null,
    val gesprochen: String? = null,
)

/** Eine waagrechte Linie mit ihrer Beschriftung: durchgezogen das Ziel, gestrichelt der Mittelwert. */
private class Linie(val y: Float, val text: String)

/**
 * Ziel und Mittelwert mit Beschriftung an ihrer Linie.
 *
 * DIE BESCHRIFTUNG SUCHT SICH EINEN FREIEN PLATZ: von rechts nach links, je
 * erst ueber, dann unter der Linie. `belegt` sind Saeulen, Punkte, ihre
 * Verbindungslinien und die Zahlen darueber, und die andere Linie zaehlt mit.
 * Ein "avg 31" quer ueber einer Saeule oder dicht an einer Zahl ("avg 55 54")
 * liest sich als eine Zahl. Ist nirgends Platz, steht sie dort, wo sie am
 * wenigsten verdeckt - nie aber unter [unten], im Fuss mit den Namen.
 *
 * DIE LINIEN SETZEN UNTER JEDER SCHRIFT AUS: `zahlen` sind die Werte ueber
 * den Saeulen. Liegt eine Saeule knapp unter Ziel oder Schnitt, liefen die
 * Linien sonst mitten durch ihre Zahl ("6:46" durchgestrichen).
 */
private fun DrawScope.linien(
    messer: TextMeasurer, stil: TextStyle, farbe: Color, von: Float, bis: Float, unten: Float, marke: Linie?, mittel: Linie?,
    belegt: List<Rect> = emptyList(), zahlen: List<Rect> = emptyList(),
) {
    val abstand = 2.dp.toPx()
    // Der Mindestabstand zu allem Belegten - knapper liest es sich als eine Zeile.
    val luft = 6.dp.toPx()
    val besetzt = belegt.map { it.inflate(luft) }.toMutableList()
    val gesetzt = mutableListOf<Pair<TextLayoutResult, Offset>>()
    fun setze(l: Linie, andere: Linie?) {
        val m = messer.measure(l.text, stil)
        val w = m.size.width.toFloat()
        val h = m.size.height.toFloat()
        val hindernisse = besetzt + listOfNotNull(andere?.let { Rect(von, it.y - abstand, bis, it.y + abstand) })
        fun stoerung(o: Offset): Double {
            val r = Rect(o, Size(w, h))
            return hindernisse.sumOf { b -> r.intersect(b).let { i -> if (i.width > 0f && i.height > 0f) (i.width * i.height).toDouble() else 0.0 } }
        }
        // Oberhalb des Fusses bleiben: dort stehen die Namen der Saeulen.
        val tiefstens = maxOf(0f, unten - h)
        val ueber = (l.y - abstand - h).coerceIn(0f, tiefstens)
        val unter = (l.y + abstand).coerceIn(0f, tiefstens)
        val xe = generateSequence(bis - w) { it - 4.dp.toPx() }.takeWhile { it >= von }.toList().ifEmpty { listOf(von) }
        // minBy nimmt bei Gleichstand den ersten: so weit rechts wie moeglich, ueber der Linie vor darunter.
        val ort = xe.flatMap { x -> listOf(Offset(x, ueber), Offset(x, unter)) }.minBy { stoerung(it) }
        gesetzt += m to ort
        besetzt += Rect(ort, Size(w, h)).inflate(luft)
    }
    // Das Ziel zuerst: es ist die feste Groesse, der Mittelwert weicht aus.
    marke?.let { setze(it, mittel) }
    mittel?.let { setze(it, marke) }
    // Nur seitlich erweitert: die eigene Beschriftung steht 2 dp ueber oder unter
    // ihrer Linie, hoeher ausgespart verloere sie dort ein Stueck.
    val luecken = Path()
    (zahlen + gesetzt.map { (m, o) -> Rect(o, Size(m.size.width.toFloat(), m.size.height.toFloat())) }).forEach {
        luecken.addRect(Rect(it.left - abstand, it.top, it.right + abstand, it.bottom))
    }
    clipPath(luecken, ClipOp.Difference) {
        marke?.let { drawLine(farbe, Offset(von, it.y), Offset(bis, it.y), 1.5.dp.toPx()) }
        mittel?.let { drawLine(farbe, Offset(von, it.y), Offset(bis, it.y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))) }
    }
    gesetzt.forEach { (m, o) -> drawText(m, topLeft = o) }
}

/**
 * Eine Verbindungslinie als Kette kleiner Felder: als ein Rechteck gezaehlt,
 * sperrte eine schraege Linie ihre ganze Diagonale fuer die Beschriftung -
 * ungezaehlt lief ein "avg 55" mitten ueber sie.
 */
private fun strich(a: Offset, b: Offset, dicke: Float): List<Rect> {
    val n = maxOf(1, ((b - a).getDistance() / dicke).toInt())
    return (0..n).map { k -> Rect(lerp(a, b, k / n.toFloat()), dicke) }
}

/**
 * Welche Saeulen ihre Zahl bekommen: alle, wenn die breiteste Zahl ins Fach
 * passt - sonst nur hoechste, tiefste und letzte, damit sich nichts ueberlappt.
 */
private fun beschriftete(werte: List<Double?>, passt: Boolean): Set<Int> {
    val mit = werte.withIndex().filter { it.value != null }
    if (passt) return mit.map { it.index }.toSet()
    return setOfNotNull(mit.maxByOrNull { it.value!! }?.index, mit.minByOrNull { it.value!! }?.index, mit.lastOrNull()?.index)
}

/** Das Kontrastverhaeltnis zweier Farben nach WCAG (1 bis 21). */
private fun kontrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/**
 * Der Ton fuer einen Kern in einer Saeule ([grund]): von den zwei Toenen der
 * Groesse der mit mehr Kontrast. EIN fester Ton reichte nicht: im dunklen
 * Schema ist er hell und verschwand in der vollen Saeule von heute (1,3:1),
 * ein dunkler verschwand in den blassen. So bleibt der Kern ueberall ab 2,7:1.
 */
private fun kernAuf(farbe: Farbe, grund: Color): Color = listOf(farbe.hell, farbe.dunkel).maxBy { kontrast(it, grund) }

/**
 * Die Breite der Zahlenspalte rechts: so breit wie die breiteste Zahl, aber
 * nie schmaler als auf der Herzseite (30 dp) - mit grosser Schrift ragte
 * "100" sonst ins Bild.
 */
private fun DrawScope.achsenspalte(texte: List<TextLayoutResult>): Float =
    maxOf(30.dp.toPx(), (texte.maxOfOrNull { it.size.width } ?: 0) + 6.dp.toPx())

/**
 * Saeulen je Wochentag oder Woche. Ueber jeder Saeule ihre Zahl, ein hohles
 * Fach mit "–" fuer Wochentage ohne Mittel, ein duenner Strich fuer die Spanne
 * der Tage. `kurz` schreibt die Zahlen und die Linienbeschriftung; ohne sie
 * bleiben Saeulen und Linien stumm. `sprich` nennt die Werte fuer TalkBack
 * mit Einheit ("8:38 h" statt "8:38"), `name` die Groesse vorneweg.
 *
 * `pegel` wie im [Tagesbild]: Ruhepuls und HRV als Punkte auf einer engen
 * Achse statt als Saeulen ab null - 54 und 57 bpm waeren sonst 79 % gegen
 * 83 % hoch und nicht zu unterscheiden.
 */
@Composable
internal fun Trendsaeulen(
    saeulen: List<TSaeule>, farbe: Farbe, mittel: Double? = null, marke: Double? = null, hoehe: Dp = 130.dp,
    kurz: ((Double) -> String)? = null, pegel: Boolean = false, sprich: ((Double) -> String)? = kurz,
    name: String? = null,
) {
    if (saeulen.none { it.wert != null }) return
    val messer = rememberTextMeasurer()
    val stil = MaterialTheme.typography.labelSmall.copy(color = Farben.neutral())
    val fett = stil.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
    val spur = Farben.spur()
    val neutral = Farben.neutral()
    val raster = Farben.raster()
    // Blasse Saeulen und Punkte deckend mit der Karte gemischt, nicht mit Alpha: so liegt
    // der Spannenstrich nicht doppelt im Punkt, und der Kontrast laesst sich nachrechnen.
    val voll = farbe.flaeche()
    val halb = voll.aufKarte(0.55f)
    val blass = voll.aufKarte(0.35f)
    val ton = farbe.ton()
    val tonHalb = ton.aufKarte(0.55f)
    val tonBlass = ton.aufKarte(0.35f)
    val mittelText = mittel?.let { m -> kurz?.let { stringResource(R.string.ks_trn_avg, it(m)) } }.orEmpty()
    val zielText = marke?.let { m -> kurz?.let { stringResource(R.string.ks_trn_goal, it(m)) } }.orEmpty()
    val zweizeilig = saeulen.any { it.hinweis != null }
    // Fuer TalkBack die Groesse, jede Saeule mit Namen und Wert, dann Schnitt und Ziel - alle mit Einheit.
    val mittelGesprochen = mittel?.let { m -> sprich?.let { stringResource(R.string.ks_trn_avg, it(m)) } }
    val zielGesprochen = marke?.let { m -> sprich?.let { stringResource(R.string.ks_trn_goal, it(m)) } }
    val satz = (
        listOfNotNull(name) + saeulen.map { s ->
            val tag = listOfNotNull(s.gesprochen ?: s.name, s.hinweis).joinToString(" ")
            val wert = s.wert?.let { w -> sprich?.let { f -> s.innen?.let { "${f(it)}/${f(w)}" } ?: f(w) } } ?: Formate.FEHLT
            "$tag $wert"
        } + listOfNotNull(mittelGesprochen, zielGesprochen)
        ).joinToString(", ")
    // Fuss (eine oder zwei Zeilen) und Kopf fuer die Zahlen wachsen mit der Schrift; die Saeulen behalten ihre Hoehe.
    val zeilen = (if (zweizeilig) 2 else 1) + 1
    Canvas(Modifier.fillMaxWidth().height(hoeheMitSchrift(if (zweizeilig) hoehe + 14.dp else hoehe, zeilen)).padding(top = 4.dp).bildBeschreibung(satz)) {
        val zeile = messer.measure("0", fett).size.height.toFloat()
        // Platz fuer die Zahl ueber der hoechsten Saeule, aus der Schrift: bei 1,0 die bisherigen 14 dp.
        val kopf = (zeile - 2.dp.toPx()).coerceAtLeast(0f)
        val boden = size.height - fussraum(messer, fett, if (zweizeilig) 2 else 1, 4.dp)
        val alle = saeulen.flatMap { listOfNotNull(it.wert, it.kleinster, it.groesster) } + listOfNotNull(mittel, marke)
        // Die Striche koennen ueber min-5..max+5 hinausreichen (runde Grenzen); dann sind sie die Achse.
        val striche = if (pegel) Auswertung.achsenschritte(alle.min() - 5, alle.max() + 5) else emptyList()
        val achse = striche.map { messer.measure(kurz?.invoke(it) ?: Formate.zahl(it, 0), stil) }
        val unten = if (pegel) minOf(alle.min() - 5, striche.min()) else 0.0
        val spitze = if (pegel) maxOf(alle.max() + 5, striche.max()) else maxOf(saeulen.maxOf { maxOf(it.wert ?: 0.0, it.groesster ?: 0.0) }, marke ?: 0.0, 1.0) * 1.2
        val rechts = size.width - if (pegel) achsenspalte(achse) else 0f
        val fach = rechts / saeulen.size
        val breite = minOf(fach * 0.55f, 26.dp.toPx())
        val ecke = CornerRadius(4.dp.toPx())
        val punkt = 4.dp.toPx()
        fun y(w: Double) = (kopf + (boden - kopf) * (1 - (w - unten) / (spitze - unten))).toFloat()
        val texte = saeulen.map { s -> s.wert?.let { w -> kurz?.invoke(w) } }
        val passt = texte.maxOf { t -> t?.let { messer.measure(it, fett).size.width } ?: 0 } <= fach - 2.dp.toPx()
        val mitZahl = beschriftete(saeulen.mapIndexed { i, s -> s.wert.takeIf { texte[i] != null } }, passt)
        val belegt = mutableListOf<Rect>()
        val zahlen = mutableListOf<Rect>()
        if (pegel) {
            striche.forEachIndexed { k, s ->
                drawLine(raster, Offset(0f, y(s)), Offset(rechts, y(s)), 1.dp.toPx())
                val m = achse[k]
                drawText(m, topLeft = Offset(size.width - m.size.width, (y(s) - m.size.height / 2f).coerceIn(0f, maxOf(0f, boden - m.size.height / 2f))))
            }
        }
        // Neun Montagsdaten passen nicht nebeneinander: dann nur jedes k-te, der letzte Name bleibt.
        val namenSchritt = maxOf(1, Math.ceil(saeulen.maxOf { messer.measure(it.name, fett).size.width } / (fach - 2.dp.toPx()).toDouble()).toInt())
        saeulen.forEachIndexed { i, s ->
            val saeuleLinks = fach * i + (fach - breite) / 2
            val mitte = fach * i + fach / 2
            if (!pegel) drawRoundRect(spur, Offset(saeuleLinks, kopf), Size(breite, boden - kopf), ecke)
            val w = s.wert
            if (w == null) {
                val m = messer.measure(Formate.FEHLT, stil)
                val o = Offset(mitte - m.size.width / 2f, (kopf + boden) / 2 - m.size.height / 2f)
                drawText(m, topLeft = o)
                belegt += Rect(o, Size(m.size.width.toFloat(), m.size.height.toFloat()))
            } else {
                val hoch = s.groesster
                if (s.kleinster != null && hoch != null && hoch > s.kleinster) {
                    drawLine(neutral, Offset(mitte, y(hoch)), Offset(mitte, y(s.kleinster)), 1.dp.toPx())
                    belegt += Rect(mitte - 1.dp.toPx(), y(hoch), mitte + 1.dp.toPx(), y(s.kleinster))
                }
                if (pegel) {
                    drawCircle(when { s.blass -> tonBlass; s.hervor -> ton; else -> tonHalb }, punkt, Offset(mitte, y(w)))
                    belegt += Rect(Offset(mitte, y(w)), punkt)
                } else if (w > 0) {
                    val fuellung = when { s.blass -> blass; s.hervor -> voll; else -> halb }
                    drawRoundRect(fuellung, Offset(saeuleLinks, y(w)), Size(breite, boden - y(w)), ecke)
                    belegt += Rect(Offset(saeuleLinks, y(w)), Size(breite, boden - y(w)))
                    // Der genommene Teil in dem Ton, der sich von seiner Saeule abhebt (siehe kernAuf).
                    s.innen?.takeIf { it > 0 }?.let {
                        drawRoundRect(kernAuf(farbe, fuellung), Offset(saeuleLinks + breite * 0.2f, y(it)), Size(breite * 0.6f, boden - y(it)), ecke)
                    }
                }
                val text = texte[i]
                if (text != null && i in mitZahl) {
                    val m = messer.measure(text, if (s.hervor) fett else stil)
                    val ueber = (if (pegel) punkt + 3.dp.toPx() else 2.dp.toPx())
                    val oben = minOf(y(w), y(hoch ?: w)) - ueber - m.size.height
                    val ecke0 = Offset(mitte - m.size.width / 2f, oben.coerceAtLeast(0f))
                    drawText(m, topLeft = ecke0)
                    belegt += Rect(ecke0, Size(m.size.width.toFloat(), m.size.height.toFloat()))
                    zahlen += belegt.last()
                }
            }
            val m = messer.measure(s.name, if (s.hervor) fett else stil)
            if ((saeulen.size - 1 - i) % namenSchritt == 0) drawText(m, topLeft = Offset(mitte - m.size.width / 2f, boden + 2.dp.toPx()))
            s.hinweis?.let {
                val h = messer.measure(it, stil)
                drawText(h, topLeft = Offset(mitte - h.size.width / 2f, boden + 2.dp.toPx() + m.size.height))
            }
        }
        linien(messer, stil, neutral, 0f, rechts, boden, marke?.let { Linie(y(it), zielText) }, mittel?.let { Linie(y(it), mittelText) }, belegt, zahlen)
    }
}

/**
 * Vierzehn Kalendertage, ein Fach je Tag - auch fuer Tage ohne Wert, als
 * hohler Rahmen. Der juengste Tag steht kraeftig, das Wochenende leicht
 * schattiert, unter jedem Montag sein Datum.
 *
 * PEGELGROESSEN OHNE SAEULEN AB NULL. Ein Ruhepuls von 55 neben einem von 58
 * ist als Saeule nicht zu unterscheiden; als Punkt auf einer engen Achse
 * schon. Darum Punkte mit Verbindungslinie und die Achse von min-5 bis max+5,
 * ihre Zahlen rechts wie auf der Herzseite.
 *
 * Das Beispiel fuer grosse Schrift und TalkBack im Baukasten: Fuss und Kopf
 * sind gemessen, das Bild sagt [beschreibung] (ohne sie: [name], Spanne,
 * letzter Wert, Schnitt, Ziel - mit Einheit ueber [sprich], wie bei den
 * [Trendsaeulen]).
 */
@Composable
internal fun Tagesbild(
    tage: List<Pair<LocalDate, Double?>>, farbe: Farbe, kurz: (Double) -> String,
    mittel: Double? = null, marke: Double? = null, pegel: Boolean = false, hoehe: Dp = 150.dp,
    beschreibung: String? = null, sprich: (Double) -> String = kurz, name: String? = null,
) {
    val werte = tage.mapNotNull { it.second }
    if (werte.isEmpty()) return
    val context = LocalContext.current
    val messer = rememberTextMeasurer()
    val stil = MaterialTheme.typography.labelSmall.copy(color = Farben.neutral())
    val fett = stil.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
    val spur = Farben.spur()
    val rahmen = Farben.raster()
    // Die Schattierung liegt unter den Saeulen - deckend gemischt sieht sie aus wie zuvor mit Alpha.
    val wochenende = MaterialTheme.colorScheme.onSurface.aufKarte(0.04f)
    val neutral = Farben.neutral()
    val raster = Farben.raster()
    val voll = farbe.flaeche()
    val halb = voll.aufKarte(0.55f)
    val ton = farbe.ton()
    val tonHalb = ton.aufKarte(0.55f)
    val mittelText = mittel?.let { stringResource(R.string.ks_trn_avg, kurz(it)) }.orEmpty()
    val zielText = marke?.let { stringResource(R.string.ks_trn_goal, kurz(it)) }.orEmpty()
    // Die Montage einmal benannt, in der Form der Formate - nicht bei jedem Zeichnen.
    val montage = remember(tage, Locale.getDefault()) { tage.map { (t, _) -> if (t.dayOfWeek == DayOfWeek.MONDAY) Formate.datumKurz(t) else null } }
    // Fuer TalkBack Groesse, Spanne, juengster Wert und die Linien in einem Satz, die Werte mit Einheit.
    // Datum und Wert mit Doppelpunkt: "Oct 2 52" klang wie eine Zahl.
    val juengster = tage.lastOrNull { it.second != null }
    val satz = beschreibung ?: listOfNotNull(
        name,
        Formate.zeitraum(context, tage.first().first, tage.last().first),
        juengster?.let { (t, w) -> Formate.datumKurz(t) + ": " + sprich(w!!) },
        mittel?.let { stringResource(R.string.ks_trn_avg, sprich(it)) },
        marke?.let { stringResource(R.string.ks_trn_goal, sprich(it)) },
    ).joinToString(", ")
    // Die Hoehe waechst mit der Schrift von Fuss und Kopf, damit die Saeulen nicht schrumpfen.
    Canvas(Modifier.fillMaxWidth().height(hoeheMitSchrift(hoehe, 2)).padding(top = 4.dp).bildBeschreibung(satz)) {
        // Der Kopf fuer die Zahl ueber der hoechsten Saeule, aus der Schrift: bei 1,0 die bisherigen 14 dp.
        val kopf = (messer.measure("0", fett).size.height - 2.dp.toPx()).coerceAtLeast(0f)
        // Der Fuss aus der Schrift gemessen wie bei den Trendsaeulen: fest 16 dp liefen die Daten ab 1,15 ins Bild.
        val boden = size.height - fussraum(messer, stil)
        // Die Striche koennen ueber min-5..max+5 hinausreichen (runde Grenzen); dann sind sie die Achse.
        val striche = if (pegel) Auswertung.achsenschritte(werte.min() - 5, werte.max() + 5) else emptyList()
        val achse = striche.map { messer.measure(kurz(it), stil) }
        val unten = if (pegel) minOf(werte.min() - 5, striche.min()) else 0.0
        val oben = if (pegel) maxOf(werte.max() + 5, striche.max()) else maxOf(werte.max(), marke ?: 0.0, 1.0) * 1.2
        fun y(w: Double) = (kopf + (boden - kopf) * (1 - (w - unten) / (oben - unten))).toFloat()
        val rechts = size.width - if (pegel) achsenspalte(achse) else 0f
        val fach = rechts / tage.size
        val breite = minOf(fach * 0.6f, 22.dp.toPx())
        val ecke = CornerRadius(3.dp.toPx())
        val texte = tage.map { t -> t.second?.let(kurz) }
        val passt = texte.maxOf { t -> t?.let { messer.measure(it, fett).size.width } ?: 0 } <= fach - 2.dp.toPx()
        val mitZahl = beschriftete(tage.map { it.second }, passt)
        val letzter = tage.indexOfLast { it.second != null }
        val belegt = mutableListOf<Rect>()
        val zahlen = mutableListOf<Rect>()
        tage.forEachIndexed { i, (tag, _) ->
            if (tag.dayOfWeek == DayOfWeek.SATURDAY || tag.dayOfWeek == DayOfWeek.SUNDAY) {
                drawRect(wochenende, Offset(fach * i, kopf), Size(fach, boden - kopf))
            }
        }
        if (pegel) {
            striche.forEachIndexed { k, s ->
                drawLine(raster, Offset(0f, y(s)), Offset(rechts, y(s)), 1.dp.toPx())
                val m = achse[k]
                drawText(m, topLeft = Offset(size.width - m.size.width, (y(s) - m.size.height / 2f).coerceIn(0f, maxOf(0f, boden - m.size.height / 2f))))
            }
        }
        // Die Zahlen ueber den Tagen zuerst setzen: die Verbindungslinien der Pegelpunkte
        // setzen unter ihnen aus, wie Ziel und Schnitt - sonst lief eine steigende Linie durch die Zahl.
        val zahlTexte = tage.mapIndexed { i, (_, w) ->
            val text = texte[i]
            if (w == null || text == null || i !in mitZahl) return@mapIndexed null
            val m = messer.measure(text, if (i == letzter) fett else stil)
            val abstand = (if (pegel) 5.dp else 2.dp).toPx()
            m to Offset(fach * i + fach / 2 - m.size.width / 2f, (y(w) - abstand - m.size.height).coerceAtLeast(0f))
        }
        zahlTexte.filterNotNull().forEach { (m, o) -> zahlen += Rect(o, Size(m.size.width.toFloat(), m.size.height.toFloat())) }
        belegt += zahlen
        if (pegel) {
            val luecken = Path().apply { zahlen.forEach { addRect(it) } }
            clipPath(luecken, ClipOp.Difference) {
                tage.forEachIndexed { i, (_, w) ->
                    val n = tage.getOrNull(i + 1)?.second ?: return@forEachIndexed
                    if (w == null) return@forEachIndexed
                    val a = Offset(fach * i + fach / 2, y(w))
                    val b = Offset(a.x + fach, y(n))
                    drawLine(tonHalb, a, b, 1.5.dp.toPx())
                    belegt += strich(a, b, 1.5.dp.toPx())
                }
            }
        }
        tage.forEachIndexed { i, (_, w) ->
            val mitte = fach * i + fach / 2
            if (!pegel) {
                if (w == null) {
                    drawRoundRect(rahmen, Offset(mitte - breite / 2, kopf), Size(breite, boden - kopf), ecke, style = Stroke(1.dp.toPx()))
                } else {
                    drawRoundRect(spur, Offset(mitte - breite / 2, kopf), Size(breite, boden - kopf), ecke)
                    if (w > 0) {
                        drawRoundRect(if (i == letzter) voll else halb, Offset(mitte - breite / 2, y(w)), Size(breite, boden - y(w)), ecke)
                        // Die Saeule selbst ist belegt: eine Linienbeschriftung darueber laese sich als ihre Zahl.
                        belegt += Rect(Offset(mitte - breite / 2, y(w)), Size(breite, boden - y(w)))
                    }
                }
            } else if (w != null) {
                drawCircle(if (i == letzter) ton else tonHalb, 3.dp.toPx(), Offset(mitte, y(w)))
                belegt += Rect(Offset(mitte, y(w)), 3.dp.toPx())
            }
            zahlTexte[i]?.let { (m, o) -> drawText(m, topLeft = o) }
            montage[i]?.let { d ->
                val m = messer.measure(d, stil)
                drawText(m, topLeft = Offset((mitte - m.size.width / 2f).coerceIn(0f, maxOf(0f, size.width - m.size.width)), size.height - m.size.height))
            }
        }
        linien(messer, stil, neutral, 0f, rechts, boden, marke?.let { Linie(y(it), zielText) }, mittel?.let { Linie(y(it), mittelText) }, belegt, zahlen)
    }
}
