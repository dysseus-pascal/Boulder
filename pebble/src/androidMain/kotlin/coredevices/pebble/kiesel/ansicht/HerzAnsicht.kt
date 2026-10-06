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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.GesundheitsStand
import coredevices.pebble.kiesel.Gesundheitsdaten
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.Fenster
import coredevices.pebble.kiesel.Blutdrucklage
import coredevices.pebble.kiesel.Vergleich
import coredevices.pebble.kiesel.bandurteil
import coredevices.pebble.kiesel.blutdrucklage
import coredevices.pebble.kiesel.pulsFrisch
import coredevices.pebble.kiesel.ruhepulsUrteil
import java.time.Instant
import kotlin.math.roundToInt

private const val SPO2_WARNUNG = 90

/**
 * "Heart & Body": oben der Puls der letzten 24 Stunden, dann HRV und
 * Blutdruck je als Woche an ihrem persoenlichen Band, Ruhepuls und SpO2 der
 * letzten Nacht, was Boulder ueber die frueheren Naechte weiss, und am Ende
 * der Weg zu den Trends. Hier wird geladen; was zu sehen ist, zeichnet
 * [HerzInhalt] aus dem fertigen Stand.
 */
@Composable
internal fun HerzAnsicht(oeffneNacht: (Long) -> Unit, oeffneTrend: () -> Unit) {
    // Derselbe Lader wie auf Health: von dort kommend steht die Seite gleich da und liest nicht noch einmal.
    val lies = rememberGesundheitslader()
    val s = rememberStand(Gesundheitsdaten.zuletzt, rememberUhrschub()) { lies() }
    if (s == null) {
        Ladekreis()
        return
    }
    HerzInhalt(s, Instant.now(), oeffneNacht, oeffneTrend)
}

/**
 * Die Seite aus einem fertigen Stand - ohne Laden, damit ein Bildtest sie mit
 * Beispieldaten zeichnen kann. [jetzt]: das Ende der 24 Stunden Puls und der
 * Bezug von "Today" beim Blutdruck.
 */
@Composable
internal fun HerzInhalt(s: GesundheitsStand, jetzt: Instant, oeffneNacht: (Long) -> Unit, oeffneTrend: () -> Unit) {
    // Vor der Liste gerechnet: ein leeres Item bekaeme seinen Abstand trotzdem, und ohne Manschette ist es meist leer.
    val blutdruck = remember(s.blutdruck) { Gesundheitsrechnung.blutdruckstand(s.blutdruck) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = SEITENRAND), verticalArrangement = Arrangement.spacedBy(KARTENABSTAND)) {
        item { Spacer(Modifier.height(2.dp)) }
        item { PulsKarte(s, jetzt.epochSecond) }
        item { HrvKarte(s) }
        // Nach der HRV: die beiden Wochen am Band stehen beisammen, der Puls des Tages bleibt oben.
        if (blutdruck != null) item { BlutdruckKarte(blutdruck, s.blutdruckWoche, jetzt) }
        item { NachtwerteKarte(s, oeffneNacht) }
        item { GewohntKarte(s, oeffneTrend) }
        item { Trendzeile(oeffneTrend) }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

/** Der Puls des Tages - internal fuer PulsKarteBildTest, der die ganze Karte zeichnet. */
@Composable
internal fun PulsKarte(s: GesundheitsStand, jetzt: Long = Instant.now().epochSecond) {
    val context = LocalContext.current
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
    // Die Bins einmal: Spanne, Spitze und Band bildeten sie sonst je fuer sich (3x groupBy ueber bis zu 1440 Minuten).
    val bins = remember(s.puls) { Gesundheitsrechnung.fuenfMinuten(s.puls) }
    val (tiefster, hoechster) = Gesundheitsrechnung.spanneDerBins(bins) ?: (letzter.second to letzter.second)
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
                    val wann = Gesundheitsrechnung.spitzeDerBins(bins)?.let { Formate.zeitpunkt(context, Instant.ofEpochSecond(it.first), klein = true) }
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
                bins, jetzt - 24 * 3600, jetzt, s.pulsZonen, zonenFarben, nacht = nacht, hoehe = 200.dp,
                beschreibung = stringResource(R.string.ks_n_puls) + ", " + stringResource(R.string.ks_h_24h).lowercase() + ", " + Formate.Angabe(spanne, "bpm").text,
            )
            Punktlegende(zonenNamen.zip(zonenFarben).map { (name, farbe) -> Legendeneintrag(name, farbe) })
        }
    }
}

/** Das Urteil am Band als kurzes Wort rechts im Kopf, in der Farbe der Punkte: gruen im Band, bernstein daneben. */
private class Kopfurteil(val text: String, val farbe: Color)

/**
 * Die HRV der letzten sieben Naechte mit dem persoenlichen Band - was als
 * gewohnt gilt, sieht man so auf einen Blick, statt es aus einem Schnitt
 * herauszulesen. Im Kopf der Wert der letzten Nacht, der Schnitt der Woche
 * und das Urteil; keine Legende - die Farben stehen im Urteil daneben.
 */
@Composable
private fun HrvKarte(s: GesundheitsStand) {
    val tage = s.hrvTage
    if (tage.none { it.wert != null }) return
    val imBand = Farben.imBand.ton()
    val ausserBand = Farben.ausserBand.ton()
    val letzte = tage.last()
    val titel = stringResource(R.string.ks_h_hrv_woche)
    val zusatz = stringResource(R.string.ks_h_hrv_woche_zusatz)
    // Das Urteil aus bandurteil - dieselbe Regel, die die Punkte im Bild faerbt.
    val urteil = Gesundheitsrechnung.bandurteil(letzte)?.let { u ->
        when (u.urteil) {
            Vergleich.WIE_IMMER -> Kopfurteil(stringResource(R.string.ks_h_im_band), imBand)
            Vergleich.TIEFER -> Kopfurteil(stringResource(R.string.ks_h_unter_band), ausserBand)
            Vergleich.HOEHER -> Kopfurteil(stringResource(R.string.ks_h_ueber_band), ausserBand)
        }
    }
    val schnitt = Gesundheitsrechnung.wochenschnitt(tage)?.let {
        stringResource(R.string.ks_h_naechte_schnitt, Fenster.WOCHE_TAGE, Formate.zahl(it.toLong()))
    }
    val nacht = stringResource(if (letzte.wert == null) R.string.ks_h_hrv_keine else R.string.ks_h_letzte_nacht)
    Abschnitt(titel, zusatz, urteil = urteil?.text, urteilFarbe = urteil?.farbe) {
        Hauptwert(letzte.wert?.let { Formate.zahl(it.toLong()) } ?: Formate.FEHLT, letzte.wert?.let { "ms" }, listOfNotNull(nacht, schnitt).joinToString(" · "))
        // Fuer TalkBack jede Nacht mit ihrem Wert - das Bild selbst ist stumm.
        val nachSatz = "$titel, $zusatz: " + tage.joinToString(", ") { t ->
            Formate.wochentagLang(t.tag.dayOfWeek) + " " + (t.wert?.let { Formate.Angabe(Formate.zahl(it.toLong()), "ms").text } ?: Formate.FEHLT)
        }
        // Zwei Buchstaben wie jede Reihe von sieben Tagen (Schritte, Naechte), nicht "M T W".
        Bandwoche(listOf(tage), imBand, ausserBand, hoehe = WOCHENBILD, beschreibung = nachSatz) { Formate.wochentagKurz(it.dayOfWeek) }
    }
}

/** So hoch stehen die Wochen von HRV und Blutdruck, wie im Entwurf "Erlebnis-Herz". */
private val WOCHENBILD = 190.dp

/**
 * Der Blutdruck der Manschette wie die HRV: die letzte Sitzung gross mit
 * ihrer Zeit, ab drei Sitzungen der Woche ihr Schnitt, darunter die sieben
 * Tage - oben und unten je an ihrem Band, gruen darin, bernstein daneben,
 * ohne genug fruehere Tage neutral. Ohne Messung in [Fenster.BLUTDRUCK_TAGE]
 * Tagen fehlt die Karte - die Uhr misst keinen Blutdruck.
 */
@Composable
private fun BlutdruckKarte(b: Gesundheitsrechnung.Blutdruckstand, woche: Gesundheitsrechnung.Blutdruckwoche?, jetzt: Instant) {
    val context = LocalContext.current
    val imBand = Farben.imBand.ton()
    val ausserBand = Farben.ausserBand.ton()
    val titel = stringResource(R.string.ks_h_blutdruck_k)
    val zusatz = stringResource(R.string.ks_kp_tage_zusatz, Fenster.WOCHE_TAGE)
    val wert = Formate.zahl(b.letzte.oben.toLong()) + "/" + Formate.zahl(b.letzte.unten.toLong())
    // Der Schnitt der sieben Tage, die Titel und Bild zeigen.
    val schnitt = woche?.schnitt?.let { (oben, unten) -> stringResource(R.string.ks_d_blutdruck_schnitt, oben, unten) }
    // Das Urteil gilt dem juengsten Tag mit Messung in der Woche - dem Tag des grossen Werts, wenn er in der Woche liegt.
    val tag = woche?.oben?.indexOfLast { it.wert != null }?.takeIf { it >= 0 }
    val urteil = tag?.let { Gesundheitsrechnung.blutdrucklage(woche.oben[it], woche.unten[it]) }?.let { lage ->
        when (lage) {
            Blutdrucklage.IM_BAND -> Kopfurteil(stringResource(R.string.ks_h_im_band), imBand)
            Blutdrucklage.DARUEBER -> Kopfurteil(stringResource(R.string.ks_h_ueber_band), ausserBand)
            Blutdrucklage.DARUNTER -> Kopfurteil(stringResource(R.string.ks_h_unter_band), ausserBand)
            Blutdrucklage.DANEBEN -> Kopfurteil(stringResource(R.string.ks_h_neben_band), ausserBand)
        }
    }
    Abschnitt(titel, zusatz, urteil = urteil?.text, urteilFarbe = urteil?.farbe) {
        // Am Zeilenanfang gross: "Today, 8:14 AM", "Yesterday, 8:14 AM", sonst das Datum - die eine Form aus Formate.
        Hauptwert(wert, "mmHg", listOfNotNull(Formate.zeitpunkt(context, b.letzte.zeit, jetzt), schnitt).joinToString(" · "))
        if (woche != null) {
            val nachSatz = "$titel, $zusatz: " + woche.oben.zip(woche.unten).joinToString(", ") { (o, u) ->
                Formate.wochentagLang(o.tag.dayOfWeek) + " " +
                    (if (o.wert != null && u.wert != null) Formate.Angabe("${Formate.zahl(o.wert.toLong())}/${Formate.zahl(u.wert.toLong())}", "mmHg").text else Formate.FEHLT)
            }
            // Zwei Reihen in einem Bild: oben und unten teilen Achse und Tage, wie im Entwurf.
            Bandwoche(listOf(woche.oben, woche.unten), imBand, ausserBand, hoehe = WOCHENBILD, rasterSchritt = 10, beschreibung = nachSatz) {
                Formate.wochentagKurz(it.dayOfWeek)
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
                stringResource(R.string.ks_h_zeitraum, Formate.uhrzeit(context, n.einschlafen), Formate.uhrzeit(context, n.aufwachen)),
                stringResource(R.string.ks_h_nachtansicht),
                onLink = { oeffneNacht(n.beginnUtc) },
            )
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
            if (ruhe.frueher < Fenster.LERNNAECHTE || schnitt == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Fliesstext(stringResource(R.string.ks_h_lernt, ruhe.frueher, Fenster.LERNNAECHTE), Modifier.weight(1f))
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

/**
 * Die letzte Zeile der Seite: der Weg zu den Herztrends und ihrem "What
 * makes a difference" - im Stil der Zeilen auf Health, mit Zeichen und Chevron.
 */
@Composable
private fun Trendzeile(oeffneTrend: () -> Unit) {
    Kasten {
        Zeile(
            stringResource(R.string.ks_h_trends), stringResource(R.string.ks_h_was_zaehlt),
            zeichen = { Tonkreis(Zeichen.trend, Farben.aktion, 0.18f) }, onClick = oeffneTrend,
        )
    }
}

/** Drei Punkte fuer die drei Naechte, die das Lernen braucht; gefuellt, was schon da ist. */
@Composable
private fun Lernpunkte(da: Int) {
    val farben = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(Fenster.LERNNAECHTE) { i ->
            if (i < da) Box(Modifier.size(12.dp).background(farben.primary, CircleShape))
            else Box(Modifier.size(12.dp).padding(1.dp).border(1.6.dp, farben.outline, CircleShape))
        }
    }
}
