package coredevices.pebble.kiesel.ansicht

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Gesundheitsdaten
import io.rebble.libpebblecommon.connection.HealthDataApi
import org.koin.compose.koinInject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Eine Nacht im Einzelnen - wie NachtActivity in Kiesel-Helper: das
 * Hypnogramm gross, darunter Puls und SpO2 derselben Stunden, die Phasen und
 * die Wachphasen. Geblaettert wird hier, nicht ueber die Navigation - sonst
 * stuende jede Nacht einzeln im Zurueck-Stapel.
 */
@Composable
internal fun NachtAnsicht(start: Long, setzeTitel: (String) -> Unit, oeffneTrend: () -> Unit) {
    val context = LocalContext.current
    val api = koinInject<HealthDataApi>()
    var beginn by remember { mutableLongStateOf(start) }
    var detail by remember { mutableStateOf<Gesundheitsdaten.NachtDetail?>(null) }
    var geladen by remember { mutableStateOf(false) }
    LaunchedEffect(beginn) {
        geladen = false
        detail = Gesundheitsdaten.nacht(api, beginn)
        geladen = true
    }
    val d = detail
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (!geladen) CircularProgressIndicator() else Text(stringResource(R.string.ks_h_keine_nacht))
        }
        return
    }
    val n = d.nacht
    val zone = ZoneId.systemDefault()
    val titel = n.aufwachen.atZone(zone).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    LaunchedEffect(titel) { setzeTitel(titel) }
    fun uhr(t: Instant) = DateFormat.getTimeFormat(context).format(java.util.Date(t.toEpochMilli()))

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(enabled = d.vorige != null, onClick = { d.vorige?.let { beginn = it } }) { Text("‹ " + stringResource(R.string.ks_h_frueher)) }
            TextButton(onClick = oeffneTrend) { Text(stringResource(R.string.ks_h_trends)) }
            TextButton(enabled = d.naechste != null, onClick = { d.naechste?.let { beginn = it } }) { Text(stringResource(R.string.ks_h_spaeter) + " ›") }
        }
        Karte {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Wert(dauerText(n.schlafMinuten.toLong()), stringResource(R.string.ks_h_geschlafen))
                n.ruhepuls?.let { Wert("$it bpm", stringResource(R.string.ks_h_ruhepuls)) }
                n.hrv?.let { Wert("$it ms", stringResource(R.string.ks_h_hrv)) }
            }
            Text(
                uhr(n.einschlafen) + " – " + uhr(n.aufwachen),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (n.abschnitte.isNotEmpty()) Hypnogramm(n, hoehe = 150.dp)
            val von = n.abschnitte.minOfOrNull { it.von.epochSecond } ?: n.einschlafen.epochSecond
            val bis = n.abschnitte.maxOfOrNull { it.bis.epochSecond } ?: n.aufwachen.epochSecond
            if (d.puls.isNotEmpty()) {
                Text(stringResource(R.string.ks_n_puls), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                Tageskurve(d.puls.filter { it.first in von..bis }, von, bis, Color(0xFFC0392B), hoehe = 110.dp)
            }
            if (d.spo2.isNotEmpty()) {
                Text("SpO2", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                Tageskurve(d.spo2, von, bis, Color(0xFF2E7C8A), hoehe = 90.dp)
            }
        }
        if (n.mitPhasen) {
            Karte {
                Text(stringResource(R.string.ks_h_phasen), style = MaterialTheme.typography.labelMedium)
                val summe = n.phasen.sum().coerceAtLeast(1)
                listOf(0 to R.string.ks_h_wach, 3 to R.string.ks_h_rem, 1 to R.string.ks_h_leicht, 2 to R.string.ks_h_tief).forEach { (p, name) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Box(Modifier.size(12.dp).background(PHASENFARBEN[p], CircleShape))
                        Text(stringResource(name), Modifier.weight(1f).padding(start = 10.dp))
                        Text(dauerText(n.phasen[p].toLong()), fontWeight = FontWeight.Bold)
                        Text(
                            "${n.phasen[p] * 100 / summe} %", Modifier.width(52.dp),
                            textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        val wach = n.abschnitte.filter { it.phase == 0 }
        if (wach.isNotEmpty()) {
            Karte {
                Text(stringResource(R.string.ks_h_wachphasen), style = MaterialTheme.typography.labelMedium)
                wach.forEach {
                    Text(uhr(it.von) + " – " + uhr(it.bis) + "  ·  " + dauerText(Duration.between(it.von, it.bis).toMinutes()))
                }
            }
        }
        if (!n.mitPhasen) {
            Text(stringResource(R.string.ks_h_ohne_phasen), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
    }
}
