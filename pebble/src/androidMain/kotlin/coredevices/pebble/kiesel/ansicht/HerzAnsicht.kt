package coredevices.pebble.kiesel.ansicht

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coredevices.pebble.R
import coredevices.pebble.kiesel.BLUTDRUCK_TAGE
import coredevices.pebble.kiesel.GesundheitsStand
import coredevices.pebble.kiesel.Gesundheitsdaten
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.LERNNAECHTE
import coredevices.pebble.kiesel.pulsFrisch
import coredevices.pebble.kiesel.ruhepulsUrteil
import coredevices.pebble.kiesel.tageZurueck
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.connection.LibPebble
import org.koin.compose.koinInject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

private const val SPO2_WARNUNG = 90

/**
 * "Heart": oben der Tagespuls wie in der Herzzeile, dann der Puls der letzten
 * 24 Stunden und der Blutdruck der Manschette, dazu Ruhepuls und SpO2 der
 * letzten Nacht, die HRV an ihrem Band und was Boulder ueber die frueheren
 * Naechte weiss. Das Pulsband stand frueher auf der Uebersicht; hier hat es
 * Platz und verdraengt nichts.
 */
@Composable
internal fun HerzAnsicht(oeffneNacht: (Long) -> Unit, oeffneTrend: () -> Unit) {
    val context = LocalContext.current
    val api = koinInject<HealthDataApi>()
    val libPebble = koinInject<LibPebble>()
    var stand by remember { mutableStateOf<GesundheitsStand?>(null) }
    var neu by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { neu++ }
    LaunchedEffect(Unit) { libPebble.healthDataUpdated.collect { neu++ } }
    LaunchedEffect(neu) { stand = Gesundheitsdaten.lies(context, api) }
    val s = stand
    if (s == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    // Vor der Liste gerechnet: ein leeres Item bekaeme seinen Abstand trotzdem, und ohne Manschette ist es meist leer.
    val blutdruck = remember(s.blutdruck) { Gesundheitsrechnung.blutdruckstand(s.blutdruck) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Spacer(Modifier.height(2.dp)) }
        item { PulsKarte(s) }
        item { HrvKarte(s) }
        // Nach der HRV: die beiden Verlaeufe ueber Tage stehen beisammen, der Puls des Tages bleibt oben.
        if (blutdruck != null) item { BlutdruckKarte(blutdruck) }
        item { NachtwerteKarte(s, oeffneNacht) }
        item { GewohntKarte(s, oeffneTrend) }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun PulsKarte(s: GesundheitsStand) {
    val context = LocalContext.current
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    val t = toene()
    val jetzt = Instant.now().epochSecond
    val letzter = s.puls.maxByOrNull { it.first }
    val zonenNamen = listOf(R.string.ks_h_zone_leicht, R.string.ks_h_zone_moderat, R.string.ks_h_zone_kraeftig, R.string.ks_h_zone_spitze)
        .map { stringResource(it) }
    Kasten {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (letzter == null) {
                Text(stringResource(R.string.ks_h_kein_puls_kurz), fontSize = 14.sp, color = grau)
                return@Column
            }
            // Wie bei Google Health: oben die Spanne des Tages, dann wo der hoechste Wert lag -
            // aus der Reihe, die das Pulsband zeichnet, damit Satz und Kurve dasselbe zeigen.
            val (tiefster, hoechster) = Gesundheitsrechnung.pulsSpanne(s.puls) ?: (letzter.second to letzter.second)
            val tag = tagespuls(s)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(zahlMitEinheit("$tiefster–$hoechster", "bpm", 30, 16), lineHeight = 36.sp)
                // Was die Herzzeile auf Health zeigt, steht hier wieder: wer sie antippt, findet seine Zahl.
                if (tag != null) Text((listOf("${tag.wachpuls} bpm") + tag.teile).joinToString(" · "), fontSize = 14.sp, lineHeight = 20.sp)
                if (s.pulsZonen.size == 3) {
                    val zone = zonenNamen[Gesundheitsrechnung.pulsZone(hoechster, s.pulsZonen)]
                    // Mit Zeit: im 24-Stunden-Fenster kann die Spitze von gestern Abend sein.
                    val wann = Gesundheitsrechnung.pulsSpitze(s.puls)?.let { zeitpunkt(context, Instant.ofEpochSecond(it.first)) }
                    Text(
                        stringResource(R.string.ks_h_puls_hoch, hoechster, zone.lowercase()) + (wann?.let { " · $it" } ?: ""),
                        fontSize = 14.sp, lineHeight = 20.sp,
                    )
                }
                // "now" nur, solange der Wert frisch ist - eine Stunde alter Puls ist nicht der jetzige.
                val frisch = Gesundheitsrechnung.pulsFrisch(letzter.first, jetzt)
                val wann = Instant.ofEpochSecond(letzter.first)
                Text(
                    if (frisch) stringResource(R.string.ks_h_puls_neu, letzter.second, uhrzeit(context, wann))
                    else stringResource(R.string.ks_h_puls_alt, letzter.second, zeitpunkt(context, wann)),
                    fontSize = 13.sp, lineHeight = 19.sp, color = grau,
                )
            }
            val nacht = s.nacht?.let { it.einschlafen.epochSecond to it.aufwachen.epochSecond }
            // Die Stunden im Format des Systems, wie jede andere Uhrzeit der Seite.
            val stunde = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "H" else "h a", Locale.getDefault())
            Pulsband(
                s.puls, jetzt - 24 * 3600, jetzt, s.pulsZonen, t.zonen, nacht = nacht, hoehe = 200.dp,
                stundeText = { stunde.format(it) },
            )
            Legende(zonenNamen.zip(t.zonen).map { (name, farbe) -> Legendenpunkt(name, farbe) })
        }
    }
}

/**
 * Die HRV der letzten sieben Naechte mit dem persoenlichen Band - was als
 * gewohnt gilt, sieht man so auf einen Blick, statt es aus einem Schnitt
 * herauszulesen.
 */
@Composable
private fun HrvKarte(s: GesundheitsStand) {
    val tage = s.hrvTage
    if (tage.none { it.wert != null }) return
    val t = toene()
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    val letzte = tage.last()
    val band = letzte.band
    Kasten {
        Kopf(stringResource(R.string.ks_h_hrv_woche), stringResource(R.string.ks_h_hrv_woche_zusatz))
        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(zahlMitEinheit(letzte.wert?.toString() ?: "–", letzte.wert?.let { "ms" }, 30, 16), lineHeight = 36.sp)
                val satz = when {
                    letzte.wert == null -> stringResource(R.string.ks_h_hrv_keine)
                    band == null -> stringResource(R.string.ks_h_letzte_nacht)
                    letzte.wert < band.first -> stringResource(R.string.ks_h_hrv_unter_band, band.first, band.second)
                    letzte.wert > band.second -> stringResource(R.string.ks_h_hrv_ueber_band, band.first, band.second)
                    else -> stringResource(R.string.ks_h_hrv_im_band, band.first, band.second)
                }
                Text(satz, fontSize = 13.sp, lineHeight = 19.sp, color = grau)
            }
            HrvWoche(tage, t.imBand, t.ausserBand) { it.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()) }
            if (tage.any { it.band != null }) {
                Legende(
                    listOfNotNull(
                        Legendenpunkt(stringResource(R.string.ks_h_hrv_band), t.imBand.copy(alpha = 0.3f), eckig = true),
                        Legendenpunkt(stringResource(R.string.ks_h_hrv_drin), t.imBand),
                        // Daneben nur, wenn es in der Woche vorkommt - sonst erklaerte die Legende eine Farbe, die fehlt.
                        Legendenpunkt(stringResource(R.string.ks_h_hrv_draussen), t.ausserBand).takeIf { tage.any { it.imBand == false } },
                    ),
                )
            }
        }
    }
}

/** "Today 8:14 AM", "Yesterday 8:14 AM", sonst mit Datum - am Zeilenanfang, darum gross. */
private fun messzeit(context: Context, t: Instant): String =
    when (Gesundheitsrechnung.tageZurueck(t, Instant.now(), ZoneId.systemDefault())) {
        0L -> context.getString(R.string.ks_kp_heute) + " " + uhrzeit(context, t)
        1L -> context.getString(R.string.ks_kp_gestern) + " " + uhrzeit(context, t)
        else -> zeitpunkt(context, t)
    }

/**
 * Der Blutdruck der Manschette: die letzte Sitzung gross, ab drei Sitzungen
 * der Schnitt daneben, darunter jede Sitzung der letzten [BLUTDRUCK_TAGE]
 * Tage. Ohne Messung fehlt die Karte - die Uhr misst keinen Blutdruck.
 */
@Composable
private fun BlutdruckKarte(b: Gesundheitsrechnung.Blutdruckstand) {
    val context = LocalContext.current
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    val jetzt = Instant.now()
    // Das Datum wie unter den Saeulen der Trends.
    val datum = remember { DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()) }
    Kasten {
        Kopf(stringResource(R.string.ks_h_blutdruck_k), stringResource(R.string.ks_kp_tage_zusatz, BLUTDRUCK_TAGE.toInt()))
        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(zahlMitEinheit("${b.letzte.oben}/${b.letzte.unten}", "mmHg", 30, 16), lineHeight = 36.sp)
                Text(
                    listOfNotNull(
                        messzeit(context, b.letzte.zeit),
                        b.schnitt?.let { (oben, unten) -> stringResource(R.string.ks_d_blutdruck_schnitt, oben, unten) },
                    ).joinToString(" · "),
                    fontSize = 13.sp, lineHeight = 19.sp, color = grau,
                )
            }
            Blutdruckbild(b.sitzungen, jetzt.minus(Duration.ofDays(BLUTDRUCK_TAGE)), jetzt, toene().herz, tagText = datum::format)
        }
    }
}

private data class Legendenpunkt(val text: String, val farbe: Color, val eckig: Boolean = false)

/** Farbpunkte mit ihrem Namen unter einem Bild, mittig. */
@Composable
private fun Legende(punkte: List<Legendenpunkt>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        punkte.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(p.farbe, if (p.eckig) RoundedCornerShape(2.dp) else CircleShape))
                Text(p.text, Modifier.padding(start = 6.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Ruhepuls und SpO2 der letzten Nacht - die HRV steht in ihrer Karte darueber
 * und hier nicht noch einmal.
 */
@Composable
private fun NachtwerteKarte(s: GesundheitsStand, oeffneNacht: (Long) -> Unit) {
    val context = LocalContext.current
    val n = s.nacht
    // SpO2 der Nacht, wenn es eine gibt; sonst der letzten 24 Stunden - dann heisst die Karte auch so.
    val spo2 = if (n == null) s.spo2.map { it.second }
    else s.spo2.filter { it.first in n.spanne }.map { it.second }
    if (n == null && spo2.isEmpty()) return
    Kasten {
        if (n != null) {
            Kopf(
                stringResource(R.string.ks_h_letzte_nacht_k),
                stringResource(R.string.ks_h_zeitraum, uhrzeit(context, n.einschlafen), uhrzeit(context, n.aufwachen)),
                stringResource(R.string.ks_h_nachtansicht),
            ) { oeffneNacht(n.beginnUtc) }
            Zeile(
                stringResource(R.string.ks_h_ruhepuls_n),
                if (n.ruhepuls != null) null else stringResource(R.string.ks_h_nicht_gemessen),
                n.ruhepuls?.toString() ?: "–", n.ruhepuls?.let { "bpm" }, minHoehe = 56.dp,
            )
        } else {
            Kopf(stringResource(R.string.ks_h_24h))
        }
        if (spo2.isNotEmpty()) {
            if (n != null) Trenner(14.dp)
            val unter = spo2.count { it < SPO2_WARNUNG }
            Zeile(
                stringResource(R.string.ks_h_spo2_n),
                if (unter == 0) stringResource(R.string.ks_h_spo2_keins, spo2.min())
                else pluralStringResource(R.plurals.ks_h_spo2_unter, unter, spo2.min(), unter),
                "${spo2.average().roundToInt()}", "%", minHoehe = 56.dp,
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * Der Ruhepuls gegen die frueheren Naechte: das Urteil und der Schnitt, aus
 * dem es kommt - dieselbe Nachtzahl fuer beides. Die HRV hat ihr Band in der
 * HRV-Karte; hier stand sie ein drittes Mal, mit einem zweiten "usual" (dem
 * Schnitt von sieben Naechten) und einer Nachtzahl, die zum Ruhepuls gehoerte.
 */
@Composable
private fun GewohntKarte(s: GesundheitsStand, oeffneTrend: () -> Unit) {
    val farben = MaterialTheme.colorScheme
    val ruhe = Gesundheitsrechnung.ruhepulsUrteil(s.nacht?.ruhepuls, s.ruhepulsWoche)
    Kasten {
        Kopf(stringResource(R.string.ks_h_gewohnt), link = stringResource(R.string.ks_h_herztrend), onLink = oeffneTrend)
        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val schnitt = ruhe.schnitt
            if (ruhe.frueher < LERNNAECHTE || schnitt == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ks_h_lernt, ruhe.frueher, LERNNAECHTE), Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp)
                    Lernpunkte(ruhe.frueher)
                }
            } else {
                // Ohne letzte Nacht kein Urteil - dann steht nur der Schnitt da.
                if (ruhe.urteil != null) Text(urteilSatz(ruhe, null), fontSize = 14.sp, lineHeight = 20.sp)
                Text(
                    stringResource(R.string.ks_h_gewohnt_text, ruhe.frueher, "${schnitt.roundToInt()} bpm"),
                    fontSize = 13.sp, lineHeight = 19.sp, color = farben.onSurfaceVariant,
                )
            }
        }
    }
}

/** Drei Punkte fuer die drei Naechte, die das Lernen braucht; gefuellt, was schon da ist. */
@Composable
private fun Lernpunkte(da: Int) {
    val farben = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(LERNNAECHTE) { i ->
            if (i < da) Box(Modifier.size(12.dp).background(farben.primary, CircleShape))
            else Box(Modifier.size(12.dp).padding(1.dp).border(1.6.dp, farben.outline, CircleShape))
        }
    }
}
