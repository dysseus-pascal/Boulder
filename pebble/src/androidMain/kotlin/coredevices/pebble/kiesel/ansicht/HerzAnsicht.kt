package coredevices.pebble.kiesel.ansicht

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coredevices.pebble.R
import coredevices.pebble.kiesel.BLUTDRUCK_TAGE
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.GesundheitsStand
import coredevices.pebble.kiesel.Gesundheitsdaten
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.LERNNAECHTE
import coredevices.pebble.kiesel.pulsFrisch
import coredevices.pebble.kiesel.ruhepulsUrteil
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.connection.LibPebble
import org.koin.compose.koinInject
import java.time.Duration
import java.time.Instant
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
        Ladekreis()
        return
    }
    // Vor der Liste gerechnet: ein leeres Item bekaeme seinen Abstand trotzdem, und ohne Manschette ist es meist leer.
    val blutdruck = remember(s.blutdruck) { Gesundheitsrechnung.blutdruckstand(s.blutdruck) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = SEITENRAND), verticalArrangement = Arrangement.spacedBy(KARTENABSTAND)) {
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
    val jetzt = Instant.now().epochSecond
    val letzter = s.puls.maxByOrNull { it.first }
    if (letzter == null) {
        Kasten { Leerzeile(stringResource(R.string.ks_h_kein_puls_kurz)) }
        return
    }
    val zonenNamen = listOf(R.string.ks_h_zone_leicht, R.string.ks_h_zone_moderat, R.string.ks_h_zone_kraeftig, R.string.ks_h_zone_spitze)
        .map { stringResource(it) }
    // Linie und Legende in denselben Toenen - der Punkt der Legende soll die Linie wiederfinden.
    val zonenFarben = Farben.pulszonen.map { it.ton() }
    // Wie bei Google Health: oben die Spanne des Tages, dann wo der hoechste Wert lag -
    // aus der Reihe, die das Pulsband zeichnet, damit Satz und Kurve dasselbe zeigen.
    val (tiefster, hoechster) = Gesundheitsrechnung.pulsSpanne(s.puls) ?: (letzter.second to letzter.second)
    val spanne = Formate.zahl(tiefster.toLong()) + "–" + Formate.zahl(hoechster.toLong())
    val tag = tagespuls(s)
    Kasten {
        Column(Modifier.padding(start = INNEN, end = INNEN, top = INNEN, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Hauptwert(spanne, "bpm")
                // Was die Herzzeile auf Health zeigt, steht hier wieder: wer sie antippt, findet seine Zahl.
                if (tag != null) Fliesstext((listOf(Formate.menge(tag.wachpuls.toLong(), "bpm").text) + tag.teile).joinToString(" · "))
                if (s.pulsZonen.size == 3) {
                    val zone = zonenNamen[Gesundheitsrechnung.pulsZone(hoechster, s.pulsZonen)]
                    // Ohne die Zahl: sie steht schon gross oben. Mit Tag, denn im 24-Stunden-Fenster kann die Spitze von gestern sein.
                    val wann = Gesundheitsrechnung.pulsSpitze(s.puls)?.let { Formate.zeitpunkt(context, Instant.ofEpochSecond(it.first), klein = true) }
                    Fliesstext(stringResource(R.string.ks_h_puls_hoch, zone.lowercase()) + (wann?.let { " · $it" } ?: ""))
                }
                // "now" nur, solange der Wert frisch ist - eine Stunde alter Puls ist nicht der jetzige.
                val frisch = Gesundheitsrechnung.pulsFrisch(letzter.first, jetzt)
                val wann = Instant.ofEpochSecond(letzter.first)
                Unterzeile(
                    if (frisch) stringResource(R.string.ks_h_puls_neu, letzter.second, Formate.uhrzeit(context, wann))
                    else stringResource(R.string.ks_h_puls_alt, letzter.second, Formate.zeitpunkt(context, wann, klein = true)),
                )
            }
            val nacht = s.nacht?.let { it.einschlafen.epochSecond to it.aufwachen.epochSecond }
            Pulsband(
                s.puls, jetzt - 24 * 3600, jetzt, s.pulsZonen, zonenFarben, nacht = nacht, hoehe = 200.dp,
                beschreibung = stringResource(R.string.ks_n_puls) + ", " + stringResource(R.string.ks_h_24h).lowercase() + ", " + Formate.Angabe(spanne, "bpm").text,
            )
            Punktlegende(zonenNamen.zip(zonenFarben).map { (name, farbe) -> Legendeneintrag(name, farbe) })
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
    val imBand = Farben.imBand.ton()
    val ausserBand = Farben.ausserBand.ton()
    val letzte = tage.last()
    val band = letzte.band
    val titel = stringResource(R.string.ks_h_hrv_woche)
    val zusatz = stringResource(R.string.ks_h_hrv_woche_zusatz)
    Abschnitt(titel, zusatz) {
        val satz = when {
            letzte.wert == null -> stringResource(R.string.ks_h_hrv_keine)
            band == null -> stringResource(R.string.ks_h_letzte_nacht)
            letzte.wert < band.first -> stringResource(R.string.ks_h_hrv_unter_band, band.first, band.second)
            letzte.wert > band.second -> stringResource(R.string.ks_h_hrv_ueber_band, band.first, band.second)
            else -> stringResource(R.string.ks_h_hrv_im_band, band.first, band.second)
        }
        Hauptwert(letzte.wert?.let { Formate.zahl(it.toLong()) } ?: Formate.FEHLT, letzte.wert?.let { "ms" }, satz)
        // Fuer TalkBack jede Nacht mit ihrem Wert - das Bild selbst ist stumm.
        val nachSatz = "$titel, $zusatz: " + tage.joinToString(", ") { t ->
            Formate.wochentagLang(t.tag.dayOfWeek) + " " + (t.wert?.let { Formate.Angabe(Formate.zahl(it.toLong()), "ms").text } ?: Formate.FEHLT)
        }
        // Zwei Buchstaben wie jede Reihe von sieben Tagen (Schritte, Naechte), nicht "M T W".
        HrvWoche(tage, imBand, ausserBand, beschreibung = nachSatz) { Formate.wochentagKurz(it.dayOfWeek) }
        if (tage.any { it.band != null }) {
            Punktlegende(
                listOfNotNull(
                    // Deckend gemischt statt Alpha: das Quadrat steht auf der Karte wie das Band im Bild.
                    Legendeneintrag(stringResource(R.string.ks_h_hrv_band), imBand.aufKarte(0.3f), eckig = true),
                    Legendeneintrag(stringResource(R.string.ks_h_hrv_drin), imBand),
                    // Daneben nur, wenn es in der Woche vorkommt - sonst erklaerte die Legende eine Farbe, die fehlt.
                    Legendeneintrag(stringResource(R.string.ks_h_hrv_draussen), ausserBand).takeIf { tage.any { it.imBand == false } },
                ),
            )
        }
    }
}

/**
 * Der Blutdruck der Manschette: die letzte Sitzung gross, ab drei Sitzungen
 * der Schnitt daneben, darunter jede Sitzung der letzten [BLUTDRUCK_TAGE]
 * Tage. Ohne Messung fehlt die Karte - die Uhr misst keinen Blutdruck.
 */
@Composable
private fun BlutdruckKarte(b: Gesundheitsrechnung.Blutdruckstand) {
    val context = LocalContext.current
    val jetzt = Instant.now()
    val titel = stringResource(R.string.ks_h_blutdruck_k)
    val zusatz = stringResource(R.string.ks_kp_tage_zusatz, BLUTDRUCK_TAGE.toInt())
    val wert = Formate.zahl(b.letzte.oben.toLong()) + "/" + Formate.zahl(b.letzte.unten.toLong())
    val schnitt = b.schnitt?.let { (oben, unten) -> stringResource(R.string.ks_d_blutdruck_schnitt, oben, unten) }
    Abschnitt(titel, zusatz) {
        // Am Zeilenanfang gross: "Today, 8:14 AM", "Yesterday, 8:14 AM", sonst das Datum - die eine Form aus Formate.
        Hauptwert(wert, "mmHg", listOfNotNull(Formate.zeitpunkt(context, b.letzte.zeit), schnitt).joinToString(" · "))
        Blutdruckbild(
            b.sitzungen, jetzt.minus(Duration.ofDays(BLUTDRUCK_TAGE)), jetzt, Farben.blutdruck.ton(),
            beschreibung = listOfNotNull("$titel, $zusatz", Formate.Angabe(wert, "mmHg").text, schnitt).joinToString(", "),
            tagText = { Formate.datumKurz(it) },
        )
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
                stringResource(R.string.ks_h_zeitraum, Formate.uhrzeit(context, n.einschlafen), Formate.uhrzeit(context, n.aufwachen)),
                stringResource(R.string.ks_h_nachtansicht),
            ) { oeffneNacht(n.beginnUtc) }
            Zeile(
                stringResource(R.string.ks_h_ruhepuls_n),
                if (n.ruhepuls != null) null else stringResource(R.string.ks_h_nicht_gemessen),
                n.ruhepuls?.let { Formate.zahl(it.toLong()) } ?: Formate.FEHLT, n.ruhepuls?.let { "bpm" }, minHoehe = 56.dp,
            )
        } else {
            Kopf(stringResource(R.string.ks_h_24h))
        }
        if (spo2.isNotEmpty()) {
            if (n != null) Trenner(INNEN)
            val unter = spo2.count { it < SPO2_WARNUNG }
            val tiefster = Formate.prozent(spo2.min())
            val schnitt = Formate.prozentTeile(spo2.average().roundToInt())
            Zeile(
                stringResource(R.string.ks_h_spo2_n),
                if (unter == 0) stringResource(R.string.ks_h_spo2_keins, tiefster)
                else pluralStringResource(R.plurals.ks_h_spo2_unter, unter, tiefster, unter),
                schnitt.zahl, schnitt.einheit, minHoehe = 56.dp,
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
    val ruhe = Gesundheitsrechnung.ruhepulsUrteil(s.nacht?.ruhepuls, s.ruhepulsWoche)
    Kasten {
        Kopf(stringResource(R.string.ks_h_gewohnt), link = stringResource(R.string.ks_h_herztrend), onLink = oeffneTrend)
        Column(Modifier.padding(start = INNEN, end = INNEN, bottom = INNEN), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val schnitt = ruhe.schnitt
            if (ruhe.frueher < LERNNAECHTE || schnitt == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Fliesstext(stringResource(R.string.ks_h_lernt, ruhe.frueher, LERNNAECHTE), Modifier.weight(1f))
                    Lernpunkte(ruhe.frueher)
                }
            } else {
                // Ohne letzte Nacht kein Urteil - dann steht nur der Schnitt da.
                if (ruhe.urteil != null) Fliesstext(urteilSatz(ruhe, null))
                Unterzeile(stringResource(R.string.ks_h_gewohnt_text, ruhe.frueher, Formate.menge(schnitt.roundToInt().toLong(), "bpm").text))
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
