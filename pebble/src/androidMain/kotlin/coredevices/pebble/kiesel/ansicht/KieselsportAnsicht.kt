package coredevices.pebble.kiesel.ansicht

import android.app.TimePickerDialog
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Herzgrenzen
import coredevices.pebble.kiesel.KieselEinstellungen
import coredevices.pebble.rememberLibPebble
import kotlinx.coroutines.launch

/** Die Pin-Arten in der Reihenfolge der Uhr: 0 kein Pin, dann Sportart + 1. */
private val PIN_ARTEN = listOf(
    R.string.ks_n_kein_pin, R.string.ks_sport_laufen, R.string.ks_sport_bike, R.string.ks_sport_wandern,
    R.string.ks_sport_kraft, R.string.ks_sport_mtb, R.string.ks_sport_yoga, R.string.ks_sport_schwimmen,
)

/**
 * Die Einstellungen von Kieselsport, am Telefon geaendert - eine Seite in
 * Gravels Einstellungen (Sport).
 *
 * DER ENTWURF STARTET BEIM STAND DER UHR, nicht bei einem eigenen: die Uhr
 * ist die eine Stelle, an der die Werte gelten (siehe KieselEinstellungen).
 */
@Composable
internal fun KieselsportAnsicht() {
    val context = LocalContext.current
    val libPebble = rememberLibPebble()
    val scope = rememberCoroutineScope()
    var stand by remember { mutableStateOf(KieselEinstellungen.stand(context)) }
    var entwurf by remember { mutableStateOf(stand) }
    var sendet by remember { mutableStateOf(false) }
    var ergebnis by remember { mutableStateOf<KieselEinstellungen.Ergebnis?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            when {
                stand.gemeldetUm == 0L -> stringResource(R.string.ks_n_stand_nie)
                // Unter einer Minute sagte DateUtils "vor 0 Minuten".
                System.currentTimeMillis() - stand.gemeldetUm < DateUtils.MINUTE_IN_MILLIS -> stringResource(R.string.ks_n_stand_eben)
                else -> stringResource(R.string.ks_n_stand_um, DateUtils.getRelativeTimeSpanString(stand.gemeldetUm).toString())
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Abschnitt(stringResource(R.string.ks_n_puls))
        // "Nach Alter" ist ein eigener Zustand, nicht die Zahl 190: zur Uhr geht
        // die Schaetzung selbst, damit ihre Zonen die der App sind. Erst nach dem
        // Laden entscheiden - vorher saehe jedes Alter wie keines aus.
        val schaetzung by produceState<Herzgrenzen.Maximum?>(null) { value = Herzgrenzen.maximum(null, Herzgrenzen.alter(), null) }
        val nachAlter = schaetzung?.takeIf { it.nachAlter }?.wert
        // Der zuletzt eingestellte Wert: "From age" an und wieder aus soll ihn nicht verlieren.
        var eigener by remember { mutableStateOf(stand.maxpuls.takeIf { stand.maxpulsGesetzt }) }
        LaunchedEffect(nachAlter) { if (nachAlter != null && !entwurf.maxpulsGesetzt) entwurf = entwurf.copy(maxpuls = nachAlter) }
        // Ohne Alter gibt es nichts zu schaetzen: kein Schalter, der eine Schaetzung behauptet.
        if (nachAlter != null) Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(!entwurf.maxpulsGesetzt, role = Role.Switch) { ausAlter ->
                entwurf = entwurf.copy(maxpulsGesetzt = !ausAlter, maxpuls = if (ausAlter) nachAlter else eigener ?: nachAlter)
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.ks_n_maxpuls_alter, nachAlter), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = !entwurf.maxpulsGesetzt, onCheckedChange = null)
        }
        if (schaetzung != null && (entwurf.maxpulsGesetzt || nachAlter == null)) Stufer(
            stringResource(R.string.ks_n_maxpuls), (120..220).toList(), entwurf.maxpuls, grob = 5,
            text = { "$it" },
        ) {
            entwurf = entwurf.copy(maxpuls = it, maxpulsGesetzt = true)
            eigener = it
        }

        Abschnitt(stringResource(R.string.ks_sport_kraft))
        val nie = stringResource(R.string.ks_n_nie)
        Stufer(
            stringResource(R.string.ks_n_pause), (0..300 step 15).toList(), entwurf.pause,
            text = { if (it == 0) nie else "$it s" },
        ) { entwurf = entwurf.copy(pause = it) }
        val stufen = listOf(
            stringResource(R.string.ks_n_traege), stringResource(R.string.ks_n_normal), stringResource(R.string.ks_n_fein),
        )
        Stufer(
            stringResource(R.string.ks_n_empfind), listOf(1, 2, 3), entwurf.empfind,
            text = { stufen[it - 1] },
        ) { entwurf = entwurf.copy(empfind = it) }

        Abschnitt(stringResource(R.string.ks_sport_schwimmen))
        Stufer(
            stringResource(R.string.ks_n_becken), (10..50 step 5).toList(), entwurf.becken,
            text = { "$it m" },
        ) { entwurf = entwurf.copy(becken = it) }

        Abschnitt(stringResource(R.string.ks_n_pin))
        val arten = PIN_ARTEN.map { stringResource(it) }
        Stufer(
            stringResource(R.string.ks_n_pin_art), (0..7).toList(), entwurf.pinArt,
            text = { arten[it] },
        ) { entwurf = entwurf.copy(pinArt = it) }
        if (entwurf.pinArt > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ks_n_pin_zeit), Modifier.weight(1f))
                OutlinedButton(onClick = {
                    val (h, m) = entwurf.pinZeit.split(":").map { it.toIntOrNull() ?: 0 }.let { it[0] to it.getOrElse(1) { 0 } }
                    // "HH:MM" wie die Uhr es zurueckmeldet - sonst
                    // saehe "8:00" gegen "08:00" unbestaetigt aus.
                    TimePickerDialog(context, { _, hh, mm ->
                        entwurf = entwurf.copy(pinZeit = String.format("%02d:%02d", hh, mm))
                    }, h, m, DateFormat.is24HourFormat(context)).show()
                }) { Text(entwurf.pinZeit) }
            }
        }

        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !sendet && !entwurf.gleich(stand),
                onClick = {
                    sendet = true
                    ergebnis = null
                    scope.launch {
                        ergebnis = KieselEinstellungen.sende(context, libPebble, entwurf)
                        stand = KieselEinstellungen.stand(context)
                        sendet = false
                    }
                },
            ) {
                if (sendet) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.ks_n_senden))
            }
        }
        when (ergebnis) {
            KieselEinstellungen.Ergebnis.Uebernommen -> Hinweis(stringResource(R.string.ks_n_uebernommen))
            KieselEinstellungen.Ergebnis.KeineUhr -> Hinweis(stringResource(R.string.ks_n_keine_uhr))
            KieselEinstellungen.Ergebnis.KeineAntwort -> Hinweis(stringResource(R.string.ks_n_keine_antwort))
            null -> {}
        }
    }
}

@Composable
private fun Abschnitt(titel: String) {
    Text(
        titel.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun Hinweis(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
}

/**
 * Ein Wert aus einer festen Reihe, mit Minus und Plus. Mit [grob] kommen
 * Knoepfe dazu, die so viele Stufen auf einmal gehen - beim Maximalpuls
 * waeren hundert Einzelschritte zu viel.
 */
@Composable
private fun Stufer(
    titel: String,
    werte: List<Int>,
    wert: Int,
    grob: Int = 0,
    text: (Int) -> String,
    aendere: (Int) -> Unit,
) {
    // Ein Wert, den die Uhr ausserhalb der Reihe meldet, landet auf der naechsten Stufe.
    val i = werte.indices.minBy { kotlin.math.abs(werte[it] - wert) }
    fun geh(schritte: Int) = aendere(werte[(i + schritte).coerceIn(werte.indices)])
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(titel, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (grob > 0) TextButton(onClick = { geh(-grob) }, enabled = i > 0) { Text("−$grob") }
            TextButton(onClick = { geh(-1) }, enabled = i > 0) { Text("−") }
            Text(text(werte[i]), Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
            TextButton(onClick = { geh(1) }, enabled = i < werte.lastIndex) { Text("+") }
            if (grob > 0) TextButton(onClick = { geh(grob) }, enabled = i < werte.lastIndex) { Text("+$grob") }
        }
    }
}
