package coredevices.pebble.kiesel.ansicht

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coredevices.pebble.R
import coredevices.pebble.kiesel.GesundheitsStand
import coredevices.pebble.kiesel.Gesundheitsdaten
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.Herzurteil
import coredevices.pebble.kiesel.LERNNAECHTE
import coredevices.pebble.kiesel.hrvUrteil
import coredevices.pebble.kiesel.pulsFrisch
import coredevices.pebble.kiesel.ruhepulsUrteil
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.connection.LibPebble
import org.koin.compose.koinInject
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private const val SPO2_WARNUNG = 90

/**
 * "Heart today": der Puls der letzten 24 Stunden, dazu Ruhepuls, HRV und
 * SpO2 der letzten Nacht und was Boulder ueber die frueheren Naechte weiss.
 * Das Pulsband stand frueher auf der Uebersicht; hier hat es Platz und
 * verdraengt nichts.
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
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Spacer(Modifier.height(2.dp)) }
        item { PulsKarte(s) }
        item { NachtwerteKarte(s, oeffneNacht) }
        item { GewohntKarte(s, oeffneTrend) }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun PulsKarte(s: GesundheitsStand) {
    val context = LocalContext.current
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    val jetzt = Instant.now().epochSecond
    val letzter = s.puls.maxByOrNull { it.first }
    Kasten {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (letzter == null) {
                Text(stringResource(R.string.ks_h_kein_puls_kurz), fontSize = 14.sp, color = grau)
                return@Column
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // "now" nur, solange der Wert frisch ist - eine Stunde alter Puls ist nicht der jetzige.
                val frisch = Gesundheitsrechnung.pulsFrisch(letzter.first, jetzt)
                val wann = Instant.ofEpochSecond(letzter.first)
                Text(zahlMitEinheit("${letzter.second}", if (frisch) stringResource(R.string.ks_h_bpm_jetzt) else "bpm", 30, 16), lineHeight = 36.sp)
                Text(
                    if (frisch) stringResource(R.string.ks_h_gemessen, uhrzeit(context, wann))
                    else stringResource(R.string.ks_h_gemessen_alt, zeitpunkt(context, wann)),
                    fontSize = 14.sp, lineHeight = 20.sp, color = grau,
                )
            }
            val nacht = s.nacht?.let { it.einschlafen.epochSecond to it.aufwachen.epochSecond }
            // Die Stunden im Format des Systems, wie jede andere Uhrzeit der Seite.
            val stunde = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "H" else "h a", Locale.getDefault())
            Pulsband(
                s.puls, jetzt - 24 * 3600, jetzt, toene().herz, ruhepuls = s.nacht?.ruhepuls, nacht = nacht, hoehe = 180.dp,
                stundeText = { stunde.format(it) },
            )
        }
    }
}

@Composable
private fun NachtwerteKarte(s: GesundheitsStand, oeffneNacht: (Long) -> Unit) {
    val context = LocalContext.current
    val n = s.nacht
    // SpO2 der Nacht, wenn es eine gibt; sonst der letzten 24 Stunden - dann heisst die Karte auch so.
    val spo2 = if (n == null) s.spo2.map { it.second }
    else s.spo2.filter { it.first in n.einschlafen.epochSecond..n.aufwachen.epochSecond }.map { it.second }
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
                if (n.ruhepuls != null) stringResource(R.string.ks_h_ruhepuls_text) else stringResource(R.string.ks_h_nicht_gemessen),
                n.ruhepuls?.toString() ?: "–", n.ruhepuls?.let { "bpm" }, minHoehe = 56.dp,
            )
            Trenner(14.dp)
            Zeile(
                stringResource(R.string.ks_h_hrv_n),
                if (n.hrv != null) stringResource(R.string.ks_h_hrv_text) else stringResource(R.string.ks_h_nicht_gemessen),
                n.hrv?.toString() ?: "–", n.hrv?.let { "ms" }, minHoehe = 56.dp,
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
 * Die Werte des Vergleichs: "55 bpm, 54 ms" - was es davon gibt. Mit
 * [mindestens] nur die Schnitte aus so vielen Naechten: ein HRV-Wert aus
 * einer Nacht ist kein "average of 3 earlier nights".
 */
private fun werte(ruhe: Herzurteil, hrv: Herzurteil, mindestens: Int = 1): String = listOfNotNull(
    ruhe.schnitt?.takeIf { ruhe.frueher >= mindestens }?.let { "${it.roundToInt()} bpm" },
    hrv.schnitt?.takeIf { hrv.frueher >= mindestens }?.let { "${it.roundToInt()} ms" },
).joinToString(", ")

@Composable
private fun GewohntKarte(s: GesundheitsStand, oeffneTrend: () -> Unit) {
    val farben = MaterialTheme.colorScheme
    val ruhe = Gesundheitsrechnung.ruhepulsUrteil(s.nacht?.ruhepuls, s.ruhepulsWoche)
    val hrv = Gesundheitsrechnung.hrvUrteil(s.nacht?.hrv, s.hrvWoche)
    val frueher = maxOf(ruhe.frueher, hrv.frueher)
    Kasten {
        Kopf(stringResource(R.string.ks_h_gewohnt), link = stringResource(R.string.ks_h_herztrend), onLink = oeffneTrend)
        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (frueher < LERNNAECHTE) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ks_h_lernt, frueher, LERNNAECHTE), Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp)
                    Lernpunkte(frueher)
                }
            } else if (s.nacht == null) {
                // Ohne letzte Nacht gibt es nichts zu vergleichen - nur den Schnitt selbst.
                Text(
                    stringResource(R.string.ks_h_gewohnt_ohne_nacht, frueher, werte(ruhe, hrv, LERNNAECHTE)),
                    fontSize = 13.sp, lineHeight = 19.sp, color = farben.onSurfaceVariant,
                )
            } else {
                if (ruhe.urteil != null || hrv.urteil != null) Text(urteilSatz(ruhe, hrv), fontSize = 14.sp, lineHeight = 20.sp)
                Text(
                    stringResource(R.string.ks_h_gewohnt_text, frueher, werte(ruhe, hrv, LERNNAECHTE)),
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
