package coredevices.pebble.kiesel.ansicht

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle as Textstil
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coredevices.pebble.R
import coredevices.pebble.kiesel.GesundheitsStand
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.Nacht
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.Tagesstrahlrechnung
import coredevices.pebble.kiesel.TrainingEintrag
import coredevices.pebble.kiesel.syncAlt
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Die Breite der Zeitspalte: gemessen an der breitesten Uhrzeit im Format
 * des Telefons und in seiner Schriftgroesse. Fest auf "11:22 AM" ausgelegt
 * verschenkte sie bei 24-Stunden-Zeit ein Viertel der Zeile.
 */
private val LocalZeitBreite = compositionLocalOf { 62.dp }

@Composable
private fun zeitBreiteMessen(): Dp {
    val context = LocalContext.current
    val messer = rememberTextMeasurer()
    val dichte = LocalDensity.current
    val stil = Textstil(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    // Zwei Kandidaten fuer die breiteste Zeit: zweistellige Stunde am Vor- und Nachmittag.
    val proben = listOf(LocalTime.of(10, 58), LocalTime.of(22, 58)).map { t -> Formate.uhrzeit(context, t) }
    val breite = proben.maxOf { messer.measure(it, stil).size.width }
    return with(dichte) { breite.toDp() } + 6.dp
}
private val SPALTE_STRAHL = 22.dp

/** Ein Ereignis aus [Tagesstrahlrechnung], wie die Zeile es zeigt: Farbe, Titel, Menge und was ein langer Druck bearbeitet. */
private data class Strahlpunkt(val zeit: Instant, val farbe: Color, val titel: String, val unter: String?, val bearbeitung: Bearbeitung? = null)

/**
 * Der Tag als Zeitstrahl, nur zum Ansehen: oben die Nacht, dann was heute war,
 * dann das Jetzt, blass darunter die Schlafenszeit. Getraenke und Praeparate
 * nur mit ihren Uhr-Apps (UhrApps), wie ueberall.
 */
@Composable
internal fun Tagesstrahl(s: GesundheitsStand, durchgeschlafen: Boolean, trainings: List<TrainingEintrag>, wege: Gesundheitswege, geaendert: () -> Unit) {
    var offen by remember { mutableStateOf<Bearbeitung?>(null) }
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val heute = LocalDate.now()
    val jetzt = Instant.now()
    val glas = stringResource(R.string.ks_d_glas)
    val wasser = stringResource(R.string.ks_d_wasser)
    val imperial = einheitenImperial()
    // Das Schema einmal gelesen: die Liste unten baut ausserhalb von Compose.
    val dunkel = Farben.dunkel()
    // Was auf den Strahl kommt und in welcher Reihenfolge, rechnet Tagesstrahlrechnung - hier nur Farbe und Worte.
    val ereignisse = Tagesstrahlrechnung.ereignisse(s, trainings, heute, zone).map { e ->
        when (e) {
            is Tagesstrahlrechnung.Ereignis.Training -> {
                val training = e.training
                // Die Strecke wie in Sport (streckeVon): ein Schwimmen bleibt auch mit "Imperial Units" in Metern.
                val unter = listOfNotNull(if (training.meter > 0) streckeVon(training, imperial).text else null, Formate.dauer(training.minuten)).joinToString(" · ")
                Strahlpunkt(e.zeit, Farben.sport(training.sport).tonIm(dunkel), trainingsName(training), unter)
            }
            is Tagesstrahlrechnung.Ereignis.Wasser -> {
                // Ein Glas ist nur, was Drinktervall eintrug; Wasser anderer Apps steht mit seiner Menge da.
                // Die Liter des Tages stehen in der Zeile "Water" darunter - hier beim letzten Glas noch einmal waeren sie doppelt.
                val g = e.glas
                val titel = if (g.eigen) glas else wasser
                val unter = if (g.eigen) null else Formate.menge(g.ml.toLong(), "ml").text
                Strahlpunkt(g.zeit, Farben.wasser.flaecheIm(dunkel), titel, unter, if (g.eigen) Bearbeitung.Glas(titel, g.zeit, g.id) else null)
            }
            is Tagesstrahlrechnung.Ereignis.Getraenk -> {
                val t = e.eintrag
                Strahlpunkt(t.zeit, Farben.koffein.flaecheIm(dunkel), t.name, t.koffeinMg?.let { Formate.menge(it, "mg").text }, if (t.eigen) Bearbeitung.Getraenk(t.name, t.zeit, t.id) else null)
            }
            is Tagesstrahlrechnung.Ereignis.Praeparate -> {
                val bearbeitung = if (e.eigen) Bearbeitung.Praeparate(e.titel, e.zeit, e.eintraege.map { it.id }, e.eintraege.map { it.kennung }) else null
                Strahlpunkt(e.zeit, Farben.praeparat.flaecheIm(dunkel), e.titel, null, bearbeitung)
            }
        }
    }
    val bett = Gesundheitsrechnung.bettzeit(s.nacht?.einschlafen, jetzt, zone)?.takeIf { it.isAfter(jetzt) }
    val hinweis = herzHinweis(s)
    val bettKoffein = if (bett != null && s.mitDrinktervall) Gesundheitsrechnung.koffeinAktiv(s.koffein, bett).roundToInt() else 0

    Kasten {
        Kopf(stringResource(R.string.ks_h_heute_bisher))
        CompositionLocalProvider(LocalZeitBreite provides zeitBreiteMessen()) {
        Column(Modifier.padding(start = INNEN, end = INNEN, bottom = 10.dp)) {
            s.nacht?.let { n -> Nachtblock(n, durchgeschlafen, hinweis) { wege.nacht(n.beginnUtc) } }
            // Ohne Nacht von heute der Weg zur juengsten - sonst waeren fruehere Naechte und Sleep trends unerreichbar.
            if (s.nacht == null) s.juengsteNacht?.let { b -> KeineNacht { wege.nacht(b) } }
            ereignisse.forEach { e ->
                Strahlzeile(Formate.uhrzeit(context, e.zeit), e.farbe, e.titel, e.unter, e.bearbeitung?.let { b -> { offen = b } })
            }
            Jetztblock(s, jetzt, weiter = bett != null, imperial = imperial)
            if (bett != null) {
                Bettzeile(Formate.uhrzeit(context, bett), s.nacht != null, if (bettKoffein > 0) stringResource(R.string.ks_f_koffein_dann, bettKoffein) else null)
            }
        }
        }
    }
    offen?.let { b -> Bearbeitungsblatt(b, onDismiss = { offen = null }) { offen = null; geaendert() } }
}

/** Die Zeitspalte und der Strahl links, der Inhalt rechts - eine Zeile des Strahls. */
@Composable
private fun Strahl(
    zeit: @Composable () -> Unit,
    strahl: @Composable () -> Unit,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    inhalt: @Composable () -> Unit,
) {
    // [modifier] aussen, vor der IntrinsicSize-Hoehe: nur dort kann eine Mindesthoehe (langDruck) die Zeile heben.
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Box(Modifier.width(LocalZeitBreite.current).fillMaxHeight()) { zeit() }
        Box(Modifier.width(SPALTE_STRAHL).fillMaxHeight()) { strahl() }
        Box(Modifier.weight(1f).padding(start = 6.dp)) { inhalt() }
    }
}

@Composable
private fun Linie(oben: Dp = 0.dp, gestrichelt: Boolean = false) {
    val farbe = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxHeight().width(SPALTE_STRAHL).padding(top = oben)) {
        val x = 11.dp.toPx()
        drawLine(
            farbe, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx(),
            pathEffect = if (gestrichelt) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())) else null,
        )
    }
}

@Composable
private fun Zeittext(text: String, farbe: Color, fett: Boolean = false, oben: Dp = 2.dp) {
    Text(
        text, Modifier.padding(top = oben), fontSize = 13.sp, color = farbe, maxLines = 1, softWrap = false,
        fontWeight = if (fett) FontWeight.SemiBold else FontWeight.Normal,
    )
}

@Composable
private fun Strahlzeile(zeit: String, farbe: Color, titel: String, unter: String?, lang: (() -> Unit)?) {
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    // Der Ring in der Kartenfarbe: mit surfaceContainer lag um jeden Punkt ein hellerer Hof.
    val grund = Farben.karte()
    Strahl(
        zeit = { Zeittext(zeit, grau) },
        strahl = {
            Linie()
            Box(Modifier.offset(5.dp, 5.dp).size(12.dp).border(BorderStroke(3.dp, grund), CircleShape).background(farbe, CircleShape))
        },
        // Nur ein langer Druck: der Strahl ist zum Ansehen, ein Tipp tut nichts. Mit
        // 48 dp und "Edit" fuer TalkBack - das Beispiel fuer langDruck im Baukasten.
        modifier = if (lang != null) Modifier.langDruck(stringResource(R.string.ks_bk_bearbeiten), aktion = lang) else Modifier,
    ) {
        Column(Modifier.padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(titel, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (unter != null) Unterzeile(unter)
        }
    }
}

/**
 * Das Jetzt: nur die Bewegung. Kein Puls - die Uhr misst nicht auf Abruf, der
 * letzte Wert war nur ein Zufallsmoment; der Tagespuls steht in der Liste.
 * Liegt der letzte Minutenwert der Uhr laenger zurueck, steht dabei, bis wann
 * die Zahlen reichen - sonst sah ein Stand von vor Stunden wie der jetzige aus.
 */
@Composable
private fun Jetztblock(s: GesundheitsStand, jetzt: Instant, weiter: Boolean, imperial: Boolean) {
    val context = LocalContext.current
    val gruen = MaterialTheme.colorScheme.primary
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    val grund = Farben.karte()
    Strahl(
        zeit = { Zeittext(Formate.uhrzeit(context, jetzt), gruen, fett = true, oben = 3.dp) },
        strahl = {
            // Bis zum Jetzt durchgezogen, danach gestrichelt: was kommt, ist noch nicht.
            Box(Modifier.height(5.dp)) { Linie() }
            if (weiter) Linie(oben = 20.dp, gestrichelt = true)
            Box(Modifier.offset(2.dp, 3.dp).size(18.dp).border(2.dp, gruen, CircleShape).background(grund, CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.size(8.dp).background(gruen, CircleShape))
            }
        },
    ) {
        Column(Modifier.padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = gruen)) { append(stringResource(R.string.ks_d_jetzt)) }
                    val letzte = s.letzteDaten
                    if (letzte != null && Gesundheitsrechnung.syncAlt(letzte, jetzt)) {
                        withStyle(SpanStyle(color = grau)) { append(" " + stringResource(R.string.ks_h_synchron, Formate.zeitpunkt(context, letzte, klein = true, heuteOhneTag = true))) }
                    }
                },
                fontSize = 12.sp, lineHeight = 22.sp,
            )
            // Die Strecke in der Einheit aus Gravels Schalter "Imperial Units", wie ueberall.
            Text(stringResource(R.string.ks_d_bewegt, Formate.distanz(s.meter, imperial).text, s.aktivMinuten), fontSize = 14.sp, lineHeight = 19.sp)
        }
    }
}

/**
 * Blass unter dem Jetzt: wann die Nacht beginnt, wenn sie wie die letzte
 * wird. Ohne letzte Nacht ist die Zeit nur die Vorgabe - dann ohne "like
 * last night".
 */
@Composable
private fun Bettzeile(zeit: String, wieLetzteNacht: Boolean, unter: String?) {
    val blass = MaterialTheme.colorScheme.outline
    val grund = Farben.karte()
    Strahl(
        zeit = { Zeittext(zeit, blass) },
        strahl = { Box(Modifier.offset(5.dp, 5.dp).size(12.dp).border(2.dp, blass, CircleShape).background(grund, CircleShape)) },
    ) {
        Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(if (wieLetzteNacht) R.string.ks_f_bettzeit else R.string.ks_f_bettzeit_vorgabe),
                fontSize = 15.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (unter != null) Unterzeile(unter, blass)
        }
    }
}

/**
 * Ohne Nacht von heute, oben im Strahl: eine blasse Zeile zur juengsten
 * Nacht. Sonst fuehrte nur der Nachtblock zur Schlafansicht, und ohne ihn
 * waren fruehere Naechte und Sleep trends nicht zu erreichen.
 */
@Composable
private fun KeineNacht(oeffne: () -> Unit) {
    val blass = MaterialTheme.colorScheme.outline
    val grund = Farben.karte()
    Strahl(
        zeit = {},
        strahl = {
            Linie()
            Box(Modifier.offset(5.dp, 5.dp).size(12.dp).border(2.dp, blass, CircleShape).background(grund, CircleShape))
        },
        onClick = oeffne,
        // Ein Ziel mit 48 dp: die Zeile selbst war mit einer Textzeile nur 34 dp hoch.
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Row(Modifier.padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ks_d_keine_nacht), fontSize = 15.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Zeichen.weiter, null, Modifier.size(16.dp), tint = blass)
        }
    }
}

/**
 * Oben: die Nacht, eingeschlafen oben, aufgewacht unten - ein Tipp oeffnet
 * die Schlafansicht. [durchgeschlafen] aus dem Tagessatz: eine getestete
 * Schwelle fuer Satz und Strahl.
 */
@Composable
private fun Nachtblock(n: Nacht, durchgeschlafen: Boolean, hinweis: String?, oeffne: () -> Unit) {
    val context = LocalContext.current
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    Strahl(
        zeit = {
            Column(Modifier.fillMaxHeight().padding(top = 2.dp, bottom = 18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Zeittext(Formate.uhrzeit(context, n.einschlafen), grau, oben = 0.dp)
                Zeittext(Formate.uhrzeit(context, n.aufwachen), grau, oben = 0.dp)
            }
        },
        strahl = {
            Box(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 18.dp).width(6.dp).fillMaxHeight().background(Farben.phasen[Farben.TIEF], RoundedCornerShape(3.dp)))
        },
        onClick = oeffne,
    ) {
        Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Das Chevron sagt, dass ein Tipp die Nacht oeffnet - wie bei jeder Zeile mit Ziel.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ks_d_eingeschlafen), Modifier.weight(1f), fontSize = 15.sp, lineHeight = 20.sp)
                Icon(Zeichen.weiter, null, Modifier.size(16.dp), tint = grau)
            }
            // Das Hypnogramm wie in der Schlafansicht - der Verlauf sagt mehr als Anteile. Stumm: Zeiten
            // und Legende nennen dieselben Zahlen, und als Teil der klickbaren Zeile verdeckte sein Satz sie.
            if (n.abschnitte.isNotEmpty()) Hypnogramm(n, hoehe = 96.dp, stumm = true)
            if (n.mitPhasen) {
                // In der Reihenfolge der Bahnen darueber. Wach nur, wenn es vorkommt: dann zeigt das Bild den orangen Block.
                val phasen = PHASEN_VON_OBEN.filter { (p, _) -> p != Farben.WACH || n.phasen[Farben.WACH] > 0 }
                // Die Legende des Baukastens: sie bricht nur zwischen den Eintraegen um, nie mitten in einem.
                // Die Dauer mit Einheit wie in der Schlafansicht: "Deep 1:56" las sich neben Uhrzeiten wie eine.
                Punktlegende(
                    phasen.map { (p, name) -> Legendeneintrag(stringResource(name) + " " + Formate.dauer(n.phasen[p].toLong()), Farben.phasen[p]) },
                    mittig = false,
                )
            }
            // Jeder Wert fuer sich: der Ruhepuls fehlte, wenn die HRV fehlte. Das Urteil zum Ruhepuls steht im Satz oben.
            val werte = when {
                n.ruhepuls != null && n.hrv != null -> stringResource(R.string.ks_d_ruhe_hrv, n.ruhepuls, n.hrv)
                n.ruhepuls != null -> stringResource(R.string.ks_d_nur_ruhe, n.ruhepuls)
                n.hrv != null -> stringResource(R.string.ks_d_nur_hrv, n.hrv)
                else -> null
            }
            if (werte != null) Unterzeile(werte + (hinweis?.takeIf { n.hrv != null }?.let { " · $it" } ?: ""))
            Text(stringResource(if (durchgeschlafen) R.string.ks_f_aufgewacht_durch else R.string.ks_d_aufgewacht), fontSize = 15.sp, lineHeight = 20.sp)
        }
    }
}
