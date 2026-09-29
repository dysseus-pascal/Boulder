package coredevices.pebble.kiesel.ansicht

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.ExerciseSegment
import coredevices.pebble.R
import coredevices.pebble.kiesel.KieselEinstellungen
import coredevices.pebble.kiesel.Spur
import coredevices.pebble.kiesel.TrainingDetail
import coredevices.pebble.kiesel.Trainingsanalyse
import coredevices.pebble.kiesel.TrainingsDaten
import java.time.Duration
import java.util.Locale

/**
 * Ein Training im Einzelnen: Zahlen, Strecke, Puls mit Zonen, Saetze oder
 * Bahnen, und was die Uhr dazu notiert hat.
 */
@Composable
internal fun TrainingDetailAnsicht(id: String, setzeTitel: (String) -> Unit) {
    val context = LocalContext.current
    var detail by remember { mutableStateOf<TrainingDetail?>(null) }
    var geladen by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        detail = TrainingsDaten.detail(context, id)
        geladen = true
    }
    val d = detail
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (!geladen) CircularProgressIndicator() else Text(stringResource(R.string.ks_t_noch_keins))
        }
        return
    }
    val t = d.eintrag
    val s = t.sitzung
    val name = s.title ?: stringResource(t.sport.nameId)
    LaunchedEffect(name) { setzeTitel(name) }
    val maxpuls = remember { KieselEinstellungen.stand(context).maxpuls }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Karte {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SportZeichen(t.sport, 48)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(wannText(context, s.startTime), style = MaterialTheme.typography.bodySmall)
                    if (!t.eigen) {
                        Text(stringResource(R.string.ks_n_quelle, d.quelle), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Wert(dauerText(t.minuten), stringResource(R.string.ks_t_dauer))
                if (t.meter > 0) {
                    Wert(kmText(t.meter), stringResource(R.string.ks_t_strecke))
                    val sekunden = Duration.between(s.startTime, s.endTime).seconds
                    if (t.sport.rad) {
                        Wert(String.format(Locale.getDefault(), "%.1f km/h", t.meter / sekunden * 3.6), stringResource(R.string.ks_t_tempo_rad))
                    } else {
                        val jeKm = (sekunden / (t.meter / 1000)).toLong()
                        Wert(String.format(Locale.getDefault(), "%d:%02d /km", jeKm / 60, jeKm % 60), stringResource(R.string.ks_t_tempo))
                    }
                    val auf = Spur.hoehenmeter(d.punkte)
                    if (auf > 0) Wert(String.format(Locale.getDefault(), "%.0f m", auf), stringResource(R.string.ks_t_aufstieg))
                }
                if (d.puls.isNotEmpty()) {
                    Wert(d.puls.map { it.bpm }.average().toInt().toString(), stringResource(R.string.ks_t_puls_schnitt))
                    Wert(d.puls.maxOf { it.bpm }.toString(), stringResource(R.string.ks_t_puls_max))
                }
            }
        }

        if (d.punkte.size >= 2) {
            Streckenkarte(
                d.punkte,
                farbeAlsInt(t.sport.farbe),
                Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(12.dp)),
            )
        } else if (t.sport in setOf(coredevices.pebble.kiesel.Sport.Laufen, coredevices.pebble.kiesel.Sport.Bike,
                coredevices.pebble.kiesel.Sport.Mtb, coredevices.pebble.kiesel.Sport.Wandern)) {
            Text(stringResource(R.string.ks_t_keine_strecke), style = MaterialTheme.typography.bodySmall)
        }

        Karte {
            Text(stringResource(R.string.ks_n_puls), fontWeight = FontWeight.Bold)
            if (d.puls.size < 2) {
                Text(stringResource(R.string.ks_n_kein_puls), style = MaterialTheme.typography.bodySmall)
            } else {
                Pulsdiagramm(d.puls, maxpuls, Color(t.sport.farbe), Modifier.fillMaxWidth().height(160.dp))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.ks_n_zonen), style = MaterialTheme.typography.labelMedium)
                Zonen(Trainingsanalyse.zonenSekunden(d.puls, maxpuls))
            }
        }

        SaetzeUndBahnen(d)

        if (d.spo2.isNotEmpty()) {
            Karte {
                Text(stringResource(R.string.ks_n_spo2), fontWeight = FontWeight.Bold)
                Text(d.spo2.joinToString("  ·  ") { (sek, p) -> String.format(Locale.getDefault(), "%d min: %.0f %%", sek / 60, p) })
            }
        }

        s.notes?.takeIf { it.isNotBlank() }?.let { notiz ->
            Karte {
                Text(stringResource(R.string.ks_n_notiz), style = MaterialTheme.typography.labelMedium)
                Text(notiz, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Die Zeit je Zone als Balken - die laengste fuellt die Breite. */
@Composable
private fun Zonen(sekunden: LongArray) {
    val namen = listOf(
        stringResource(R.string.ks_zone_unter), stringResource(R.string.ks_zone_1), stringResource(R.string.ks_zone_2),
        stringResource(R.string.ks_zone_3), stringResource(R.string.ks_zone_4), stringResource(R.string.ks_zone_5),
    )
    val groesste = sekunden.max().coerceAtLeast(1)
    for (z in 5 downTo 0) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
            Text(if (z == 0) namen[0] else "Z$z ${namen[z]}", Modifier.width(120.dp), style = MaterialTheme.typography.bodySmall)
            Box(Modifier.weight(1f).height(10.dp)) {
                Box(
                    Modifier.fillMaxWidth(sekunden[z].toFloat() / groesste).height(10.dp)
                        .background(ZONENFARBEN[z], RoundedCornerShape(3.dp))
                )
            }
            Text(dauerText(sekunden[z] / 60), Modifier.width(64.dp).padding(start = 6.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Die Saetze beim Kraft, die Bahnen beim Schwimmen - einzeln, wie die Uhr sie zaehlte. */
@Composable
private fun SaetzeUndBahnen(d: TrainingDetail) {
    val s = d.eintrag.sitzung
    val saetze = s.segments.filter { it.segmentType != ExerciseSegment.EXERCISE_SEGMENT_TYPE_REST }
    if (saetze.isNotEmpty()) {
        val pausen = s.segments.filter { it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_REST }
        Karte {
            Text(
                stringResource(R.string.ks_t_saetze, saetze.size, saetze.sumOf { it.repetitions }),
                fontWeight = FontWeight.Bold,
            )
            if (pausen.isNotEmpty()) {
                val schnitt = pausen.map { Duration.between(it.startTime, it.endTime).seconds }.average().toInt()
                Text(stringResource(R.string.ks_t_pause_schnitt, schnitt), style = MaterialTheme.typography.bodySmall)
            }
            saetze.forEachIndexed { i, satz ->
                val dauer = Duration.between(satz.startTime, satz.endTime).seconds
                Text("${i + 1}.  ${satz.repetitions} ×  ·  $dauer s", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (s.laps.isNotEmpty()) {
        val zeiten = s.laps.map { Duration.between(it.startTime, it.endTime).seconds.toInt() }
        Karte {
            Text(
                stringResource(R.string.ks_t_bahnen, zeiten.size, zeiten.average().toInt(), zeiten.min(), zeiten.max()),
                fontWeight = FontWeight.Bold,
            )
            zeiten.forEachIndexed { i, sek -> Text("${i + 1}.  $sek s", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
