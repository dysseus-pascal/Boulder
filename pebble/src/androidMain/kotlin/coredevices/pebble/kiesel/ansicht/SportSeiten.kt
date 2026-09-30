package coredevices.pebble.kiesel.ansicht

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Kieselsport
import coredevices.pebble.kiesel.OffeneSpur
import coredevices.pebble.kiesel.Sport
import coredevices.pebble.kiesel.Sportrechnung
import coredevices.pebble.kiesel.TrainingEintrag
import coredevices.pebble.kiesel.TrainingsDaten
import coredevices.pebble.kiesel.TrainingsUebersicht
import coredevices.pebble.kiesel.Wochenrechnung
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * Die Unterseiten des Sport-Reiters: alle Trainings nach Wochen, die Trends
 * und die Spuren zum Nachtragen. Was frueher alles untereinander auf dem
 * Reiter stand.
 */

/**
 * Die zuletzt geladene Uebersicht. ZUERST DIE ALTE ZEIGEN: jede Rueckkehr aus
 * einem Training baut die Seite neu, und ohne sie stuende dort erst ein
 * Ladekreis und die Liste landete nicht an ihrer alten Stelle.
 */
private var zuletzt: TrainingsUebersicht? = null

@Composable
private fun Laden(inhalt: @Composable (TrainingsUebersicht) -> Unit) {
    val context = LocalContext.current
    var daten by remember { mutableStateOf(zuletzt) }
    LaunchedEffect(Unit) { daten = TrainingsDaten.uebersicht(context).also { zuletzt = it } }
    val d = daten
    if (d == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    else inhalt(d)
}

private fun datumText(context: android.content.Context, tag: LocalDate, flags: Int): String =
    DateUtils.formatDateTime(context, tag.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), flags)

// --- All workouts -------------------------------------------------------------------

/**
 * Alle Trainings des Vierteljahres, eine Karte je Woche mit ihrer Summe im
 * Kopf. [tagStart] kommt vom Tipp auf einen Tag der Wochenreihe.
 */
@Composable
internal fun SportListeAnsicht(tagStart: LocalDate?, oeffne: (String) -> Unit) = Laden { d ->
    val context = LocalContext.current
    // Saveable: das Oeffnen eines Trainings verwirft sonst die Filter.
    var tag by rememberSaveable { mutableStateOf(tagStart) }
    var sport by rememberSaveable { mutableStateOf<Sport?>(null) }
    val amTag = d.trainings.filter { tag == null || it.einheit.tag == tag }
    val gezeigt = amTag.filter { sport == null || it.sport == sport }
    val gruppen = Sportrechnung.nachWoche(gezeigt) { it.einheit.tag }
    val diese = LocalDate.now().with(DayOfWeek.MONDAY)

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip(stringResource(R.string.ks_sp_alle_chip), d.trainings.size, null, tag == null && sport == null) { tag = null; sport = null }
                tag?.let { t ->
                    val flags = DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL or DateUtils.FORMAT_NO_YEAR
                    Chip(datumText(context, t, flags), null, null, true, weg = true) { tag = null }
                }
                Sportrechnung.zaehle(amTag.map { it.einheit }).forEach { (art, n) ->
                    Chip(stringResource(art.nameId), n, Color(art.farbe), sport == art) { sport = if (sport == art) null else art }
                }
            }
        }
        items(gruppen, key = { it.first.toString() }) { (montag, liste) ->
            val titel = when (montag) {
                diese -> stringResource(R.string.ks_sp_diese_woche)
                diese.minusWeeks(1) -> stringResource(R.string.ks_sp_letzte_woche)
                else -> DateUtils.formatDateRange(
                    context, montag.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    montag.plusDays(7).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR,
                ).uppercase()
            }
            Wochengruppe(titel, liste, oeffne)
        }
        val fuss = when {
            tag == null && sport == null -> R.string.ks_sp_nichts_davor
            gezeigt.isEmpty() -> R.string.ks_sp_keine
            else -> null
        }
        if (fuss != null) item {
            Text(
                stringResource(fuss), Modifier.fillMaxWidth().padding(top = 6.dp),
                fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun Wochengruppe(titel: String, liste: List<TrainingEintrag>, oeffne: (String) -> Unit) {
    val context = LocalContext.current
    Kasten {
        Kopf(titel, stringResource(R.string.ks_sp_woche_zusatz, liste.size, dauerText(liste.sumOf { it.minuten })))
        liste.forEachIndexed { i, t ->
            if (i > 0) Trenner()
            val unter = trainingszeit(context, t.sitzung.startTime, false) + if (t.meter > 0) " · " + kmText(t.meter) else ""
            TrainingZeile(t, unter) { oeffne(t.id) }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** Ein Filterchip: gewaehlt gruen hinterlegt, sonst umrandet; der Punkt traegt die Farbe der Art. */
@Composable
private fun Chip(text: String, anzahl: Int?, punkt: Color?, gewaehlt: Boolean, weg: Boolean = false, onClick: () -> Unit) {
    val farben = MaterialTheme.colorScheme
    val form = RoundedCornerShape(8.dp)
    Row(
        Modifier.minimumInteractiveComponentSize().height(32.dp).clip(form)
            .then(if (gewaehlt) Modifier.background(farben.primary.copy(alpha = 0.22f)) else Modifier.border(1.dp, farben.outline, form))
            .clickable(onClick = onClick).semantics { selected = gewaehlt }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (punkt != null) Box(Modifier.size(8.dp).background(punkt, CircleShape))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (gewaehlt) toene().bewegung else farben.onSurface)
        if (anzahl != null) Text("$anzahl", fontSize = 14.sp, color = farben.onSurfaceVariant)
        if (weg) Icon(Zeichen.zu, null, Modifier.size(16.dp), tint = toene().bewegung)
    }
}

// --- Trends -------------------------------------------------------------------------

/** Die Bilder, die frueher auf dem Reiter standen - unveraendert, nur ohne Erklaersaetze. */
@Composable
internal fun SportTrendAnsicht() = Laden { d ->
    val heute = LocalDate.now()
    val einheiten = d.trainings.map { it.einheit }
    val verteilung = Wochenrechnung.verteilung(einheiten)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Spacer(Modifier.height(2.dp)) }
        item {
            Kasten {
                Kopf(stringResource(R.string.ks_sp_acht_wochen))
                Box(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) { Wochenstapel(Wochenrechnung.wochen(einheiten, heute), heute) }
            }
        }
        if (verteilung.isNotEmpty()) item {
            Kasten {
                Kopf(stringResource(R.string.ks_sp_nach_art), "· " + stringResource(R.string.ks_sp_90_tage))
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) { Verteilung(verteilung) }
            }
        }
        item {
            Kasten {
                Kopf(stringResource(R.string.ks_sp_vier_wochen))
                Box(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) { Kalender(Wochenrechnung.tage(einheiten), heute) }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

// --- Spuren zum Nachtragen -----------------------------------------------------------

/**
 * Spuren, zu denen kein Training in der Akte steht. Eintragen fragt nach der
 * Art; Ausblenden laesst die Spur liegen, zeigt sie aber nicht mehr.
 */
@Composable
internal fun SpurenAnsicht() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var daten by remember { mutableStateOf(zuletzt) }
    var neu by remember { mutableIntStateOf(0) }
    var artFuer by remember { mutableStateOf<OffeneSpur?>(null) }
    LaunchedEffect(neu) { daten = TrainingsDaten.uebersicht(context).also { zuletzt = it } }
    val d = daten
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val warn = toene().warnung
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp)) {
        Kasten {
            if (d.offen.isEmpty()) Zeile(stringResource(R.string.ks_sp_spuren_leer))
            d.offen.forEachIndexed { i, o ->
                if (i > 0) Trenner()
                Zeile(
                    trainingszeit(context, Instant.ofEpochSecond(o.beginn), true), dauerText(o.minuten) + " · " + kmText(o.meter),
                    zeichen = { Tonkreis(Zeichen.ort, warn, warn, 0.18f) },
                )
                Row(Modifier.padding(start = 64.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { TrainingsDaten.blendeAus(context, o.beginn); neu++ }) { Text(stringResource(R.string.ks_t_ausblenden)) }
                    Button(onClick = { artFuer = o }) { Text(stringResource(R.string.ks_t_eintragen)) }
                }
            }
        }
    }

    artFuer?.let { spur ->
        // Welche Art war es? Nur die mit Strecke - nur zu ihnen gibt es Spuren.
        AlertDialog(
            onDismissRequest = { artFuer = null },
            title = { Text(stringResource(R.string.ks_t_welche_art)) },
            text = {
                Column {
                    listOf(2L, 0L, 1L, 4L).forEach { art ->
                        TextButton(onClick = {
                            artFuer = null
                            scope.launch {
                                val meldung = runCatching { Kieselsport.nachtragen(context, spur.beginn, art) }
                                    .getOrElse { context.getString(R.string.ks_a_nicht_eingetragen) }
                                Toast.makeText(context, meldung, Toast.LENGTH_LONG).show()
                                neu++
                            }
                        }) { Text(Kieselsport.artAlsSatzart(context, art).second) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { artFuer = null }) { Text(stringResource(R.string.ks_abbrechen)) } },
        )
    }
}
