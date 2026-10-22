package coredevices.pebble.kiesel.ansicht

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.GesundheitsStand
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.Nacht
import coredevices.pebble.kiesel.Tagesbandrechnung
import coredevices.pebble.kiesel.Tagesbandrechnung.Art
import coredevices.pebble.kiesel.Tagesbandrechnung.Ereignis
import coredevices.pebble.kiesel.Tagesbandrechnung.Marke
import coredevices.pebble.kiesel.TrainingEintrag
import coredevices.pebble.kiesel.pulsFrisch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/*
 * DAS TAGESBAND - der Tag quer, von der letzten Nacht bis zur naechsten
 * Schlafenszeit, 40 dp je Stunde und seitlich wischbar. Von oben: die
 * Stunden, der Puls vor seinen Bezugslinien (Ruhepuls, Zonengrenzen; die
 * Zahlen fest rechts daneben), eine gemeinsame Zeile fuer Schlaf und Schritte (sie
 * ueberschneiden sich nie), die Symbole der Eintraege und unter der Nacht
 * ihr Text. Die Masse stehen im Entwurf "Erlebnis-Health"; was wohin
 * kommt und wie dichte Symbole zusammengehen, rechnet [Tagesbandrechnung].
 */

/** Der Massstab: 40 dp je Stunde. */
private const val STUNDE_DP = 40f

/** Links Luft fuer die erste Stunde, rechts fuer den Puls neben dem Jetzt. */
private const val RAND_LINKS_DP = 12f
private const val RAND_RECHTS_DP = 30f

private val HOEHE = 198.dp

/** Unter der Stundenzeile beginnen Raster und Jetzt-Linie. */
private val RASTER_OBEN = 18.dp

/** Die Zeile des Pulses. */
private val PULS_OBEN = 26.dp
private val PULS_UNTEN = 96.dp

/** So deckend steht die Ruhelinie ueber der Karte: gedaempftes Herzrot hinter der vollen Pulslinie. */
private const val RUHE_DECKUNG = 0.55f

/**
 * Die Spalte der Zahlen rechts neben dem Band: so breit wie [ZAHL_MUSTER] in
 * der Schrift der Achsen, dazu [ZAHL_LUFT] zum Band und [ZAHL_RAND] zum
 * Kartenrand. Gemessen, nicht fest: mit grosser Schrift werden die Zahlen breiter, die Spalte mit ihnen.
 */
private const val ZAHL_MUSTER = "000"
private val ZAHL_LUFT = 4.dp
private val ZAHL_RAND = 10.dp

/** Die Schrittsaeulen stehen unten buendig hier; die Symbole beginnen 10 dp darunter. */
private val SCHRITT_BODEN = 142.dp
private val SCHRITT_HOCH = 40.dp

/**
 * Darunter fuellt eine halbe Stunde die Saeule nicht: an einem ruhigen Tag
 * stuenden 50 Schritte sonst so hoch wie ein Spaziergang.
 */
private const val SCHRITTE_VOLL = 1000L

/** Die vier Spuren der Nacht: so hoch wie Schrittsaeulen und Symbole zusammen. */
private val SPUR_OBEN = 112.dp
private val SPUR_HOEHE = 15.dp
private val SPUR_ABSTAND = 17.dp

private val SYMBOL_OBEN = 152.dp
private val SYMBOL = 26.dp

/** Die Zeile unter den Symbolen: ihre Werte, unter der Nacht der Schlaftext. */
private val TEXT_OBEN = 182.dp

/** So breit blenden die Raender aus - man sieht, dass es weitergeht. */
private val AUSBLENDEN = 18.dp

/** Beim Oeffnen steht das Jetzt bei drei Vierteln der sichtbaren Breite: der Morgen davor bleibt im Blick. */
private const val JETZT_ANTEIL = 0.75f

/** So lange gleitet das Band beim Oeffnen der App zum Jetzt: wie Pebbles App ihre Filterzeile scrollt (NotificationAppsScreen). */
private const val GLEITEN_MS = 600

/** Was ein Symbol des Bands traegt. */
private sealed class Bandinhalt {
    data class Eintrag(val e: Ereignis) : Bandinhalt()
    data class Offen(val g: Tagesbandrechnung.Geplant) : Bandinhalt()
    data class Bett(val zeit: Instant) : Bandinhalt()
}

private typealias Bandmarke = Marke<Bandinhalt>

/**
 * Das Tagesband der Gesundheitsseite (Karte "TODAY SO FAR"). Ein Tipp auf
 * die Nacht oeffnet die Schlafansicht, auf Wasser Water, auf Kaffee und
 * Praeparat Stack, auf ein Training dessen Detail; ein langer Druck auf
 * einen eigenen Eintrag aendert oder loescht ihn wie bisher. Fuer TalkBack
 * ist das Band ein Bild mit einem Satz - die Liste darunter ist seine
 * zugaengliche Form.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Tagesband(
    s: GesundheitsStand,
    trainings: List<TrainingEintrag>,
    jetzt: Instant,
    imperial: Boolean,
    wege: Gesundheitswege,
    oeffnung: Int = 0,
    geaendert: () -> Unit,
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    var bearbeite by remember { mutableStateOf<Bearbeitung?>(null) }
    var liste by remember { mutableStateOf<List<Bandmarke>?>(null) }

    val fenster = remember(s.nacht, jetzt) { Tagesbandrechnung.fenster(s.nacht, jetzt, zone) }
    val von = fenster.von
    fun x(t: Instant): Float = RAND_LINKS_DP + Duration.between(von, t).seconds / 3600f * STUNDE_DP
    val breite = x(fenster.bis) + RAND_RECHTS_DP

    val platz = stringResource(R.string.ks_s_platz_n)
    val nacht = s.nacht
    val bett = Gesundheitsrechnung.bettzeit(nacht?.einschlafen, jetzt, zone)
    val marken = remember(s, trainings, jetzt) {
        Tagesbandrechnung.ereignisse(s, trainings, fenster).map { e ->
            val art = when (e) {
                is Ereignis.Training -> Art.TRAINING
                is Ereignis.Wasser -> Art.WASSER
                is Ereignis.Getraenk -> Art.KAFFEE
                is Ereignis.Praeparate -> Art.PRAEPARAT
            }
            Marke<Bandinhalt>(art, e.zeit, false, Bandinhalt.Eintrag(e))
        } + Tagesbandrechnung.geplant(s, jetzt, zone) { String.format(platz, it) }.map {
            Marke<Bandinhalt>(Art.PRAEPARAT, it.zeit, true, Bandinhalt.Offen(it))
        } + listOfNotNull(bett?.let { Marke<Bandinhalt>(Art.BETT, it, true, Bandinhalt.Bett(it)) })
    }
    val symbole = remember(marken, von) { Tagesbandrechnung.anordnen(marken) { x(it) } }
    // Die Zahl der heute geplanten Praeparate, fuer "3 of 4" unter einem genommenen.
    val geplantHeute = remember(s.supplemente, s.mitSupCycle) {
        s.supplemente?.takeIf { s.mitSupCycle }?.heute { String.format(platz, it) }?.size ?: 0
    }

    val linie = remember(s.bandPuls) { Tagesbandrechnung.pulslinie(s.bandPuls) }
    // Die Skala aus dem, was die Linie zeigt, dem Ruhepuls des Bands (der der Nacht, ohne sie der
    // der Zonen - Tagesbandrechnung.ruhepuls) und den Zonen der Herzseite.
    val ruhepuls = Tagesbandrechnung.ruhepuls(s)
    val skala = remember(linie, ruhepuls, s.pulsZonen) {
        Tagesbandrechnung.pulsskala(ruhepuls, s.pulsZonen, linie.flatten().map { it.second })
    }
    fun yPuls(bpm: Int): Dp = PULS_UNTEN - (PULS_UNTEN - PULS_OBEN) * ((bpm - skala.tief).toFloat() / (skala.hoch - skala.tief))
    // Der letzte Puls nur, wenn er frisch ist - seit 0.35 zeigt "jetzt" keinen veralteten.
    val jetztPuls = s.puls.maxByOrNull { it.first }?.takeIf { Gesundheitsrechnung.pulsFrisch(it.first, jetzt.epochSecond) }

    val schlaftext = nacht?.let { schlaftext(it) }
    // Die Beschreibung nennt den Ruhepuls der Linie. Mit Nacht steht er in ihrem Text; kommt er
    // nicht von ihr (Tagesbandrechnung.ruhepuls), eigens - aber nur, wenn die Linie auch dasteht.
    val ruhetext = ruhepuls?.takeIf { nacht?.ruhepuls == null && skala.linien.any { it.zone == null } }
        ?.let { stringResource(R.string.ks_tb_ruhe, it) }
    val farben = MaterialTheme.colorScheme
    val raster = Farben.raster().aufKarte(0.5f)
    val zukunft = Farben.zukunft()
    val pulsfarbe = Farben.puls.ton()
    // Gedaempft: die Ruhelinie ist Massstab, nicht Messung - der Puls davor soll vorne stehen.
    val ruhefarbe = pulsfarbe.aufKarte(RUHE_DECKUNG)
    val zonenfarben = Farben.pulszonen.map { it.ton() }
    val achse = achsStil()
    val bewegung = Farben.bewegung.flaeche()
    val bewegungBlass = bewegung.aufKarte(0.45f)
    val stunde = stundenText()
    val messer = rememberTextMeasurer()
    val stundenStil = TextStyle(fontSize = 12.sp, color = farben.outline)
    val nachtSpanne = nacht?.let { n ->
        (n.abschnitte.minOfOrNull { it.von } ?: n.einschlafen).coerceAtMost(n.einschlafen) to
            (n.abschnitte.maxOfOrNull { it.bis } ?: n.aufwachen).coerceAtLeast(n.aufwachen)
    }
    val trainingsZeiten = trainings.map { it.sitzung.startTime to it.sitzung.endTime }

    val titel = stringResource(R.string.ks_h_heute_bisher)
    val jetztText = Formate.uhrzeit(context, jetzt)
    val beschreibung = listOfNotNull(
        titel,
        stringResource(R.string.ks_h_zeitraum, Formate.uhrzeit(context, von), Formate.uhrzeit(context, fenster.bis)),
        schlaftext,
        ruhetext,
        jetztText + (jetztPuls?.let { " ${it.second} bpm" } ?: ""),
    ).joinToString(", ")
    val nachtOeffnen: (() -> Unit)? = nacht?.let { n -> { wege.nacht(n.beginnUtc) } } ?: s.juengsteNacht?.let { b -> { wege.nacht(b) } }

    Kasten {
        Kopf(titel)
        BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            val dichte = LocalDensity.current
            val bandHoehe = hoeheMitSchrift(HOEHE)
            // DIE ZAHLEN DER LINIEN IN EINER EIGENEN SPALTE RECHTS, nicht im Band: so bleiben sie
            // beim Wischen stehen, und das Ausblenden am rechten Rand bleibt sichtbar (es zeigt,
            // dass es weitergeht). Ohne Linien keine Spalte - das Band behaelt seine Breite.
            val spalte = if (skala.linien.isEmpty()) 0.dp
            else with(dichte) { messer.measure(ZAHL_MUSTER, achse).size.width.toDp() } + ZAHL_LUFT + ZAHL_RAND
            val sichtbar = maxWidth - spalte
            // Das Jetzt bei drei Vierteln der sichtbaren Breite, der Rest davor; am Anfang nie davor.
            val start = with(dichte) { (x(jetzt).dp - sichtbar * JETZT_ANTEIL).coerceAtLeast(0.dp).roundToPx() }
            val scroll = rememberScrollState(start)
            // JEDES OEFFNEN DER APP laesst das Band von seiner Stellung zum Jetzt gleiten: sonst stuende es
            // nach Stunden im Hintergrund, wo es zuletzt war, und das Jetzt laege rechts draussen. Dazwischen
            // bleibt es, wo man es hinwischt. Gemerkt, welches Oeffnen schon galt: Drehen baut neu, oeffnet aber nicht.
            var gegolten by rememberSaveable { mutableIntStateOf(oeffnung) }
            LaunchedEffect(oeffnung) {
                if (oeffnung == gegolten) return@LaunchedEffect
                gegolten = oeffnung
                scroll.animateScrollTo(start, tween(GLEITEN_MS, easing = FastOutSlowInEasing))
            }
            if (spalte > 0.dp) Canvas(Modifier.align(Alignment.TopEnd).size(spalte, bandHoehe)) {
                skala.linien.forEach { l ->
                    val m = messer.measure(Formate.zahl(l.bpm.toLong()), achse)
                    drawText(m, topLeft = Offset(size.width - ZAHL_RAND.toPx() - m.size.width, yPuls(l.bpm).toPx() - m.size.height / 2f))
                }
            }
            Box(
                Modifier.width(sichtbar).raenderAusblenden(AUSBLENDEN).horizontalScroll(scroll)
                    .clearAndSetSemantics {
                        contentDescription = beschreibung
                        if (nachtOeffnen != null) onClick { nachtOeffnen(); true }
                    },
            ) {
                // Mit grosser Schrift waechst die Hoehe um das, was die Werte unten mehr brauchen.
                Box(Modifier.size(breite.dp, bandHoehe)) {
                    Canvas(Modifier.matchParentSize()) {
                        val unten = size.height
                        // Die Zukunft blass hinterlegt.
                        drawRect(zukunft, Offset(x(jetzt).dp.toPx(), 0f), Size(size.width - x(jetzt).dp.toPx(), unten))
                        // Die Stunden alle drei, mit duennen Rasterlinien - in der Ortszeit, auch ueber eine Zeitumstellung.
                        var h = von.atZone(zone).truncatedTo(ChronoUnit.HOURS)
                        while (!h.toInstant().isAfter(fenster.bis)) {
                            if (h.hour % 3 == 0 && !h.toInstant().isBefore(von)) {
                                val sx = x(h.toInstant()).dp.toPx()
                                drawLine(raster, Offset(sx, RASTER_OBEN.toPx()), Offset(sx, unten), 1.dp.toPx())
                                val m = messer.measure(stunde(h), stundenStil)
                                drawText(m, topLeft = Offset(sx - m.size.width / 2f, 0f))
                            }
                            h = h.plusHours(1)
                        }
                        // Die Nacht in vier Spuren, Wach oben; Schlaf ohne Phase in der Spur Leicht wie im Hypnogramm.
                        nacht?.abschnitte?.forEach { a ->
                            val spur = PHASEN_VON_OBEN.indexOfFirst { it.first == (if (a.phase == Farben.OHNE_PHASE) Farben.LEICHT else a.phase) }
                            if (spur < 0) return@forEach
                            val ax = x(maxOf(a.von, von)).dp.toPx()
                            val bx = x(minOf(a.bis, fenster.bis)).dp.toPx()
                            if (bx <= ax) return@forEach
                            drawRoundRect(
                                Farben.phasen[a.phase], Offset(ax, (SPUR_OBEN + SPUR_ABSTAND * spur).toPx()),
                                Size((bx - ax).coerceAtLeast(2.dp.toPx()), SPUR_HOEHE.toPx()), CornerRadius(3.dp.toPx()),
                            )
                        }
                        // Die Schritte je halbe Stunde, unten buendig; waehrend eines Trainings kraeftig, sonst blass.
                        val voll = maxOf(s.schrittBins.maxOfOrNull { it.second } ?: 0L, SCHRITTE_VOLL)
                        s.schrittBins.forEach { (beginn, schritte) ->
                            val t = Instant.ofEpochSecond(beginn)
                            // In der Nacht nicht: dort steht das Hypnogramm, und ein Schritt im Schlaf ist Rauschen.
                            if (nachtSpanne != null && !t.isBefore(nachtSpanne.first) && t.isBefore(nachtSpanne.second)) return@forEach
                            if (t.isBefore(von)) return@forEach
                            val ende = t.plusSeconds(1800)
                            val imTraining = trainingsZeiten.any { (a, b) -> t.isBefore(b) && ende.isAfter(a) }
                            val hoehe = SCHRITT_HOCH.toPx() * schritte / voll
                            drawRoundRect(
                                if (imTraining) bewegung else bewegungBlass,
                                Offset((x(t) + 3f).dp.toPx(), SCHRITT_BODEN.toPx() - hoehe),
                                Size(14.dp.toPx(), hoehe), CornerRadius(2.dp.toPx()),
                            )
                        }
                        // Hinter dem Puls seine Bezugslinien quer durchs Band, nach den Regeln der App:
                        // der Ruhepuls gestrichelt, die Zonengrenzen gepunktet in ihrer Zonenfarbe wie auf der Herzseite.
                        val gestrichelt = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                        val gepunktet = PathEffect.dashPathEffect(floatArrayOf(0.1f, 9.dp.toPx()))
                        skala.linien.forEach { l ->
                            val ly = yPuls(l.bpm).toPx()
                            val zonenfarbe = l.zone?.let { zonenfarben.getOrNull(it) }
                            if (zonenfarbe == null) drawLine(ruhefarbe, Offset(0f, ly), Offset(size.width, ly), 1.5.dp.toPx(), pathEffect = gestrichelt)
                            else drawLine(zonenfarbe, Offset(0f, ly), Offset(size.width, ly), 2.dp.toPx(), cap = StrokeCap.Round, pathEffect = gepunktet)
                        }
                        // Der Puls; Luecken ueber einer Viertelstunde zwischen zwei Messungen brechen die Linie (Tagesbandrechnung.pulslinie).
                        linie.forEach { stueck ->
                            val punkte = stueck.map { (sek, bpm) -> Offset(x(Instant.ofEpochSecond(sek + 150)).dp.toPx(), yPuls(bpm).toPx()) }
                            if (punkte.size == 1) drawCircle(pulsfarbe, 1.5.dp.toPx(), punkte[0])
                            else drawPath(
                                Path().apply { moveTo(punkte[0].x, punkte[0].y); punkte.drop(1).forEach { lineTo(it.x, it.y) } },
                                pulsfarbe, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                            )
                        }
                        // Das Jetzt: eine gruene Linie unter seiner Uhrzeit.
                        val jx = x(jetzt).dp.toPx()
                        drawLine(farben.primary, Offset(jx, RASTER_OBEN.toPx()), Offset(jx, unten), 2.dp.toPx())
                    }

                    // Die Nacht und ihr Text oeffnen die Schlafansicht.
                    if (nacht != null && nachtSpanne != null) {
                        val a = x(maxOf(nachtSpanne.first, von))
                        val b = x(nachtSpanne.second)
                        Box(
                            Modifier.offset(a.dp, SPUR_OBEN).size((b - a).coerceAtLeast(1f).dp, HOEHE - SPUR_OBEN)
                                .clickable { wege.nacht(nacht.beginnUtc) },
                        )
                        if (schlaftext != null) Text(
                            schlaftext, Modifier.mitteBei(((a + b) / 2).dp, TEXT_OBEN).clickable { wege.nacht(nacht.beginnUtc) },
                            fontSize = 12.sp, lineHeight = 14.sp, color = farben.onSurfaceVariant, maxLines = 1, softWrap = false,
                        )
                    } else if (s.juengsteNacht != null) {
                        // Ohne Nacht von heute der Weg zur juengsten - sonst waeren fruehere Naechte und Sleep trends unerreichbar.
                        val mitternacht = jetzt.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
                        Text(
                            stringResource(R.string.ks_d_keine_nacht),
                            Modifier.offset(x(maxOf(mitternacht, von)).dp, TEXT_OBEN).clickable { wege.nacht(s.juengsteNacht) },
                            fontSize = 12.sp, lineHeight = 14.sp, color = farben.outline, maxLines = 1, softWrap = false,
                        )
                    }

                    symbole.forEach { sym ->
                        Bandsymbol(sym, geplantHeute, imperial, wege, oeffneListe = { liste = it }, bearbeite = { bearbeite = it })
                    }

                    // Die Uhrzeit des Jetzt ueber seiner Linie, auf der Kartenfarbe: sie deckt die Stunde darunter.
                    Text(
                        jetztText,
                        Modifier.mitteBei(x(jetzt).dp, 0.dp).background(Farben.karte()).padding(horizontal = 4.dp),
                        fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, color = farben.primary, maxLines = 1, softWrap = false,
                    )
                    // Auf dem Grund der Zukunft, in der er steht: die Bezugslinien laufen hinter ihm durch,
                    // und ein Punkt zwischen "68" und "bpm" lase sich sonst wie ein Trenner.
                    if (jetztPuls != null) Text(
                        zahlMitEinheit(Formate.zahl(jetztPuls.second.toLong()), "bpm", 18, 12),
                        Modifier.offset((x(jetzt) + 8f - 2f).dp, (yPuls(jetztPuls.second) - 30.dp).coerceAtLeast(RASTER_OBEN))
                            .background(zukunft.compositeOver(Farben.karte())).padding(horizontal = 2.dp),
                        maxLines = 1, softWrap = false,
                    )
                }
            }
        }
    }

    liste?.let { m -> Bandliste(m, imperial, wege, onDismiss = { liste = null }) { liste = null; bearbeite = it } }
    bearbeite?.let { b -> Bearbeitungsblatt(b, onDismiss = { bearbeite = null }) { bearbeite = null; geaendert() } }
}

/** "7:59 h · resting 55 · HRV 50" - was fehlt, entfaellt. */
@Composable
private fun schlaftext(n: Nacht): String = listOfNotNull(
    Formate.dauer(n.schlafMinuten.toLong()),
    n.ruhepuls?.let { stringResource(R.string.ks_tb_ruhe, it) },
    n.hrv?.let { stringResource(R.string.ks_tb_hrv, it) },
).joinToString(" · ")

/** Zeichen und Farbe einer Art; ein Training in der Farbe seiner Sportart. */
private fun aussehen(art: Art, marken: List<Bandmarke>): Pair<ImageVector, Farbe?> = when (art) {
    Art.TRAINING -> Zeichen.laeufer to Farben.sport(((marken.first().inhalt as Bandinhalt.Eintrag).e as Ereignis.Training).training.sport)
    Art.WASSER -> Zeichen.wasser to Farben.wasser
    Art.KAFFEE -> Zeichen.tasse to Farben.koffein
    Art.PRAEPARAT -> Zeichen.kapsel to Farben.praeparat
    Art.BETT -> Zeichen.mond to null
}

/** Wie deckend der Grund des Kreises ist - dieselben Werte wie die Tonkreise der Liste. */
private fun deckung(art: Art): Float = when (art) {
    Art.KAFFEE -> 0.30f
    Art.PRAEPARAT -> 0.18f
    else -> 0.22f
}

/** Der Wert unter einem Symbol, summiert ueber seine Marken: "63 mg", "0.5 l", "3 of 4", "2.7 km". */
@Composable
private fun wert(art: Art, marken: List<Bandmarke>, geplantHeute: Int, imperial: Boolean): String? {
    val context = LocalContext.current
    val eintraege = marken.mapNotNull { (it.inhalt as? Bandinhalt.Eintrag)?.e }
    return when (art) {
        Art.TRAINING -> {
            val t = eintraege.filterIsInstance<Ereignis.Training>().map { it.training }
            val meter = t.sumOf { it.meter }
            // Schwimmen bleibt metrisch wie ueberall (streckeVon); sonst die Dauer, wenn keine Strecke da ist.
            if (meter > 0) Formate.distanz(meter, imperial && t.none { it.sport.immerMetrisch }).text
            else Formate.dauer(t.sumOf { it.minuten })
        }
        Art.WASSER -> Formate.literFein(eintraege.filterIsInstance<Ereignis.Wasser>().sumOf { it.glas.ml }) + " l"
        Art.KAFFEE -> {
            val g = eintraege.filterIsInstance<Ereignis.Getraenk>().map { it.eintrag }
            val mg = g.mapNotNull { it.koffeinMg }
            if (mg.isNotEmpty()) Formate.menge(mg.sum(), "mg").text else g.firstOrNull()?.name
        }
        Art.PRAEPARAT -> {
            val offen = marken.mapNotNull { (it.inhalt as? Bandinhalt.Offen)?.g }
            if (offen.isNotEmpty()) {
                if (offen.size == 1) offen.first().name else stringResource(R.string.ks_tb_offen, offen.size)
            } else {
                val genommen = eintraege.filterIsInstance<Ereignis.Praeparate>().flatMap { it.eintraege }
                when {
                    geplantHeute > 0 -> Formate.zahl(genommen.size.toLong()) + " " + stringResource(R.string.ks_h_von_n, geplantHeute)
                    genommen.size == 1 -> genommen.first().name
                    else -> Formate.zahl(genommen.size.toLong())
                }
            }
        }
        Art.BETT -> marken.firstNotNullOfOrNull { (it.inhalt as? Bandinhalt.Bett)?.zeit }?.let { Formate.uhrzeit(context, it) }
    }
}

/** Was ein langer Druck an einer Marke aendern darf - nur Boulders eigene Getraenke, Glaeser und Praeparate. */
@Composable
private fun bearbeitung(m: Bandmarke): Bearbeitung? {
    val e = (m.inhalt as? Bandinhalt.Eintrag)?.e ?: return null
    val glas = stringResource(R.string.ks_d_glas)
    return when (e) {
        is Ereignis.Wasser -> e.glas.takeIf { it.eigen }?.let { Bearbeitung.Glas(glas, it.zeit, it.id) }
        is Ereignis.Getraenk -> e.eintrag.takeIf { it.eigen }?.let { Bearbeitung.Getraenk(it.name, it.zeit, it.id) }
        is Ereignis.Praeparate -> if (e.eigen) Bearbeitung.Praeparate(e.titel, e.zeit, e.eintraege.map { it.id }, e.eintraege.map { it.kennung }) else null
        is Ereignis.Training -> null
    }
}

/**
 * Ein Symbol: ein runder Kreis mit Strichzeichen, darunter klein und grau
 * sein Wert. Geplantes blass mit Rand, ein Buendel als "+N".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bandsymbol(
    sym: Tagesbandrechnung.Symbol<Bandinhalt>,
    geplantHeute: Int,
    imperial: Boolean,
    wege: Gesundheitswege,
    oeffneListe: (List<Bandmarke>) -> Unit,
    bearbeite: (Bearbeitung) -> Unit,
) {
    val farben = MaterialTheme.colorScheme
    val art = sym.marken.first().art
    val geplant = sym.marken.all { it.geplant }
    val bearbeitbar = sym.marken.mapNotNull { bearbeitung(it) }
    val tipp: (() -> Unit)? = when {
        sym.buendel -> ({ oeffneListe(sym.marken) })
        art == Art.TRAINING -> sym.marken.singleOrNull()?.let { m ->
            val t = ((m.inhalt as Bandinhalt.Eintrag).e as Ereignis.Training).training
            ({ wege.training(t.id) })
        } ?: ({ oeffneListe(sym.marken) })
        art == Art.WASSER -> wege.wasser
        art == Art.KAFFEE || art == Art.PRAEPARAT -> wege.stack
        else -> null
    }
    // Ein Eintrag allein geht gleich ins Blatt; mehrere zusammengefasste erst in die Liste.
    val lang: (() -> Unit)? = when {
        sym.buendel || bearbeitbar.isEmpty() -> null
        sym.marken.size == 1 -> ({ bearbeite(bearbeitbar.single()) })
        else -> ({ oeffneListe(sym.marken) })
    }
    val grund = Farben.karte()
    Column(
        Modifier.mitteBei(sym.x.dp, SYMBOL_OBEN - 4.dp).width(48.dp)
            .then(if (tipp != null || lang != null) Modifier.combinedClickable(onLongClick = lang, onClick = { tipp?.invoke() }) else Modifier)
            .padding(top = 4.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (sym.buendel) {
            Box(Modifier.size(SYMBOL).background(grund, CircleShape).border(BorderStroke(1.5.dp, farben.outline), CircleShape), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.ks_tb_buendel, sym.marken.size), fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, color = farben.onSurfaceVariant)
            }
            return@Column
        }
        val (zeichen, farbe) = aussehen(art, sym.marken)
        val (flaeche, strich) = if (geplant || farbe == null) grund to farben.outline else farbe.flaeche().copy(alpha = deckung(art)) to farbe.ton()
        Box(
            Modifier.size(SYMBOL).background(flaeche, CircleShape)
                .then(if (geplant) Modifier.border(BorderStroke(1.5.dp, farben.outlineVariant), CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(zeichen, null, Modifier.size(16.dp), tint = strich)
        }
        wert(art, sym.marken, geplantHeute, imperial)?.let {
            Text(
                it, Modifier.wrapContentWidth(unbounded = true), fontSize = 12.sp, lineHeight = 14.sp,
                color = if (geplant) farben.outline else farben.onSurfaceVariant, maxLines = 1, softWrap = false,
            )
        }
    }
}

/**
 * Die Eintraege eines Buendels "+N" oder eines zusammengefassten Symbols.
 * Ein Tipp auf ein Training oeffnet sein Detail, auf einen eigenen Eintrag
 * das Blatt zum Aendern oder Loeschen - wie das Blatt "Add caffeine" die
 * Eintraege von gestern.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Bandliste(marken: List<Bandmarke>, imperial: Boolean, wege: Gesundheitswege, onDismiss: () -> Unit, bearbeite: (Bearbeitung) -> Unit) {
    val context = LocalContext.current
    val zeiten = marken.map { it.zeit }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Kopf(stringResource(R.string.ks_h_zeitraum, Formate.uhrzeit(context, zeiten.min()), Formate.uhrzeit(context, zeiten.max())))
            marken.sortedBy { it.zeit }.forEachIndexed { i, m ->
                if (i > 0) Trenner()
                val (zeichen, farbe) = aussehen(m.art, listOf(m))
                val b = bearbeitung(m)
                val training = ((m.inhalt as? Bandinhalt.Eintrag)?.e as? Ereignis.Training)?.training
                val titel = when (val inhalt = m.inhalt) {
                    is Bandinhalt.Eintrag -> when (val e = inhalt.e) {
                        is Ereignis.Training -> trainingsName(e.training)
                        is Ereignis.Wasser -> stringResource(if (e.glas.eigen) R.string.ks_d_glas else R.string.ks_d_wasser)
                        is Ereignis.Getraenk -> e.eintrag.name
                        is Ereignis.Praeparate -> e.titel
                    }
                    is Bandinhalt.Offen -> inhalt.g.name
                    is Bandinhalt.Bett -> stringResource(R.string.ks_f_bettzeit_vorgabe)
                }
                Zeile(
                    titel, Formate.uhrzeit(context, m.zeit), wert(m.art, listOf(m), 0, imperial),
                    zeichen = {
                        if (farbe == null || m.geplant) Tonkreis(zeichen, Farben.karte(), MaterialTheme.colorScheme.outline, alpha = 1f)
                        else Tonkreis(zeichen, farbe, deckung(m.art))
                    },
                    onClick = when {
                        training != null -> ({ wege.training(training.id) })
                        b != null -> ({ bearbeite(b) })
                        else -> null
                    },
                    chevron = training != null,
                )
            }
        }
    }
}

/**
 * Setzt den Inhalt mit seiner Mitte auf [x] und seiner Oberkante auf [y]
 * des umgebenden Bands - fuer Uhrzeiten, Texte und Symbole, die an einem
 * Zeitpunkt haengen, nicht an einer Spalte.
 */
private fun Modifier.mitteBei(x: Dp, y: Dp): Modifier = layout { messbar, grenzen ->
    val p = messbar.measure(grenzen.copy(minWidth = 0, maxWidth = Constraints.Infinity))
    layout(p.width, p.height) { p.place(x.roundToPx() - p.width / 2, y.roundToPx()) }
}

/**
 * Die Raender links und rechts blenden aus: man sieht, dass das Band
 * weitergeht. Ueber eine eigene Ebene (Offscreen), sonst nahme die Maske
 * auch die Karte darunter mit.
 */
private fun Modifier.raenderAusblenden(rand: Dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val r = (rand.toPx() / size.width).coerceIn(0f, 0.5f)
            drawRect(
                Brush.horizontalGradient(0f to Color.Transparent, r to Color.Black, 1f - r to Color.Black, 1f to Color.Transparent),
                blendMode = BlendMode.DstIn,
            )
        }
