package coredevices.pebble.kiesel.ansicht

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Pool
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coredevices.pebble.R
import coredevices.pebble.kiesel.Wochenrechnung
import coredevices.pebble.health.HealthConnectPermissionBridge
import coredevices.pebble.kiesel.KIESELSPORT_RECHTE
import coredevices.pebble.kiesel.KieselVerlauf
import coredevices.pebble.kiesel.Kieselsport
import coredevices.pebble.kiesel.OffeneSpur
import coredevices.pebble.kiesel.Sport
import coredevices.pebble.kiesel.SpurDienst
import coredevices.pebble.kiesel.TrainingEintrag
import coredevices.pebble.kiesel.TrainingsDaten
import coredevices.pebble.kiesel.TrainingsUebersicht
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

// --- Kleine Helfer -------------------------------------------------------------

internal fun Sport.zeichen(): ImageVector = when (this) {
    Sport.Laufen -> Icons.AutoMirrored.Filled.DirectionsRun
    Sport.Bike, Sport.Mtb -> Icons.AutoMirrored.Filled.DirectionsBike
    Sport.Wandern -> Icons.Filled.Hiking
    Sport.Kraft -> Icons.Filled.FitnessCenter
    Sport.Yoga -> Icons.Filled.SelfImprovement
    Sport.Schwimmen -> Icons.Filled.Pool
    Sport.Anderes -> Icons.Filled.Timer
}

@Composable
internal fun SportZeichen(sport: Sport, groesse: Int = 40) {
    Box(
        Modifier.size(groesse.dp).background(Color(sport.farbe).copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(sport.zeichen(), contentDescription = null, tint = Color(sport.farbe), modifier = Modifier.size((groesse * 0.6f).dp))
    }
}

internal fun dauerText(minuten: Long): String =
    if (minuten >= 60) String.format(Locale.getDefault(), "%d:%02d h", minuten / 60, minuten % 60) else "$minuten min"

internal fun kmText(meter: Double): String = String.format(Locale.getDefault(), "%.1f km", meter / 1000)

internal fun wannText(context: Context, zeit: Instant): String = DateUtils.formatDateTime(
    context, zeit.toEpochMilli(),
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL or DateUtils.FORMAT_SHOW_TIME,
)

private fun darfOrten(context: Context) = SpurDienst.darfOrten(context)
private fun darfImmerOrten(context: Context) = SpurDienst.darfImmerOrten(context)

// --- Die Liste -----------------------------------------------------------------

/**
 * Der Trainings-Reiter: was Kieselsport eingetragen hat, juengstes zuerst.
 *
 * OBEN DAS, WAS FEHLT: die Erlaubnis fuer die Strecke, der Lesezugriff auf die
 * Akte, und Spuren ohne Training. Darunter die Woche und die Trainings des
 * Vierteljahres.
 */
@Composable
internal fun TrainingListe(
    setzeAktionen: (@Composable () -> Unit) -> Unit,
    oeffne: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var daten by remember { mutableStateOf<TrainingsUebersicht?>(null) }
    var fehlendeRechte by remember { mutableStateOf<Set<String>>(emptySet()) }
    var neu by remember { mutableIntStateOf(0) }
    var protokoll by remember { mutableStateOf(false) }
    var einstellungen by remember { mutableStateOf(false) }
    var artFuer by remember { mutableStateOf<OffeneSpur?>(null) }
    var ortsstand by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        setzeAktionen {
            IconButton(onClick = { protokoll = true }) {
                Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = stringResource(R.string.ks_n_protokoll))
            }
            IconButton(onClick = { einstellungen = true }) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.ks_n_einstellungen))
            }
        }
    }
    // Zurueck aus den Systemeinstellungen: dort kann sich die Erlaubnis geaendert haben.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ortsstand++; neu++ }
    LaunchedEffect(neu) {
        daten = TrainingsDaten.uebersicht(context)
        fehlendeRechte = TrainingsDaten.fehlendeRechte(context)
    }

    val ortStarter = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ortsstand++ }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(2.dp)) }

        // Die Erlaubnis fuer die Strecke - ohne "Immer" bleibt die Karte leer.
        item(key = "ort-$ortsstand") {
            val erlaubt = darfOrten(context)
            val immer = darfImmerOrten(context)
            if (!erlaubt || !immer) {
                Karte {
                    Text(stringResource(R.string.ks_ei_strecke_aufzeichnen), fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(if (!erlaubt) R.string.ks_ei_standort_nicht else R.string.ks_ei_standort_nutzung),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(stringResource(R.string.ks_ei_kein_gps), style = MaterialTheme.typography.bodySmall)
                    if (!erlaubt) {
                        Button(onClick = {
                            ortStarter.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }) { Text(stringResource(R.string.ks_ei_standort_erlauben)) }
                    } else {
                        // DER HAEUFIGE FALL, und er sieht aus wie ein Fehler: die
                        // Erlaubnis steht da, die Karte bleibt leer. "Immer" gibt
                        // es nur in den Systemeinstellungen.
                        Text(stringResource(R.string.ks_ei_nur_nutzung_text), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }) { Text(stringResource(R.string.ks_ei_immer_erlauben)) }
                    }
                }
            }
        }

        val d = daten
        if (d == null) {
            item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            return@LazyColumn
        }
        if (!d.akteDa) {
            item { Karte { Text(stringResource(R.string.ks_n_akte_fehlt)) } }
            return@LazyColumn
        }
        if (fehlendeRechte.isNotEmpty()) {
            item {
                Karte {
                    Text(stringResource(R.string.ks_n_akte_rechte), fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.ks_n_akte_rechte_text), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        scope.launch {
                            HealthConnectPermissionBridge.request(context, KIESELSPORT_RECHTE)
                            neu++
                        }
                    }) { Text(stringResource(R.string.ks_n_erlauben)) }
                }
            }
        }

        if (d.offen.isNotEmpty()) {
            item {
                Karte {
                    Text(stringResource(R.string.ks_t_offen), fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.ks_t_offen_text), style = MaterialTheme.typography.bodySmall)
                    d.offen.forEach { o ->
                        Text(
                            wannText(context, Instant.ofEpochSecond(o.beginn)) + "  ·  " + dauerText(o.minuten) + "  ·  " + kmText(o.meter),
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { TrainingsDaten.blendeAus(context, o.beginn); neu++ }) {
                                Text(stringResource(R.string.ks_t_ausblenden))
                            }
                            Button(onClick = { artFuer = o }) { Text(stringResource(R.string.ks_t_eintragen)) }
                        }
                    }
                }
            }
        }

        item { Woche(d.trainings) }

        if (d.trainings.isEmpty()) {
            item {
                Karte {
                    Text(stringResource(R.string.ks_t_noch_keins), fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.ks_t_noch_keins_text), style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            // DAS JUENGSTE GLEICH UNTER DER WOCHE: was man sucht, wenn man
            // diesen Schirm oeffnet, ist fast immer das letzte Training. Die
            // Bilder ueber die Wochen kommen danach, der Rest der Liste zuletzt.
            val erstes = d.trainings.first()
            item { Ueberschrift(stringResource(R.string.ks_n_zuletzt)) }
            item(key = erstes.id) { Zeile(erstes) { oeffne(erstes.id) } }
            val einheiten = d.trainings.map { it.einheit }
            item {
                Ueberschrift(stringResource(R.string.ks_n_acht_wochen))
                Karte {
                    Text(stringResource(R.string.ks_n_minuten_woche), style = MaterialTheme.typography.bodySmall)
                    Wochenstapel(Wochenrechnung.wochen(einheiten, LocalDate.now()), LocalDate.now())
                }
            }
            item {
                Ueberschrift(stringResource(R.string.ks_n_woraus))
                Karte {
                    Text(stringResource(R.string.ks_n_drei_monate), style = MaterialTheme.typography.bodySmall)
                    Verteilung(Wochenrechnung.verteilung(einheiten))
                }
            }
            if (d.trainings.size > 1) item { Ueberschrift(stringResource(R.string.ks_n_davor)) }
            items(d.trainings.drop(1), key = { it.id }) { t -> Zeile(t) { oeffne(t.id) } }
        }
        item { Spacer(Modifier.height(12.dp)) }
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
                                val meldung = Kieselsport.nachtragen(context, spur.beginn, art)
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

    if (einstellungen) EinstellungenDialog { einstellungen = false }

    if (protokoll) {
        val zeilen = remember { KieselVerlauf.alle(context) }
        AlertDialog(
            onDismissRequest = { protokoll = false },
            title = { Text(stringResource(R.string.ks_n_protokoll)) },
            text = {
                LazyColumn(Modifier.height(400.dp)) {
                    if (zeilen.isEmpty()) item { Text(stringResource(R.string.ks_n_protokoll_leer)) }
                    items(zeilen) { (t, z) ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(wannText(context, Instant.ofEpochMilli(t)), style = MaterialTheme.typography.labelSmall)
                            Text(z, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { protokoll = false }) { Text("OK") } },
        )
    }
}

@Composable
internal fun Karte(inhalt: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { inhalt() }
    }
}

/** Die letzten sieben Tage in einer Zeile: wie oft, wie lange, wie weit. */
@Composable
private fun Woche(trainings: List<TrainingEintrag>) {
    val grenze = Instant.now().minus(Duration.ofDays(7))
    val woche = trainings.filter { it.sitzung.startTime.isAfter(grenze) }
    Karte {
        Text(stringResource(R.string.ks_t_sieben_tage), style = MaterialTheme.typography.labelMedium)
        if (woche.isEmpty()) {
            Text(stringResource(R.string.ks_t_sieben_keins))
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Wert(woche.size.toString(), stringResource(R.string.ks_t_trainings))
                Wert(dauerText(woche.sumOf { it.minuten }), stringResource(R.string.ks_t_zeit))
                val meter = woche.sumOf { it.meter }
                if (meter > 0) Wert(kmText(meter), stringResource(R.string.ks_t_strecke))
            }
        }
        Kalender(Wochenrechnung.tage(trainings.map { it.einheit }), LocalDate.now())
        Text(stringResource(R.string.ks_n_kalender_text), style = MaterialTheme.typography.bodySmall)
    }
}

/** Eine Abschnittsueberschrift zwischen den Karten, wie in Kiesel-Helper. */
@Composable
internal fun Ueberschrift(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp),
    )
}

@Composable
internal fun Wert(zahl: String, name: String) {
    Column {
        Text(zahl, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Zeile(t: TrainingEintrag, tippe: () -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().clickable(onClick = tippe)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SportZeichen(t.sport)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.sitzung.title ?: stringResource(t.sport.nameId), fontWeight = FontWeight.Bold)
                val teile = buildList {
                    add(wannText(context, t.sitzung.startTime))
                    add(dauerText(t.minuten))
                    if (t.meter > 0) add(kmText(t.meter))
                }
                Text(teile.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
