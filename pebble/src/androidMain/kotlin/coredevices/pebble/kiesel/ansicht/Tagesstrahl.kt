package coredevices.pebble.kiesel.ansicht

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import coredevices.pebble.kiesel.GesundheitWidget
import coredevices.pebble.kiesel.Gesundheitsdaten
import coredevices.pebble.kiesel.Kaffeesorten
import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle as Textstil
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Akkurechnung
import coredevices.pebble.kiesel.GesundheitsStand
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.Nacht
import coredevices.pebble.kiesel.Praeparatlage
import coredevices.pebble.kiesel.Auswertung
import coredevices.pebble.kiesel.SCHRITTZIEL
import coredevices.pebble.kiesel.SupCycle
import coredevices.pebble.kiesel.Schlafurteil
import coredevices.pebble.kiesel.Tageszeit
import coredevices.pebble.kiesel.TrainingEintrag
import coredevices.pebble.kiesel.Vergleich
import coredevices.pebble.kiesel.praeparatzeile
import coredevices.pebble.kiesel.pulsFrisch
import coredevices.pebble.kiesel.ruhepulsUrteil
import coredevices.pebble.kiesel.schlafurteil
import coredevices.pebble.kiesel.syncAlt
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
    val proben = listOf(LocalTime.of(10, 58), LocalTime.of(22, 58)).map { t ->
        uhrzeit(context, t.atDate(LocalDate.now()).atZone(ZoneId.systemDefault()).toInstant())
    }
    val breite = proben.maxOf { messer.measure(it, stil).size.width }
    return with(dichte) { breite.toDp() } + 6.dp
}
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
internal fun Tagessatz(s: GesundheitsStand, akkuJetzt: Int?, akkuRest: Akkurechnung.Restzeit?, oeffneAkku: () -> Unit) {
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
                Schlafurteil.AUSGERUHT -> R.string.ks_h_urteil_ausgeruht
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
        // Nur Versaeumtes gehoert in den Satz - was erst spaeter ansteht, steht in der Liste.
        if (z.lage == Praeparatlage.OFFEN) z.namen.firstOrNull() else null
    }
    val vHaerte = when (Gesundheitsrechnung.tagesurteil(s.tagesherz, LocalDate.now())?.haerte) {
        Vergleich.HOEHER -> stringResource(R.string.ks_f_tag_haerter)
        Vergleich.TIEFER -> stringResource(R.string.ks_f_tag_leichter)
        else -> null
    }
    val vOffen = stringResource(R.string.ks_f_offen)
    val vAkkuVor = stringResource(R.string.ks_f_akku_vor)
    val vAkkuNach = when {
        akkuRest == null -> "."
        akkuRest.stunden -> stringResource(R.string.ks_f_akku_stunden, akkuRest.wert)
        else -> pluralStringResource(R.plurals.ks_f_akku_tage, akkuRest.wert, akkuRest.wert)
    }
    val akkuTon = akkuJetzt?.let { akkuFarbe(it) }

    val text = buildAnnotatedString {
        if (n != null && urteilWort != null) {
            hervor(vSchlaf, urteilWort, stundenMinuten(n.schlafMinuten) + " h", farbe = hell)
            if (vRuhe != null) append(vRuhe)
        }
        hervor(vSchritte, schritteWert, farbe = hell)
        // Ein gewoehnlicher Tag braucht kein Wort - nur die Abweichung der Belastung zaehlt
        if (vHaerte != null) append(vHaerte)
        if (offen != null) hervor(vOffen, offen, farbe = hell)
        append(".")
        if (akkuJetzt != null && akkuTon != null) {
            append(" $vAkkuVor ")
            // Nur die Zahl, in der Farbe des Stands, als Link zur Akkuansicht -
            // ein Symbol davor stach im Satz zu sehr heraus.
            withLink(LinkAnnotation.Clickable("akku", TextLinkStyles(SpanStyle(color = akkuTon, fontWeight = FontWeight.Medium))) { oeffneAkku() }) {
                append("$akkuJetzt%")
            }
            append(vAkkuNach)
        }
    }
    Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(gruss, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
        Text(text, fontSize = 18.sp, lineHeight = 26.sp, color = grau)
    }
}

/** Was ein langer Druck mit einem Ereignis tun kann - nur bei Boulders eigenen Eintraegen. */
internal sealed class Bearbeitung {
    abstract val titel: String
    abstract val zeit: Instant
    data class Getraenk(override val titel: String, override val zeit: Instant, val id: String) : Bearbeitung()
    data class Glas(override val titel: String, override val zeit: Instant, val id: String) : Bearbeitung()
    /** [kennungen]: an ihnen erfaehrt SupCycles Stand, welche Plaetze nicht mehr als genommen zaehlen. */
    data class Praeparate(override val titel: String, override val zeit: Instant, val ids: List<String>, val kennungen: List<String?>) : Bearbeitung()
}

/** Ein Ereignis des Tages auf dem Strahl. */
private data class Ereignis(val zeit: Instant, val farbe: Color, val titel: String, val unter: String?, val bearbeitung: Bearbeitung? = null)

/**
 * Der Tag als Zeitstrahl, nur zum Ansehen: oben die Nacht, dann was heute war,
 * dann das Jetzt, blass darunter die Schlafenszeit. Getraenke und Praeparate
 * nur mit ihren Uhr-Apps (UhrApps), wie ueberall.
 */
@Composable
internal fun Tagesstrahl(s: GesundheitsStand, trainings: List<TrainingEintrag>, wege: Gesundheitswege, geaendert: () -> Unit) {
    var offen by remember { mutableStateOf<Bearbeitung?>(null) }
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val heute = LocalDate.now()
    val jetzt = Instant.now()
    val glas = stringResource(R.string.ks_d_glas)
    val wasser = stringResource(R.string.ks_d_wasser)
    val heuteLiter = stringResource(R.string.ks_d_heute_liter, liter(s.wasserMl))
    val ereignisse = buildList {
        trainings.filter { it.sitzung.startTime.atZone(zone).toLocalDate() == heute }.forEach { training ->
            val unter = listOfNotNull(if (training.meter > 0) kmText(training.meter) else null, dauerText(training.minuten)).joinToString(" · ")
            add(Ereignis(training.sitzung.startTime, training.sport.ton(), trainingsName(training), unter))
        }
        if (s.mitDrinktervall) {
            val letztes = s.glasEintraege.maxOfOrNull { it.zeit }
            // Ein Glas ist nur, was Drinktervall eintrug; Wasser anderer Apps steht mit seiner Menge da.
            s.glasEintraege.forEach { g ->
                val titel = if (g.eigen) glas else wasser
                // Beim letzten Eintrag der Stand des Tages - sonst wiederholte er sich.
                val unter = listOfNotNull(if (g.eigen) null else "${g.ml} ml", if (g.zeit == letztes) heuteLiter else null).joinToString(" · ").ifEmpty { null }
                add(Ereignis(g.zeit, FLAECHE_WASSER, titel, unter, if (g.eigen) Bearbeitung.Glas(titel, g.zeit, g.id) else null))
            }
            s.eintraege.filter { !it.praeparat }.forEach { e ->
                add(Ereignis(e.zeit, FLAECHE_KOFFEIN, e.name, e.koffeinMg?.let { "${it.roundToInt()} mg" }, if (e.eigen) Bearbeitung.Getraenk(e.name, e.zeit, e.id) else null))
            }
        }
        if (s.mitSupCycle) {
            // Was SupCycle in einem Zug abhakte, steht als eine Zeile. Fremde Eintraege
            // derselben Minute stehen daneben - sonst loeschte die Zeile nur einen Teil ihres Titels.
            s.eintraege.filter { it.praeparat }.groupBy { it.zeit.epochSecond / 60 to it.eigen }.values.forEach { gruppe ->
                val titel = gruppe.joinToString(", ") { it.name }
                val zeit = gruppe.first().zeit
                val bearbeitung = if (gruppe.first().eigen) Bearbeitung.Praeparate(titel, zeit, gruppe.map { it.id }, gruppe.map { it.kennung }) else null
                add(Ereignis(zeit, FLAECHE_PRAEPARAT, titel, null, bearbeitung))
            }
        }
    }.sortedBy { it.zeit }
    val bett = Gesundheitsrechnung.bettzeit(s.nacht?.einschlafen, jetzt, zone)?.takeIf { it.isAfter(jetzt) }
    val hinweis = herzHinweis(s)
    val bettKoffein = if (bett != null && s.mitDrinktervall) Gesundheitsrechnung.koffeinAktiv(s.koffein, bett).roundToInt() else 0

    Kasten {
        Kopf(stringResource(R.string.ks_h_heute_bisher))
        CompositionLocalProvider(LocalZeitBreite provides zeitBreiteMessen()) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
            s.nacht?.let { n -> Nachtblock(n, hinweis) { wege.nacht(n.beginnUtc) } }
            // Ohne Nacht von heute der Weg zur juengsten - sonst waeren fruehere Naechte und Sleep trends unerreichbar.
            if (s.nacht == null) s.juengsteNacht?.let { b -> KeineNacht { wege.nacht(b) } }
            ereignisse.forEach { e ->
                Strahlzeile(uhrzeit(context, e.zeit), e.farbe, e.titel, e.unter, e.bearbeitung?.let { b -> { offen = b } })
            }
            Jetztblock(s, jetzt, weiter = bett != null)
            if (bett != null) {
                Bettzeile(uhrzeit(context, bett), s.nacht != null, if (bettKoffein > 0) stringResource(R.string.ks_f_koffein_dann, bettKoffein) else null)
            }
        }
        }
    }
    offen?.let { b -> Bearbeitungsblatt(b, onDismiss = { offen = null }) { offen = null; geaendert() } }
}

/**
 * Das Blatt nach langem Druck: ein Getraenk umstellen oder loeschen, ein Glas
 * oder Praeparate loeschen. Falsch gewaehlt ist auf der Uhr schnell - hier
 * wird es richtig.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Bearbeitungsblatt(b: Bearbeitung, onDismiss: () -> Unit, fertig: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fehler = stringResource(R.string.ks_b_fehler)
    fun ausfuehren(tat: suspend () -> Boolean) {
        scope.launch {
            if (!tat()) Toast.makeText(context, fehler, Toast.LENGTH_LONG).show()
            GesundheitWidget.stosseAn(context)
            fertig()
        }
    }
    // Gleich ganz offen: halb offen lagen Speichern und Loeschen unter dem Rand.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            // Mit dem Tag, wenn es nicht heute war: auch ein Eintrag von gestern laesst sich hier aendern.
            Kopf(b.titel.uppercase(), "· " + zeitpunkt(context, b.zeit))
            val start = remember(b) { (b as? Bearbeitung.Getraenk)?.let { Kaffeesorten.erkenne(context, it.titel) } }
            // Ein eigenes Getraenk der Uhr ("Mate") ist keine der Sorten - es laesst sich nur loeschen, nicht zu Kaffee machen.
            if (b is Bearbeitung.Getraenk && start != null) {
                var sorte by remember(b) { mutableIntStateOf(start.sorte) }
                var milch by remember(b) { mutableStateOf(start.milch) }
                var zucker by remember(b) { mutableStateOf(start.zucker) }
                var koffeinfrei by remember(b) { mutableStateOf(start.koffeinfrei) }
                val sorten = listOf(
                    Kaffeesorten.ESPRESSO to R.string.ks_h_espresso, Kaffeesorten.KAFFEE to R.string.ks_h_kaffee,
                    Kaffeesorten.TEE to R.string.ks_h_tee, Kaffeesorten.ENERGY to R.string.ks_h_energy_drink,
                )
                sorten.forEach { (k, name) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(sorte == k, role = Role.RadioButton) { sorte = k }.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = sorte == k, onClick = null)
                        Text(stringResource(name), Modifier.padding(start = 12.dp), fontSize = 16.sp)
                    }
                }
                Trenner(14.dp)
                // Milch und koffeinfrei zu allem ausser dem Energy-Drink - wie auf der Uhr.
                val mitMilch = sorte != Kaffeesorten.ENERGY
                if (mitMilch) Haken(stringResource(R.string.ks_b_koffeinfrei), koffeinfrei) { koffeinfrei = it }
                if (mitMilch) Haken(stringResource(R.string.ks_b_milch), milch) { milch = it }
                Haken(stringResource(R.string.ks_b_zucker), zucker) { zucker = it }
                val art = Kaffeesorten.art(sorte, milch && mitMilch, zucker, koffeinfrei && mitMilch)
                val p = Kaffeesorten.portion(art)
                if (p != null) Text(
                    stringResource(R.string.ks_b_werte, p.koffeinMg.roundToInt(), p.kcal.roundToInt()),
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Unveraendert gespeichert schriebe die Werte der Sorte ueber die eingetragenen.
                val geaendert = art != Kaffeesorten.art(start.sorte, start.milch, start.zucker, start.koffeinfrei)
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { ausfuehren { Gesundheitsdaten.loesche(context, NutritionRecord::class, listOf(b.id)) } }) {
                        Text(stringResource(R.string.ks_b_loeschen), color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.weight(1f))
                    Button(enabled = geaendert, onClick = { ausfuehren { Gesundheitsdaten.aendereGetraenk(context, b.id, art) } }) {
                        Text(stringResource(R.string.ks_b_speichern))
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            ausfuehren {
                                when (b) {
                                    is Bearbeitung.Glas -> Gesundheitsdaten.loesche(context, HydrationRecord::class, listOf(b.id))
                                    // Die Liste und die Quote zaehlen es dann auch nicht mehr - die Uhr erfaehrt davon nichts.
                                    is Bearbeitung.Praeparate -> Gesundheitsdaten.loesche(context, NutritionRecord::class, b.ids)
                                        .also { if (it) SupCycle.verwirf(context, b.kennungen) }
                                    is Bearbeitung.Getraenk -> Gesundheitsdaten.loesche(context, NutritionRecord::class, listOf(b.id))
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    ) { Text(stringResource(R.string.ks_b_loeschen)) }
                }
            }
        }
    }
}

@Composable
private fun Haken(text: String, an: Boolean, setze: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(an, role = Role.Checkbox, onValueChange = setze).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = an, onCheckedChange = null)
        Text(text, Modifier.padding(start = 12.dp), fontSize = 16.sp)
    }
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
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).then(modifier),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Strahlzeile(zeit: String, farbe: Color, titel: String, unter: String?, lang: (() -> Unit)?) {
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    val grund = MaterialTheme.colorScheme.surfaceContainer
    val haptik = LocalHapticFeedback.current
    Strahl(
        zeit = { Zeittext(zeit, grau) },
        strahl = {
            Linie()
            Box(Modifier.offset(5.dp, 5.dp).size(12.dp).border(BorderStroke(3.dp, grund), CircleShape).background(farbe, CircleShape))
        },
        // Nur ein langer Druck: der Strahl ist zum Ansehen, ein Tipp tut nichts.
        modifier = if (lang != null) Modifier.combinedClickable(onClick = {}, onLongClick = {
            haptik.performHapticFeedback(HapticFeedbackType.LongPress)
            lang()
        }) else Modifier,
    ) {
        Column(Modifier.padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(titel, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (unter != null) Text(unter, fontSize = 13.sp, lineHeight = 18.sp, color = grau)
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
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = gruen)) { append(stringResource(R.string.ks_d_jetzt)) }
                    val letzte = s.letzteDaten
                    if (letzte != null && Gesundheitsrechnung.syncAlt(letzte, jetzt)) {
                        withStyle(SpanStyle(color = grau)) { append(" " + stringResource(R.string.ks_h_synchron, zeitpunkt(context, letzte))) }
                    }
                },
                fontSize = 12.sp, lineHeight = 22.sp,
            )
            Text(stringResource(R.string.ks_d_bewegt, kmText(s.meter), s.aktivMinuten), fontSize = 14.sp, lineHeight = 19.sp)
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
    val grund = MaterialTheme.colorScheme.surfaceContainer
    Strahl(
        zeit = { Zeittext(zeit, blass) },
        strahl = { Box(Modifier.offset(5.dp, 5.dp).size(12.dp).border(2.dp, blass, CircleShape).background(grund, CircleShape)) },
    ) {
        Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(if (wieLetzteNacht) R.string.ks_f_bettzeit else R.string.ks_f_bettzeit_vorgabe),
                fontSize = 15.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (unter != null) Text(unter, fontSize = 13.sp, lineHeight = 18.sp, color = blass)
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
    val grund = MaterialTheme.colorScheme.surfaceContainer
    Strahl(
        zeit = {},
        strahl = {
            Linie()
            Box(Modifier.offset(5.dp, 5.dp).size(12.dp).border(2.dp, blass, CircleShape).background(grund, CircleShape))
        },
        onClick = oeffne,
    ) {
        Row(Modifier.padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ks_d_keine_nacht), fontSize = 15.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Zeichen.weiter, null, Modifier.size(16.dp), tint = blass)
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
            // Das Chevron sagt, dass ein Tipp die Nacht oeffnet - wie bei jeder Zeile mit Ziel.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ks_d_eingeschlafen), Modifier.weight(1f), fontSize = 15.sp, lineHeight = 20.sp)
                Icon(Zeichen.weiter, null, Modifier.size(16.dp), tint = grau)
            }
            // Das Hypnogramm wie in der Schlafansicht - der Verlauf sagt mehr als Anteile.
            if (n.abschnitte.isNotEmpty()) Hypnogramm(n, hoehe = 96.dp)
            if (n.mitPhasen) {
                // Wach mit, wenn es vorkommt: das Bild zeigt den orangen Block, die Legende soll ihn nennen.
                val phasen = listOf(2 to R.string.ks_h_tief, 3 to R.string.ks_h_rem, 1 to R.string.ks_h_leicht) +
                    listOfNotNull((0 to R.string.ks_h_wach).takeIf { n.phasen[0] > 0 })
                // Umbrechen nur zwischen den Eintraegen, nie mitten in einem.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    phasen.forEach { (p, name) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(Modifier.size(8.dp).background(PHASENFARBEN[p], CircleShape))
                            Text(stringResource(name) + " " + stundenMinuten(n.phasen[p]), fontSize = 12.sp, lineHeight = 16.sp, color = grau, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
            // Jeder Wert fuer sich: der Ruhepuls fehlte, wenn die HRV fehlte. Das Urteil zum Ruhepuls steht im Satz oben.
            val werte = when {
                n.ruhepuls != null && n.hrv != null -> stringResource(R.string.ks_d_ruhe_hrv, n.ruhepuls, n.hrv)
                n.ruhepuls != null -> stringResource(R.string.ks_d_nur_ruhe, n.ruhepuls)
                n.hrv != null -> stringResource(R.string.ks_d_nur_hrv, n.hrv)
                else -> null
            }
            if (werte != null) Text(werte + (hinweis?.takeIf { n.hrv != null }?.let { " · $it" } ?: ""), fontSize = 13.sp, lineHeight = 18.sp, color = grau)
            // Ohne Abschnitte waere "durchgeschlafen" eine Behauptung aus Unwissen.
            val durch = n.abschnitte.isNotEmpty() && n.phasen[0] < 10
            Text(stringResource(if (durch) R.string.ks_f_aufgewacht_durch else R.string.ks_d_aufgewacht), fontSize = 15.sp, lineHeight = 20.sp)
        }
    }
}

/** Der wache Tagesschnitt und was dazu zu sagen ist: Vergleich und Belastung. */
internal class Tagespuls(val wachpuls: Int, val teile: List<String>)

/**
 * Der Tagespuls fuer die Herzzeile und oben auf der Herzseite - eine Rechnung
 * fuer beide, damit die angetippte Zahl am Ziel wieder steht. Null ohne
 * wachen Tagesschnitt.
 */
@Composable
internal fun tagespuls(s: GesundheitsStand): Tagespuls? {
    val urteil = Gesundheitsrechnung.tagesurteil(s.tagesherz, LocalDate.now()) ?: return null
    val wach = urteil.heute.wachpuls ?: return null
    // Der Tagesschnitt statt des letzten Werts: der sagt, wie der Tag war
    val vergleich = when {
        urteil.abstand == null -> stringResource(R.string.ks_d_tagespuls)
        urteil.abstand >= 3 -> stringResource(R.string.ks_d_tagespuls_hoch, urteil.abstand)
        urteil.abstand <= -3 -> stringResource(R.string.ks_d_tagespuls_tief, -urteil.abstand)
        else -> stringResource(R.string.ks_d_tagespuls_gleich)
    }
    val last = urteil.heute.belastung.takeIf { it > 0 }?.let { stringResource(R.string.ks_d_belastung, it) }
    return Tagespuls(wach, listOfNotNull(vergleich, last))
}

/**
 * Herz samt Blutdruck, Koerper, Wasser, Praeparate, Koffein als Zeilen. Das
 * Chevron fuehrt zu Detail oder Trend; Eintragen hat sein eigenes "+".
 */
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
            val ruhe = s.nacht?.ruhepuls
            val tag = tagespuls(s)
            val letzter = s.puls.maxByOrNull { it.first }
            // Die Zahl rechts steht nur einmal da, links steht, was sie ist: "Resting
            // 55 · 55 bpm" wiederholte sie. Der Ersatztext nur ohne jeden Puls.
            val (wert, puls) = when {
                tag != null -> tag.wachpuls to tag.teile
                ruhe != null -> ruhe to listOf(stringResource(R.string.ks_d_ruhe))
                letzter != null -> letzter.second to listOf(stringResource(R.string.ks_d_zuletzt, zeitpunkt(context, Instant.ofEpochSecond(letzter.first))))
                else -> null to listOf(stringResource(R.string.ks_h_kein_puls_kurz))
            }
            // Der Blutdruck hinten an, auch neben zwei Pulsteilen - selbst dann bleibt die Zeile auf 412 dp zweizeilig.
            val druck = Gesundheitsrechnung.blutdruckFuerHerzzeile(s.blutdruck, jetzt, zone)
                ?.let { stringResource(R.string.ks_d_blutdruck_kurz, it.oben, it.unten) }
            val unter = (puls + listOfNotNull(druck)).joinToString(" · ")
            Zeile(
                stringResource(R.string.ks_d_herz), unter, wert?.toString(), if (wert != null) "bpm" else null,
                zeichen = { Tonkreis(Zeichen.herz, FLAECHE_HERZ, t.herz) }, onClick = wege.herz,
            )
        }
        // Nur wer sich wiegt, bekommt die Zeile - ohne Waage waere sie immer leer.
        koerperzeile(s, wege.koerper)?.let { add(it) }
        if (s.mitDrinktervall) add {
            // Gezaehlt wie auf der Uhr: nur ihre Glaeser; die Liter umfassen alles Wasser des Tages.
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
            val (eins, zwei, mehr) = if (z.lage == Praeparatlage.OFFEN) Triple(R.string.ks_h_p_offen_1, R.string.ks_h_p_offen_2, R.string.ks_h_p_offen_n)
            else Triple(R.string.ks_h_p_faellig_1, R.string.ks_h_p_faellig_2, R.string.ks_h_p_faellig_n)
            val unter = when {
                z.lage == Praeparatlage.ALLE -> stringResource(R.string.ks_h_p_alle)
                z.lage == Praeparatlage.KEINE -> stringResource(R.string.ks_h_p_keine)
                z.weitere > 0 -> stringResource(mehr, z.namen[0], z.namen[1], z.weitere)
                z.namen.size == 2 -> stringResource(zwei, z.namen[0], z.namen[1])
                else -> z.namen.firstOrNull()?.let { stringResource(eins, it) }
            }
            Zeile(
                stringResource(R.string.ks_h_praeparate_n), unter, "${z.genommen}", stringResource(R.string.ks_h_von_n, z.faellig),
                zeichen = { Tonkreis(Zeichen.kapsel, FLAECHE_PRAEPARAT, t.bewegung, 0.18f) },
                // Die Warnfarbe nur fuer Versaeumtes: was seine Planzeit hinter sich hat
                // (ohne Plan: was nach dem ersten Haken liegen blieb).
                unterFarbe = if (z.lage == Praeparatlage.OFFEN) t.warnung else null,
                onClick = zeigePraeparate,
            )
        }
        if (s.mitDrinktervall) add {
            // Gross die Menge des Tages wie im Widget; was davon noch wirkt, steht benannt darunter.
            val heuteMg = Gesundheitsrechnung.koffeinHeute(s.koffein, LocalDate.now(), zone).roundToInt()
            val aktiv = Gesundheitsrechnung.koffeinAktiv(s.koffein, jetzt).roundToInt()
            val bett = Gesundheitsrechnung.bettzeit(s.nacht?.einschlafen, jetzt, zone)
            val dann = bett?.let { Gesundheitsrechnung.koffeinAktiv(s.koffein, it).roundToInt() }
            val unter = if (aktiv <= 0) stringResource(R.string.ks_h_koffein_keins)
            else listOfNotNull(
                stringResource(R.string.ks_f_koffein_aktiv, aktiv),
                dann?.takeIf { it > 0 }?.let { stringResource(R.string.ks_f_koffein_bett, it) },
            ).joinToString(" · ")
            val dazu = stringResource(R.string.ks_h_koffein_dazu)
            Zeile(
                stringResource(R.string.ks_h_koffein_n), unter, "$heuteMg", "mg",
                zeichen = { Tonkreis(Zeichen.tasse, FLAECHE_KOFFEIN, t.koffein, 0.30f) },
                rechts = {
                    IconButton(onClick = zeigeKoffein, modifier = Modifier.size(48.dp)) {
                        Icon(Zeichen.plus, dazu, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                },
                onClick = { wege.trend("ernaehrung") },
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

/**
 * Schritte der Woche als Saeulen, das Ziel durchgezogen und an der Linie
 * beschriftet ("goal 10k") - wie seit 0.26.0 ueberall: durchgezogen das
 * Ziel, gestrichelt ein Schnitt.
 */
@Composable
internal fun Schrittwoche(s: GesundheitsStand, trend: () -> Unit) {
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    val leer = MaterialTheme.colorScheme.surfaceVariant
    val heute = LocalDate.now()
    val messer = rememberTextMeasurer()
    val stil = MaterialTheme.typography.labelSmall.copy(color = grau)
    val zielText = stringResource(R.string.ks_h_ziel_zahl, Auswertung.tausender(SCHRITTZIEL.toDouble()))
    Kasten {
        Kopf(stringResource(R.string.ks_d_schritte_woche), link = stringResource(R.string.ks_h_trends), onLink = trend)
        Row(Modifier.fillMaxWidth().clickable(onClick = trend).padding(start = 10.dp, end = 10.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val spitze = maxOf(SCHRITTZIEL, s.wocheSchritte.maxOfOrNull { it.second } ?: 0L).toFloat()
            s.wocheSchritte.forEachIndexed { i, (tag, schritte) ->
                val letzte = i == s.wocheSchritte.lastIndex
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (schritte > 0) String.format(Locale.getDefault(), "%.1fk", schritte / 1000.0) else "", fontSize = 11.sp, lineHeight = 14.sp, color = grau, maxLines = 1)
                    val gruen = if (schritte >= SCHRITTZIEL) FLAECHE_BEWEGUNG else FLAECHE_BEWEGUNG.copy(alpha = 0.75f)
                    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
                        drawRoundRect(leer, Offset.Zero, size, CornerRadius(4.dp.toPx()))
                        val h = size.height * (schritte / spitze)
                        if (h > 0) drawRoundRect(gruen, Offset(0f, size.height - h), Size(size.width, h), CornerRadius(4.dp.toPx()))
                        val ziel = size.height * (1 - SCHRITTZIEL / spitze)
                        // Halb in die Luecke zur Nachbarsaeule: so laeuft die Linie durch und liest sich nicht als gestrichelt.
                        drawLine(grau, Offset(-3.dp.toPx(), ziel), Offset(size.width + 3.dp.toPx(), ziel), 1.5.dp.toPx())
                        // Die Beschriftung rechts am Linienende wie in den Trends, ueber der Linie, wenn
                        // sie ins Bild passt, sonst darunter; die letzte Saeule zeichnet zuletzt, also obenauf.
                        if (letzte) {
                            val m = messer.measure(zielText, stil)
                            val abstand = 2.dp.toPx()
                            val oben = (ziel - abstand - m.size.height).takeIf { it >= 0f } ?: (ziel + abstand)
                            drawText(m, topLeft = Offset(size.width + 3.dp.toPx() - m.size.width, oben.coerceAtMost(size.height - m.size.height)))
                        }
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
