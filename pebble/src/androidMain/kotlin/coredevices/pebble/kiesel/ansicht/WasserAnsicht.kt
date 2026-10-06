package coredevices.pebble.kiesel.ansicht

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.GesundheitsStand
import coredevices.pebble.kiesel.Gesundheitsdaten
import coredevices.pebble.kiesel.Stackrechnung
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Das Zeichen vor einem Glas oder Getraenk auf Water und Stack - etwas kleiner als in der Liste von Health. */
internal val EINTRAG_ZEICHEN = 32.dp

/** Der Trenner zwischen solchen Zeilen reicht bis zum Text: Rand, Zeichen und Abstand der [Zeile]. */
internal val EINTRAG_EINZUG = INNEN + EINTRAG_ZEICHEN + 14.dp

/** So hoch reicht die Saeule eines Tages, der sein Ziel genau erreicht hat; darueber steht der Name der Linie. */
private val ZIEL_HOEHE = 70.dp

/** Das Bild der Woche ohne die Tage darunter: Saeulen bis zum Ziel und Platz fuer mehr. */
private val WOCHE_HOEHE = 90.dp

/**
 * Die Seite Water: oben die Menge von heute gegen das Ziel, mit Balken und
 * der Zahl der Glaeser, darunter die Glaeser von heute (ein langer Druck
 * aendert oder loescht ein eigenes), die letzten sieben Tage als Saeulen an
 * der Ziellinie und der Weg zu den Ernaehrungstrends. OHNE "NEXT REMINDER":
 * Drinktervall schickt keine Erinnerungszeit (siehe die DT_-Felder), und
 * geraten waere sie falsch. Hier wird geladen - mit dem Lader von Health,
 * von dort kommend steht die Seite also gleich da; zu sehen ist [WasserInhalt].
 */
@Composable
internal fun WasserAnsicht(oeffneTrend: () -> Unit) {
    val lies = rememberGesundheitslader()
    var neu by remember { mutableIntStateOf(0) }
    var bearbeite by remember { mutableStateOf<Bearbeitung?>(null) }
    // Neue Daten von der Uhr oder ein geaendertes Glas: dann stimmen Menge und Liste nicht mehr.
    val s = rememberStand(Gesundheitsdaten.zuletzt, neu, rememberUhrschub()) { lies() }
    if (s == null) {
        Ladekreis()
        return
    }
    WasserInhalt(s, Instant.now(), { bearbeite = it }, oeffneTrend)
    bearbeite?.let { b -> Bearbeitungsblatt(b, onDismiss = { bearbeite = null }) { bearbeite = null; neu++ } }
}

/**
 * Die Seite Water aus einem fertigen Stand - ohne Laden, damit ein Bildtest
 * sie mit Beispieldaten zeichnen kann. [jetzt] bestimmt, welcher Tag heute ist.
 */
@Composable
internal fun WasserInhalt(s: GesundheitsStand, jetzt: Instant, bearbeite: (Bearbeitung) -> Unit, oeffneTrend: () -> Unit) {
    val context = LocalContext.current
    val heute = jetzt.atZone(ZoneId.systemDefault()).toLocalDate()
    // Ein Ziel von 0 ml waere keines - dann wie ohne Ziel, statt durch null zu teilen.
    val zielMl = s.wasserZiel?.ml?.takeIf { it > 0 }
    Seite {
        Kasten {
            Column(Modifier.fillMaxWidth().padding(INNEN), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Dieselbe Zahl wie die Zeile Water auf Health - auf eine Stelle, das Glas darunter genau.
                Hauptwert(
                    Formate.liter(s.wasserMl), zielMl?.let { stringResource(R.string.ks_w_von_liter, Formate.liter(it)) } ?: "l",
                    groesse = 52, einheitGroesse = 22,
                )
                if (zielMl != null) Fortschrittsbalken(s.wasserMl.toFloat() / zielMl, Farben.wasser.flaeche())
                // Gezaehlt wie auf der Uhr: nur ihre Glaeser. Die Liter oben umfassen alles Wasser des Tages.
                Unterzeile(pluralStringResource(R.plurals.ks_w_glaeser, s.glaeser.size, s.glaeser.size))
            }
        }
        // Alles Wasser von heute, auch das anderer Apps - es zaehlt in der Menge oben mit.
        val glaeser = s.glasEintraege.sortedBy { it.zeit }
        if (glaeser.isNotEmpty()) Kasten {
            Kopf(stringResource(R.string.ks_h_heute))
            val glas = stringResource(R.string.ks_d_glas)
            val wasser = stringResource(R.string.ks_d_wasser)
            glaeser.forEachIndexed { i, g ->
                if (i > 0) Trenner(EINTRAG_EINZUG)
                Eintragszeile(
                    if (g.eigen) glas else wasser, Formate.uhrzeit(context, g.zeit), Formate.Angabe(Formate.literFein(g.ml), "l"),
                    zeichen = { Tonkreis(Zeichen.wasser, Farben.wasser, groesse = EINTRAG_ZEICHEN, bild = 18.dp) },
                    // Nur Boulders eigene Glaeser: was eine andere App eintrug, darf Boulder nicht loeschen.
                    bearbeitung = if (g.eigen) Bearbeitung.Glas(glas, g.zeit, g.id) else null,
                    bearbeite = bearbeite,
                )
            }
            Spacer(Modifier.height(4.dp))
        }
        if (s.wasserWoche.isNotEmpty()) Wasserwoche(s.wasserWoche, zielMl, heute)
        Trendzeile(oeffneTrend)
    }
}

/** Der Balken unter der Menge: die leere Spur, darauf der Anteil am Ziel - voll ab dem Ziel. */
@Composable
private fun Fortschrittsbalken(anteil: Float, farbe: Color) {
    val form = RoundedCornerShape(6.dp)
    Box(Modifier.fillMaxWidth().height(12.dp).background(Farben.spur(), form)) {
        if (anteil > 0f) Box(Modifier.fillMaxWidth(anteil.coerceIn(0f, 1f)).fillMaxHeight().background(farbe, form))
    }
}

/**
 * Eine Zeile fuer ein Glas oder Getraenk von heute (Water, Stack): Zeichen,
 * Name, rechts Uhrzeit und Menge. Ein langer Druck oeffnet mit [bearbeitung]
 * das bestehende Aendern/Loeschen, wie im Tagesband - ohne bleibt die Zeile still.
 */
@Composable
internal fun Eintragszeile(
    titel: String,
    zeit: String,
    wert: Formate.Angabe?,
    zeichen: @Composable () -> Unit,
    bearbeitung: Bearbeitung?,
    bearbeite: (Bearbeitung) -> Unit,
) {
    val label = stringResource(R.string.ks_bk_bearbeiten)
    Zeile(
        titel, zeichen = zeichen, minHoehe = 52.dp, chevron = false,
        // Aussen, damit die Mindesthoehe des langen Drucks greift (siehe langDruck).
        modifier = if (bearbeitung != null) Modifier.langDruck(label, 52.dp) { bearbeite(bearbeitung) } else Modifier,
        rechts = {
            Text(zeit, fontSize = 14.sp, lineHeight = 20.sp, color = Farben.neutral(), maxLines = 1, softWrap = false)
            // Gleich breit in jeder Zeile: so stehen die Mengen buendig untereinander.
            Text(
                wert?.let { zahlMitEinheit(it, groesse = 18) } ?: AnnotatedString(""), Modifier.widthIn(min = 64.dp),
                textAlign = TextAlign.End, maxLines = 1, softWrap = false,
            )
        },
    )
}

/**
 * Die letzten sieben Tage als Saeulen an der durchgezogenen Ziellinie, ihr
 * Wert rechts ueber der Linie (Achse rechts). Heute in der Wasserfarbe,
 * fruehere Tage blasser - blau, wenn sie das Ziel erreichten, sonst grau.
 * DAS ZIEL IST DAS VON HEUTE: fruehere Ziele merkt sich Boulder nicht, und
 * so misst die Linie dasselbe wie "of 2.5 l" oben.
 */
@Composable
private fun Wasserwoche(woche: List<Pair<LocalDate, Int>>, zielMl: Int?, heute: LocalDate) {
    val heuteFarbe = Farben.wasser.flaeche()
    val erreichtFarbe = heuteFarbe.aufKarte(0.6f)
    val darunterFarbe = Farben.neutral().aufKarte(0.25f)
    val linie = Farben.wasser.ton()
    val grau = Farben.neutral()
    val messer = rememberTextMeasurer()
    val stil = MaterialTheme.typography.labelSmall.copy(color = linie, fontWeight = FontWeight.Normal)
    val titel = stringResource(R.string.ks_w_sieben_tage)
    val zielText = zielMl?.let { Formate.Angabe(Formate.liter(it), "l").text }
    val erreicht = zielMl?.let { Stackrechnung.zielErreicht(woche, it) }
    // Fuer TalkBack die Woche in einem Satz, mit dem Ziel vorweg.
    val satz = (listOfNotNull(titel, zielText?.let { stringResource(R.string.ks_h_ziel_zahl, it) }).joinToString(", ")) + ": " +
        woche.joinToString(", ") { (tag, ml) -> Formate.wochentagLang(tag.dayOfWeek) + " " + Formate.Angabe(Formate.liter(ml), "l").text }
    Kasten {
        Kopf(titel, urteil = erreicht?.let { stringResource(R.string.ks_w_ziel_erreicht, it) })
        Column(Modifier.fillMaxWidth().padding(start = INNEN, end = INNEN, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Mit der Schrift hoeher: der Name der Ziellinie steht im Bild und soll die Saeulen nicht verdraengen.
            Canvas(Modifier.fillMaxWidth().height(hoeheMitSchrift(WOCHE_HOEHE)).bildBeschreibung(satz)) {
                val spalte = size.width / woche.size
                val breite = minOf(26.dp.toPx(), spalte - 6.dp.toPx())
                val meiste = woche.maxOf { it.second }.coerceAtLeast(1)
                // Das Ziel steht 70 dp hoch; nur wer mehr trank, als darueber Platz hat, drueckt die Skala.
                val jeMl = if (zielMl != null) minOf(ZIEL_HOEHE.toPx() / zielMl, size.height / meiste) else size.height * 0.9f / meiste
                woche.forEachIndexed { i, (tag, ml) ->
                    val h = ml * jeMl
                    if (h <= 0f) return@forEachIndexed
                    val farbe = when {
                        tag == heute -> heuteFarbe
                        zielMl != null && ml >= zielMl -> erreichtFarbe
                        else -> darunterFarbe
                    }
                    drawRoundRect(farbe, Offset(spalte * (i + 0.5f) - breite / 2, size.height - h), Size(breite, h), CornerRadius(4.dp.toPx()))
                }
                if (zielMl != null && zielText != null) {
                    val y = size.height - zielMl * jeMl
                    drawLine(linie, Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
                    // Der Wert rechts ueber der Linie; passt er nicht, darunter.
                    val m = messer.measure(zielText, stil)
                    val oben = (y - 3.dp.toPx() - m.size.height).takeIf { it >= 0f } ?: (y + 3.dp.toPx())
                    drawText(m, topLeft = Offset(size.width - m.size.width, oben))
                }
            }
            Row(Modifier.fillMaxWidth()) {
                woche.forEach { (tag, _) ->
                    Text(
                        Formate.wochentagKurz(tag.dayOfWeek), Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                        // Normal statt des Medium von labelSmall: sonst hebt sich heute kaum ab.
                        fontWeight = if (tag == heute) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (tag == heute) MaterialTheme.colorScheme.onSurface else grau,
                    )
                }
            }
        }
    }
}
