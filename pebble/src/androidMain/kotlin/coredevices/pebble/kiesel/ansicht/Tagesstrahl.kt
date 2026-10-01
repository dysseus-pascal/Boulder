package coredevices.pebble.kiesel.ansicht

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coredevices.pebble.R
import coredevices.pebble.kiesel.GesundheitsStand
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.Nacht
import coredevices.pebble.kiesel.Praeparatlage
import coredevices.pebble.kiesel.SCHRITTZIEL
import coredevices.pebble.kiesel.Schlafurteil
import coredevices.pebble.kiesel.Tageszeit
import coredevices.pebble.kiesel.TrainingEintrag
import coredevices.pebble.kiesel.Vergleich
import coredevices.pebble.kiesel.praeparatzeile
import coredevices.pebble.kiesel.pulsFrisch
import coredevices.pebble.kiesel.ruhepulsUrteil
import coredevices.pebble.kiesel.schlafurteil
import coredevices.pebble.kiesel.tageszeit
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Die Health-Seite nach Konzept F: oben ein Satz zum Tag (aus C), darunter
 * der Tag als Zeitstrahl nur zum Ansehen (aus D), dann die Zeilen fuer Herz,
 * Wasser, Praeparate und Koffein (aus B), zuletzt die Schritte der Woche.
 */

// Breit genug fuer "11:22 AM" in einer Zeile.
private val SPALTE_ZEIT = 62.dp
private val SPALTE_STRAHL = 22.dp

private fun liter(ml: Int): String = if (ml == 0) "0" else Gesundheitsrechnung.literKurz(ml)

private fun zahl(n: Long): String = String.format(Locale.getDefault(), "%,d", n)

/** Akkufarbe nach Stand: gruen ab 50 %, gelb ab 20 %, darunter rot. */
@Composable
private fun akkuFarbe(prozent: Int): Color {
    val t = toene()
    return when {
        prozent >= 50 -> t.bewegung
        prozent >= 20 -> t.warnung
        else -> Color(0xFFF2867A)
    }
}

/**
 * Eine Vorlage mit %1$s, %2$s ... einsetzen und die eingesetzten Werte
 * hervorheben - so stehen die Zahlen im Satz heraus, und jede Sprache behaelt
 * ihre eigene Wortfolge.
 */
private fun AnnotatedString.Builder.hervor(vorlage: String, vararg werte: String, farbe: Color) {
    val muster = Regex("%(\\d+)\\\$[sd]")
    var i = 0
    muster.findAll(vorlage).forEach { m ->
        append(vorlage.substring(i, m.range.first))
        val wert = werte.getOrNull(m.groupValues[1].toInt() - 1).orEmpty()
        withStyle(SpanStyle(color = farbe, fontWeight = FontWeight.Medium)) { append(wert) }
        i = m.range.last + 1
    }
    append(vorlage.substring(i))
}

/** Oben: ein Gruss und ein Satz zum Tag - mit dem Uhrenakku, der zur Akkuansicht fuehrt. */
@Composable
internal fun Tagessatz(s: GesundheitsStand, akkuJetzt: Int?, akkuTage: Int?, oeffneAkku: () -> Unit) {
    val hell = MaterialTheme.colorScheme.onSurface
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    val gruss = stringResource(
        when (Gesundheitsrechnung.tageszeit(LocalTime.now())) {
            Tageszeit.MORGEN -> R.string.ks_f_morgen
            Tageszeit.TAG -> R.string.ks_f_tag
            Tageszeit.ABEND -> R.string.ks_f_abend
        }
    )
    val n = s.nacht
    val urteilWort = n?.let {
        stringResource(
            when (Gesundheitsrechnung.schlafurteil(it.schlafMinuten)) {
                Schlafurteil.AUSGERUHT -> R.string.ks_f_ausgeruht
                Schlafurteil.KURZ -> R.string.ks_h_urteil_kurz
                Schlafurteil.SEHR_KURZ -> R.string.ks_h_urteil_sehr_kurz
            }
        )
    }
    val ruhe = n?.let { Gesundheitsrechnung.ruhepulsUrteil(it.ruhepuls, s.ruhepulsWoche) }
    val vSchlaf = stringResource(R.string.ks_f_schlaf)
    val vRuhe = when (ruhe?.urteil) {
        Vergleich.WIE_IMMER -> stringResource(R.string.ks_f_ruhe_ok)
        Vergleich.HOEHER -> stringResource(R.string.ks_f_ruhe_hoch, ruhe.abstand)
        Vergleich.TIEFER -> stringResource(R.string.ks_f_ruhe_tief, ruhe.abstand)
        null -> null
    }
    val schritteWert = pluralStringResource(R.plurals.ks_f_schritte_wert, s.schritte.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), zahl(s.schritte))
    val vSchritte = stringResource(if (n != null) R.string.ks_f_schritte else R.string.ks_f_schritte_allein)
    val platz = stringResource(R.string.ks_s_platz_n)
    val offen = s.supplemente?.takeIf { s.mitSupCycle }?.let { stand ->
        val z = Gesundheitsrechnung.praeparatzeile(stand.heute { String.format(platz, it) })
        if (z.lage == Praeparatlage.OFFEN || z.lage == Praeparatlage.FAELLIG) z.namen.firstOrNull() else null
    }
    val vOffen = stringResource(R.string.ks_f_offen)
    val vAkkuVor = stringResource(R.string.ks_f_akku_vor)
    val vAkkuNach = akkuTage?.let { pluralStringResource(R.plurals.ks_f_akku_tage, it, it) } ?: "."
    val akkuTon = akkuJetzt?.let { akkuFarbe(it) }

    val text = buildAnnotatedString {
        if (n != null && urteilWort != null) {
            hervor(vSchlaf, urteilWort, stundenMinuten(n.schlafMinuten) + " h", farbe = hell)
            if (vRuhe != null) append(vRuhe)
        }
        hervor(vSchritte, schritteWert, farbe = hell)
        if (offen != null) hervor(vOffen, offen, farbe = hell)
        append(".")
        if (akkuJetzt != null && akkuTon != null) {
            append(" $vAkkuVor ")
            // Symbol und Zahl sind ein Link: ein Tipp oeffnet die Akkuansicht.
            withLink(LinkAnnotation.Clickable("akku", TextLinkStyles(SpanStyle(color = akkuTon, fontWeight = FontWeight.Medium))) { oeffneAkku() }) {
                appendInlineContent("akkusymbol", "[akku]")
                append("$akkuJetzt %")
            }
            append(vAkkuNach)
        }
    }
    // Das Symbol so hoch wie die Schrift und auf ihrer Mitte - es soll im Satz stehen, nicht daneben.
    val symbol = mapOf(
        "akkusymbol" to InlineTextContent(Placeholder(1.2.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
            Akkusymbol(akkuJetzt ?: 0, akkuTon ?: grau)
        }
    )
    Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(gruss, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
        Text(text, inlineContent = symbol, fontSize = 18.sp, lineHeight = 26.sp, color = grau)
    }
}

/** Eine kleine Batterie, gefuellt nach Stand - in der Farbe des Akkustands. */
@Composable
private fun Akkusymbol(prozent: Int, farbe: Color) {
    Canvas(Modifier.fillMaxSize().padding(end = 3.dp)) {
        val strich = size.height * 0.1f
        val w = size.width * 0.84f
        val h = size.height * 0.56f
        val oben = (size.height - h) / 2
        drawRoundRect(farbe, Offset(strich / 2, oben), Size(w, h), CornerRadius(h * 0.22f), style = Stroke(strich))
        drawRect(farbe, Offset(w + strich / 2, oben + h * 0.3f), Size(strich * 1.4f, h * 0.4f))
        val innen = (w - strich * 2.4f) * prozent.coerceIn(0, 100) / 100f
        drawRect(farbe, Offset(strich * 1.7f, oben + strich * 1.2f), Size(innen, h - strich * 2.4f))
    }
}

/** Ein Ereignis des Tages auf dem Strahl. */
private data class Ereignis(val zeit: Instant, val farbe: Color, val titel: String, val unter: String?)

/**
 * Der Tag als Zeitstrahl, nur zum Ansehen: oben die Nacht, dann was heute war,
 * dann das Jetzt, blass darunter die Schlafenszeit. Getraenke und Praeparate
 * nur mit ihren Uhr-Apps (UhrApps), wie ueberall.
 */
@Composable
internal fun Tagesstrahl(s: GesundheitsStand, training: TrainingEintrag?, wege: Gesundheitswege) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val heute = LocalDate.now()
    val jetzt = Instant.now()
    val glas = stringResource(R.string.ks_d_glas)
    val heuteLiter = stringResource(R.string.ks_d_heute_liter, liter(s.wasserMl))
    val ereignisse = buildList {
        if (training != null && training.sitzung.startTime.atZone(zone).toLocalDate() == heute) {
            val unter = listOfNotNull(if (training.meter > 0) kmText(training.meter) else null, dauerText(training.minuten)).joinToString(" · ")
            add(Ereignis(training.sitzung.startTime, training.sport.ton(), trainingsName(training), unter))
        }
        if (s.mitDrinktervall) {
            val letztes = s.glaeser.maxOrNull()
            // Beim letzten Glas der Stand des Tages - sonst wiederholte er sich.
            s.glaeser.forEach { g -> add(Ereignis(g, FLAECHE_WASSER, glas, if (g == letztes) heuteLiter else null)) }
            s.eintraege.filter { !it.praeparat }.forEach { e ->
                add(Ereignis(e.zeit, FLAECHE_KOFFEIN, e.name, e.koffeinMg?.let { "${it.roundToInt()} mg" }))
            }
        }
        if (s.mitSupCycle) {
            // Was SupCycle in einem Zug abhakte, steht als eine Zeile.
            s.eintraege.filter { it.praeparat }.groupBy { it.zeit.epochSecond / 60 }.values.forEach { gruppe ->
                add(Ereignis(gruppe.first().zeit, FLAECHE_PRAEPARAT, gruppe.joinToString(", ") { it.name }, null))
            }
        }
    }.sortedBy { it.zeit }
    val bett = Gesundheitsrechnung.bettzeit(s.nacht?.einschlafen, jetzt, zone)?.takeIf { it.isAfter(jetzt) }
    val hinweis = herzHinweis(s)
    val bettKoffein = if (bett != null && s.mitDrinktervall) Gesundheitsrechnung.koffeinAktiv(s.koffein, bett).roundToInt() else 0

    Kasten {
        Kopf(stringResource(R.string.ks_h_heute_bisher))
        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
            s.nacht?.let { n -> Nachtblock(n, hinweis) { wege.nacht(n.beginnUtc) } }
            ereignisse.forEach { e -> Strahlzeile(uhrzeit(context, e.zeit), e.farbe, e.titel, e.unter) }
            Jetztblock(s, jetzt, weiter = bett != null)
            if (bett != null) {
                Bettzeile(uhrzeit(context, bett), if (bettKoffein > 0) stringResource(R.string.ks_f_koffein_dann, bettKoffein) else null)
            }
        }
    }
}

/** Die Zeitspalte und der Strahl links, der Inhalt rechts - eine Zeile des Strahls. */
@Composable
private fun Strahl(
    zeit: @Composable () -> Unit,
    strahl: @Composable () -> Unit,
    onClick: (() -> Unit)? = null,
    inhalt: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Box(Modifier.width(SPALTE_ZEIT).fillMaxHeight()) { zeit() }
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
private fun Strahlzeile(zeit: String, farbe: Color, titel: String, unter: String?) {
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    val grund = MaterialTheme.colorScheme.surfaceContainer
    Strahl(
        zeit = { Zeittext(zeit, grau) },
        strahl = {
            Linie()
            Box(Modifier.offset(5.dp, 5.dp).size(12.dp).border(BorderStroke(3.dp, grund), CircleShape).background(farbe, CircleShape))
        },
    ) {
        Column(Modifier.padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(titel, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (unter != null) Text(unter, fontSize = 13.sp, lineHeight = 18.sp, color = grau)
        }
    }
}

/** Das Jetzt: Puls und Bewegung - Getraenke und Praeparate stehen in der Liste darunter. */
@Composable
private fun Jetztblock(s: GesundheitsStand, jetzt: Instant, weiter: Boolean) {
    val context = LocalContext.current
    val gruen = MaterialTheme.colorScheme.primary
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    val grund = MaterialTheme.colorScheme.surfaceContainer
    Strahl(
        zeit = { Zeittext(uhrzeit(context, jetzt), gruen, fett = true, oben = 3.dp) },
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
            Text(stringResource(R.string.ks_d_jetzt), fontSize = 12.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = gruen)
            val letzter = s.puls.maxByOrNull { it.first }
            if (letzter != null && Gesundheitsrechnung.pulsFrisch(letzter.first, jetzt.epochSecond)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${letzter.second}", fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.ks_d_puls), Modifier.padding(bottom = 4.dp), fontSize = 14.sp, color = grau)
                }
            }
            Text(stringResource(R.string.ks_d_bewegt, kmText(s.meter), s.aktivMinuten), fontSize = 14.sp, lineHeight = 19.sp)
        }
    }
}

/** Blass unter dem Jetzt: wann die Nacht beginnt, wenn sie wie die letzte wird. */
@Composable
private fun Bettzeile(zeit: String, unter: String?) {
    val blass = MaterialTheme.colorScheme.outline
    val grund = MaterialTheme.colorScheme.surfaceContainer
    Strahl(
        zeit = { Zeittext(zeit, blass) },
        strahl = { Box(Modifier.offset(5.dp, 5.dp).size(12.dp).border(2.dp, blass, CircleShape).background(grund, CircleShape)) },
    ) {
        Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.ks_f_bettzeit), fontSize = 15.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (unter != null) Text(unter, fontSize = 13.sp, lineHeight = 18.sp, color = blass)
        }
    }
}

/** Oben: die Nacht, eingeschlafen oben, aufgewacht unten - ein Tipp oeffnet die Schlafansicht. */
@Composable
private fun Nachtblock(n: Nacht, hinweis: String?, oeffne: () -> Unit) {
    val context = LocalContext.current
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    Strahl(
        zeit = {
            Column(Modifier.fillMaxHeight().padding(top = 2.dp, bottom = 18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Zeittext(uhrzeit(context, n.einschlafen), grau, oben = 0.dp)
                Zeittext(uhrzeit(context, n.aufwachen), grau, oben = 0.dp)
            }
        },
        strahl = {
            Box(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 18.dp).width(6.dp).fillMaxHeight().background(PHASENFARBEN[2], RoundedCornerShape(3.dp)))
        },
        onClick = oeffne,
    ) {
        Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ks_d_eingeschlafen), fontSize = 15.sp, lineHeight = 20.sp)
            if (n.mitPhasen) {
                val phasen = listOf(3 to R.string.ks_h_rem, 1 to R.string.ks_h_leicht, 2 to R.string.ks_h_tief)
                Anteilsbalken(phasen.map { (p, _) -> n.phasen[p] to PHASENFARBEN[p] })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    phasen.forEach { (p, name) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(Modifier.size(8.dp).background(PHASENFARBEN[p], CircleShape))
                            Text(stringResource(name) + " " + stundenMinuten(n.phasen[p]), fontSize = 12.sp, lineHeight = 16.sp, color = grau)
                        }
                    }
                }
            }
            if (n.ruhepuls != null && n.hrv != null) {
                Text(stringResource(R.string.ks_d_ruhe_hrv, n.ruhepuls, n.hrv) + (hinweis?.let { " · $it" } ?: ""), fontSize = 13.sp, lineHeight = 18.sp, color = grau)
            }
            // Ohne Abschnitte waere "durchgeschlafen" eine Behauptung aus Unwissen.
            val durch = n.abschnitte.isNotEmpty() && n.phasen[0] < 10
            Text(stringResource(if (durch) R.string.ks_f_aufgewacht_durch else R.string.ks_d_aufgewacht), fontSize = 15.sp, lineHeight = 20.sp)
        }
    }
}

/** Herz, Wasser, Praeparate, Koffein als Zeilen - jede fuehrt eine Ebene tiefer. */
@Composable
internal fun Koerperliste(
    s: GesundheitsStand,
    wege: Gesundheitswege,
    zeigeKoffein: () -> Unit,
    zeigePraeparate: () -> Unit,
) {
    val context = LocalContext.current
    val t = toene()
    val jetzt = Instant.now()
    val zone = ZoneId.systemDefault()
    val zeilen = buildList<@Composable () -> Unit> {
        add {
            val letzter = s.puls.maxByOrNull { it.first }
            val ruhe = s.nacht?.ruhepuls
            val frisch = letzter != null && Gesundheitsrechnung.pulsFrisch(letzter.first, jetzt.epochSecond)
            val urteil = s.nacht?.let { Gesundheitsrechnung.ruhepulsUrteil(it.ruhepuls, s.ruhepulsWoche) }?.urteil
            val wann = if (frisch) stringResource(R.string.ks_h_puls_jetzt) else letzter?.let { zeitpunkt(context, Instant.ofEpochSecond(it.first)) }
            val ruheText = ruhe?.let {
                stringResource(R.string.ks_d_ruhe, it) + if (urteil == Vergleich.WIE_IMMER) ", " + stringResource(R.string.ks_f_wie_immer) else ""
            }
            val unter = listOfNotNull(wann, ruheText).joinToString(" · ").ifEmpty { stringResource(R.string.ks_h_kein_puls_kurz) }
            Zeile(
                stringResource(R.string.ks_d_herz), unter, letzter?.second?.toString(), if (letzter != null) "bpm" else null,
                zeichen = { Tonkreis(Zeichen.herz, FLAECHE_HERZ, t.herz) }, onClick = wege.herz,
            )
        }
        if (s.mitDrinktervall) add {
            val letztes = s.glaeser.maxOrNull()
            val unter = if (letztes == null) stringResource(R.string.ks_h_kein_glas)
            else pluralStringResource(R.plurals.ks_f_glaeser, s.glaeser.size, s.glaeser.size, uhrzeit(context, letztes))
            Zeile(
                stringResource(R.string.ks_d_wasser), unter, liter(s.wasserMl), "l",
                zeichen = { Tonkreis(Zeichen.wasser, FLAECHE_WASSER, t.wasser) },
            ) { wege.trend("ernaehrung") }
        }
        val stand = s.supplemente?.takeIf { s.mitSupCycle }
        if (stand != null) add {
            val platz = stringResource(R.string.ks_s_platz_n)
            val z = Gesundheitsrechnung.praeparatzeile(stand.heute { String.format(platz, it) })
            val unter = when (z.lage) {
                Praeparatlage.ALLE -> stringResource(R.string.ks_h_p_alle)
                Praeparatlage.KEINE -> stringResource(R.string.ks_h_p_keine)
                else -> z.namen.firstOrNull()?.let { stringResource(R.string.ks_d_noch_offen, it) }
            }
            Zeile(
                stringResource(R.string.ks_h_praeparate_n), unter, "${z.genommen}", stringResource(R.string.ks_h_von_n, z.faellig),
                zeichen = { Tonkreis(Zeichen.kapsel, FLAECHE_PRAEPARAT, t.bewegung, 0.18f) },
                // Die Warnfarbe nur fuer das, was nach dem ersten Haken liegen blieb.
                unterFarbe = if (z.lage == Praeparatlage.OFFEN) t.warnung else null,
                onClick = zeigePraeparate,
            )
        }
        if (s.mitDrinktervall) add {
            val heute = LocalDate.now()
            val heuteMg = s.koffein.filter { it.first.atZone(zone).toLocalDate() == heute }.sumOf { it.second }.roundToInt()
            val aktiv = Gesundheitsrechnung.koffeinAktiv(s.koffein, jetzt).roundToInt()
            val bett = Gesundheitsrechnung.bettzeit(s.nacht?.einschlafen, jetzt, zone)
            val dann = bett?.let { Gesundheitsrechnung.koffeinAktiv(s.koffein, it).roundToInt() }
            val unter = if (heuteMg <= 0 && aktiv <= 0) stringResource(R.string.ks_h_koffein_keins)
            else listOfNotNull(
                stringResource(R.string.ks_f_koffein_heute, heuteMg),
                dann?.takeIf { it > 0 }?.let { stringResource(R.string.ks_f_koffein_bett, it) },
            ).joinToString(" · ")
            Zeile(
                stringResource(R.string.ks_h_koffein_n), unter, "$aktiv", "mg",
                zeichen = { Tonkreis(Zeichen.tasse, FLAECHE_KOFFEIN, t.koffein, 0.30f) }, onClick = zeigeKoffein,
            )
        }
    }
    Kasten {
        zeilen.forEachIndexed { i, z ->
            if (i > 0) Trenner()
            z()
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** Schritte der Woche als Saeulen, das Ziel gestrichelt. */
@Composable
internal fun Schrittwoche(s: GesundheitsStand, trend: () -> Unit) {
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    val leer = MaterialTheme.colorScheme.surfaceVariant
    val heute = LocalDate.now()
    Kasten {
        Kopf(stringResource(R.string.ks_d_schritte_woche), "· " + stringResource(R.string.ks_h_ziel_zahl, zahl(SCHRITTZIEL)), stringResource(R.string.ks_h_trends), trend)
        Row(Modifier.fillMaxWidth().clickable(onClick = trend).padding(start = 10.dp, end = 10.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val spitze = maxOf(SCHRITTZIEL, s.wocheSchritte.maxOfOrNull { it.second } ?: 0L).toFloat()
            s.wocheSchritte.forEach { (tag, schritte) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (schritte > 0) String.format(Locale.getDefault(), "%.1fk", schritte / 1000.0) else "", fontSize = 11.sp, lineHeight = 14.sp, color = grau, maxLines = 1)
                    val gruen = if (schritte >= SCHRITTZIEL) FLAECHE_BEWEGUNG else FLAECHE_BEWEGUNG.copy(alpha = 0.75f)
                    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
                        drawRoundRect(leer, Offset.Zero, size, CornerRadius(4.dp.toPx()))
                        val h = size.height * (schritte / spitze)
                        if (h > 0) drawRoundRect(gruen, Offset(0f, size.height - h), Size(size.width, h), CornerRadius(4.dp.toPx()))
                        val ziel = size.height * (1 - SCHRITTZIEL / spitze)
                        drawLine(grau, Offset(0f, ziel), Offset(size.width, ziel), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())))
                    }
                    Text(
                        tag.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2), fontSize = 12.sp, lineHeight = 16.sp,
                        fontWeight = if (tag == heute) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (tag == heute) MaterialTheme.colorScheme.onSurface else grau,
                    )
                }
            }
        }
    }
}
