package coredevices.pebble.kiesel.ansicht

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Akteneintraege
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.GesundheitWidget
import coredevices.pebble.kiesel.GesundheitsStand
import coredevices.pebble.kiesel.Gesundheitsdaten
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.Stackrechnung
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** Das Zeichen vor einem Praeparat: gefuellt mit Haken (genommen) oder ein Ring (offen). */
private val PRAEPARAT_ZEICHEN = 28.dp

/** Der Trenner zwischen Praeparaten reicht bis zum Text, wie [EINTRAG_EINZUG]. */
private val PRAEPARAT_EINZUG = INNEN + PRAEPARAT_ZEICHEN + 14.dp

/** Die Kurve ohne die Uhrzeiten darunter. */
private val KURVE_HOEHE = 120.dp

/** Die Spitze der Kurve steht bei neun Zehnteln der Hoehe - darueber bleibt Luft fuer den Strich. */
private const val KURVE_KOPF = 0.9

/**
 * So viel Koffein fuellt das Bild mindestens: ein entkoffeinierter Kaffee
 * (2-5 mg) stuende sonst so hoch wie ein doppelter Espresso.
 */
private const val KURVE_MIN_MG = 30.0

/**
 * Die Seite Stack: die Praeparate von heute (Haken und Zeit, oder ein Ring
 * mit der Planzeit) und das Koffein - wie viel jetzt wirkt, die Kurve bis
 * zur Schlafenszeit und die Getraenke von heute. Sie ersetzt die Blaetter
 * Praeparate und "Add caffeine" von Health; "Add caffeine" (Koffein von
 * Hand, Eintraege von gestern) steht als Zeile in der Karte Caffeine. Hier
 * wird geladen; zu sehen ist [StackInhalt].
 */
@Composable
internal fun StackAnsicht(oeffneTrend: () -> Unit) {
    val context = LocalContext.current
    val lies = rememberGesundheitslader()
    val scope = rememberCoroutineScope()
    var neu by remember { mutableIntStateOf(0) }
    var koffeinBlatt by remember { mutableStateOf(false) }
    var bearbeite by remember { mutableStateOf<Bearbeitung?>(null) }
    // Neue Daten von der Uhr oder ein eigener Eintrag: Haken, Kurve und Liste stimmen dann nicht mehr.
    val s = rememberStand(Gesundheitsdaten.zuletzt, neu, rememberUhrschub()) { lies() }
    if (s == null) {
        Ladekreis()
        return
    }
    StackInhalt(s, Instant.now(), { bearbeite = it }, { koffeinBlatt = true }, oeffneTrend)

    if (koffeinBlatt) {
        KoffeinBlatt(s, onDismiss = { koffeinBlatt = false }, bearbeite = { koffeinBlatt = false; bearbeite = it }) { art ->
            koffeinBlatt = false
            val fehler = context.getString(R.string.ks_h_koffein_fehler)
            scope.launch {
                // Ohne Rueckmeldung glaubte man den Kaffee eingetragen, und die Zahl stuende still.
                if (!Akteneintraege.trinkeKoffein(context, art)) Toast.makeText(context, fehler, Toast.LENGTH_LONG).show()
                GesundheitWidget.stosseAn(context)
                neu++
            }
        }
    }
    bearbeite?.let { b -> Bearbeitungsblatt(b, onDismiss = { bearbeite = null }) { bearbeite = null; neu++ } }
}

/**
 * Die Seite Stack aus einem fertigen Stand - ohne Laden, damit ein Bildtest
 * sie mit Beispieldaten zeichnen kann. Was keine Daten hat, entfaellt wie
 * auf Health: ohne SupCycle die Praeparate, ohne Drinktervall das Koffein.
 * [koffeinDazu] oeffnet "Add caffeine".
 */
@Composable
internal fun StackInhalt(
    s: GesundheitsStand,
    jetzt: Instant,
    bearbeite: (Bearbeitung) -> Unit,
    koffeinDazu: () -> Unit,
    oeffneTrend: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val platz = stringResource(R.string.ks_s_platz_n)
    val praeparate = s.supplemente?.takeIf { s.mitSupCycle }?.let { st ->
        Stackrechnung.praeparate(st, s.eintraege, jetzt, zone) { String.format(platz, it) }
    }
    Seite {
        if (praeparate != null) Praeparatkarte(praeparate, bearbeite)
        if (s.mitDrinktervall) Koffeinkarte(s, jetzt, bearbeite, koffeinDazu)
        Trendzeile(oeffneTrend)
    }
}

/** Die Praeparate von heute in der Reihenfolge des Plans, oben rechts wie viele davon genommen sind. */
@Composable
private fun Praeparatkarte(liste: List<Stackrechnung.Praeparat>, bearbeite: (Bearbeitung) -> Unit) {
    Kasten {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Kopf(stringResource(R.string.ks_h_praeparate)) }
            // Dieselbe Zaehlung wie "3 of 4 supplements" in der Zeile Stack auf Health.
            if (liste.isNotEmpty()) Text(
                zahlMitEinheit(Formate.zahl(liste.count { it.genommen }.toLong()), stringResource(R.string.ks_h_von_n, liste.size), groesse = 18),
                Modifier.padding(end = INNEN), maxLines = 1, softWrap = false,
            )
        }
        if (liste.isEmpty()) Leerzeile(stringResource(R.string.ks_h_p_keine))
        liste.forEachIndexed { i, p ->
            if (i > 0) Trenner(PRAEPARAT_EINZUG)
            Praeparatzeile(p, bearbeite)
        }
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * Ein Praeparat: genommen mit Haken und Einnahmezeit, offen mit Ring und
 * Planzeit. BERNSTEIN NUR FUER VERSAEUMTES - wie im Satz, in der Zeile
 * Stack und im Widget ([Gesundheitsrechnung.praeparatVersaeumt]): was erst
 * am Abend ansteht, ist mittags nicht "still open". Darunter die Kurwoche
 * aus SupCycle, falls es eine Kur ist.
 */
@Composable
private fun Praeparatzeile(p: Stackrechnung.Praeparat, bearbeite: (Bearbeitung) -> Unit) {
    val context = LocalContext.current
    val warnung = Farben.warnung.ton()
    val kur = p.kur?.let { stringResource(R.string.ks_st_kurwoche, it.woche, it.von) }
    val unter = listOfNotNull(if (p.versaeumt) stringResource(R.string.ks_st_offen) else null, kur).joinToString(" · ").ifEmpty { null }
    val zeit = if (p.genommen) p.um?.let { Formate.uhrzeit(context, it) } else p.plan?.let { Formate.uhrzeit(context, it) }
    // Fuer TalkBack, was Haken und Ring zeigen, in Worten wie auf Health.
    val stand = when {
        p.genommen -> stringResource(R.string.ks_st_genommen, p.name)
        p.versaeumt -> stringResource(R.string.ks_h_p_offen_1, p.name)
        else -> stringResource(R.string.ks_h_p_faellig_1, p.name)
    }
    val b = p.eintrag?.let { Bearbeitung.Praeparate(it.name, it.zeit, listOf(it.id), listOf(it.kennung)) }
    val label = stringResource(R.string.ks_bk_bearbeiten)
    val ring = MaterialTheme.colorScheme.outline
    Zeile(
        p.name, unter, unterFarbe = if (p.versaeumt) warnung else null, minHoehe = 56.dp, chevron = false,
        beschreibung = listOfNotNull(stand, zeit, kur).joinToString(", "),
        // Wie im Tagesband: ein langer Druck auf eine eigene Einnahme oeffnet das Loeschen.
        modifier = if (b != null) Modifier.langDruck(label, 56.dp) { bearbeite(b) } else Modifier,
        zeichen = {
            if (p.genommen) Tonkreis(Zeichen.haken, Farben.praeparat.flaeche(), Farben.karte(), alpha = 1f, groesse = PRAEPARAT_ZEICHEN, bild = 16.dp)
            else Box(Modifier.size(PRAEPARAT_ZEICHEN).border(2.dp, if (p.versaeumt) warnung else ring, CircleShape))
        },
        rechts = if (zeit == null) null else {
            { Text(zeit, fontSize = 15.sp, lineHeight = 20.sp, color = if (p.versaeumt) warnung else Farben.neutral(), maxLines = 1, softWrap = false) }
        },
    )
}

/**
 * Das Koffein: oben was jetzt wirkt, rechts im Kopf die Summe von heute,
 * darunter die Kurve und die Getraenke von heute, am Ende "Add caffeine".
 * Alle Werte aus dem Koffeinmodell der App, wie die Zeile Stack auf Health.
 */
@Composable
private fun Koffeinkarte(s: GesundheitsStand, jetzt: Instant, bearbeite: (Bearbeitung) -> Unit, koffeinDazu: () -> Unit) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val kurve = remember(s.koffein, s.nacht?.einschlafen, jetzt) { Stackrechnung.koffeinkurve(s.koffein, jetzt, s.nacht?.einschlafen, zone) }
    val aktiv = Gesundheitsrechnung.koffeinAktiv(s.koffein, jetzt).roundToInt()
    val heute = Gesundheitsrechnung.koffeinHeute(s.koffein, jetzt.atZone(zone).toLocalDate(), zone).roundToInt()
    val bettMg = kurve.bett?.let { Gesundheitsrechnung.koffeinAktiv(s.koffein, it).roundToInt() }
    val getraenke = s.eintraege.filter { !it.praeparat }.sortedBy { it.zeit }
    Kasten {
        Kopf(stringResource(R.string.ks_h_koffein_n), urteil = stringResource(R.string.ks_st_heute_mg, Formate.zahl(heute.toLong())))
        Column(Modifier.fillMaxWidth().padding(start = INNEN, end = INNEN, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Hauptwert(Formate.zahl(aktiv.toLong()), stringResource(R.string.ks_st_aktiv_jetzt), einheitGroesse = 14)
            Koffeinbild(kurve, aktiv, bettMg)
        }
        getraenke.forEach { e ->
            Trenner(if (e === getraenke.first()) INNEN else EINTRAG_EINZUG)
            Eintragszeile(
                e.name, Formate.uhrzeit(context, e.zeit), e.koffeinMg?.let { Formate.menge(it, "mg") },
                zeichen = { Tonkreis(Zeichen.tasse, Farben.koffein, 0.30f, groesse = EINTRAG_ZEICHEN, bild = 18.dp) },
                // Nur Boulders eigene Getraenke: was eine andere App eintrug, darf Boulder nicht aendern.
                bearbeitung = if (e.eigen) Bearbeitung.Getraenk(e.name, e.zeit, e.id) else null,
                bearbeite = bearbeite,
            )
        }
        Trenner(if (getraenke.isEmpty()) INNEN else EINTRAG_EINZUG)
        Zeile(
            stringResource(R.string.ks_h_koffein_dazu), minHoehe = 52.dp,
            zeichen = { Tonkreis(Zeichen.plus, Farben.koffein, 0.30f, groesse = EINTRAG_ZEICHEN, bild = 18.dp) },
            onClick = koffeinDazu,
        )
    }
}

/**
 * Die Koffeinkurve von morgens bis zur Schlafenszeit: bis jetzt durchgezogen
 * mit Flaeche, danach gestrichelt - was noch kommt, ist gerechnet, nicht
 * getrunken. Die Jetzt-Linie gruen, die Schlafenszeit gepunktet mit ihrem
 * Wert oben rechts; darunter die Uhrzeiten alle vier Stunden und die von jetzt.
 */
@Composable
private fun Koffeinbild(k: Stackrechnung.Koffeinkurve, aktiv: Int, bettMg: Int?) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val farben = MaterialTheme.colorScheme
    val linie = Farben.koffein.ton()
    val flaeche = Farben.koffein.flaeche().copy(alpha = 0.30f)
    val messer = rememberTextMeasurer()
    val stil = MaterialTheme.typography.labelSmall.copy(color = Farben.neutral(), fontWeight = FontWeight.Normal)
    val jetztStil = stil.copy(color = farben.primary, fontWeight = FontWeight.SemiBold)
    val bettStil = TextStyle(fontSize = 12.sp, lineHeight = 15.sp, color = Farben.neutral(), textAlign = TextAlign.End)
    // "≈" wie ueberall in der App (Formate.ungefaehr): die Halbwertszeit ist ein Mittel, kein Messwert.
    val bettWert = bettMg?.let { Formate.ungefaehr(Formate.zahl(it.toLong())) + " mg" }
    val bettUm = k.bett?.let { stringResource(R.string.ks_st_bett_um, Formate.uhrzeit(context, it)) }
    val jetztText = Formate.uhrzeit(context, k.jetzt)
    // Die Uhrzeiten alle vier Stunden ab dem Anfang, in der Ortszeit.
    val stunden = remember(k.von, k.bis) {
        generateSequence(k.von.atZone(zone)) { it.plusHours(4) }.takeWhile { !it.toInstant().isAfter(k.bis) }.map { it.toInstant() }.toList()
    }.map { it to Formate.uhrzeit(context, it) }
    val beschreibung = listOfNotNull(
        stringResource(R.string.ks_h_koffein_n),
        stringResource(R.string.ks_h_zeitraum, Formate.uhrzeit(context, k.von), Formate.uhrzeit(context, k.bis)),
        Formate.zahl(aktiv.toLong()) + " " + stringResource(R.string.ks_st_aktiv_jetzt),
        if (bettWert != null && bettUm != null) "$bettWert $bettUm" else null,
    ).joinToString(", ")
    Canvas(Modifier.fillMaxWidth().height(hoeheMitSchrift(KURVE_HOEHE + 20.dp)).bildBeschreibung(beschreibung)) {
        val fuss = fussraum(messer, stil, luft = 4.dp)
        val hoehe = size.height - fuss
        val spanne = (k.bis.epochSecond - k.von.epochSecond).toFloat()
        fun x(t: Instant) = size.width * (t.epochSecond - k.von.epochSecond) / spanne
        val skala = maxOf(k.spitze / KURVE_KOPF, KURVE_MIN_MG)
        fun y(mg: Double) = hoehe - (hoehe * mg / skala).toFloat()
        fun pfad(punkte: List<Pair<Instant, Double>>) = Path().apply {
            punkte.forEachIndexed { i, (t, mg) -> if (i == 0) moveTo(x(t), y(mg)) else lineTo(x(t), y(mg)) }
        }
        val bisher = k.punkte.filter { !it.first.isAfter(k.jetzt) }
        val spaeter = k.punkte.filter { !it.first.isBefore(k.jetzt) }
        val jx = x(k.jetzt)
        // Bis jetzt die Flaeche unter der Kurve - getrunken, nicht gerechnet.
        if (bisher.isNotEmpty()) {
            val unter = pfad(bisher).apply {
                lineTo(jx, hoehe)
                lineTo(x(bisher.first().first), hoehe)
                close()
            }
            drawPath(unter, flaeche)
        }
        val strich = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        if (bisher.size > 1) drawPath(pfad(bisher), linie, style = strich)
        if (spaeter.size > 1) drawPath(
            pfad(spaeter), linie,
            style = Stroke(2.dp.toPx(), join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))),
        )
        // Die Schlafenszeit gepunktet - runde Enden auf Strichen der Laenge null sind Punkte.
        k.bett?.let { b ->
            val bx = x(b)
            drawLine(
                farben.outline, Offset(bx, 0f), Offset(bx, hoehe), 2.dp.toPx(), cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(0f, 5.dp.toPx())),
            )
        }
        drawLine(farben.primary, Offset(jx, 0f), Offset(jx, hoehe), 2.dp.toPx())
        // Der Wert zur Schlafenszeit oben rechts, zweizeilig wie im Entwurf - links neben ihrer
        // Linie, und steht das Jetzt schon dort, links neben ihm: keine Linie geht durch die Schrift.
        if (bettWert != null && bettUm != null && k.bett != null) {
            val m = messer.measure("$bettWert\n$bettUm", bettStil)
            val luft = 6.dp.toPx()
            var rechts = minOf(size.width - 4.dp.toPx(), x(k.bett) - luft)
            if (jx > rechts - m.size.width - luft && jx < rechts + luft) rechts = jx - luft
            drawText(m, topLeft = Offset((rechts - m.size.width).coerceAtLeast(0f), 2.dp.toPx()))
        }
        // Die Uhrzeiten unter dem Bild; die von jetzt gruen, und eine Stunde, die sie verdeckte, entfaellt.
        val unten = hoehe + 4.dp.toPx()
        val jm = messer.measure(jetztText, jetztStil)
        val jl = (jx - jm.size.width / 2f).coerceIn(0f, (size.width - jm.size.width).coerceAtLeast(0f))
        val luft = 6.dp.toPx()
        stunden.forEach { (t, text) ->
            val m = messer.measure(text, stil)
            val l = (x(t) - m.size.width / 2f).coerceIn(0f, (size.width - m.size.width).coerceAtLeast(0f))
            if (l + m.size.width + luft > jl && l < jl + jm.size.width + luft) return@forEach
            drawText(m, topLeft = Offset(l, unten))
        }
        drawText(jm, topLeft = Offset(jl, unten))
    }
}
