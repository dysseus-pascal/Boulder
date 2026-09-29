package coredevices.pebble.kiesel.ansicht

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coredevices.pebble.R
import coredevices.pebble.kiesel.GesundheitsStand
import coredevices.pebble.kiesel.Gesundheitsdaten
import coredevices.pebble.kiesel.Gesundheitsrechnung
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

private val FARBE_BEWEGUNG = Color(0xFF4F8A3A)
private val FARBE_SCHLAF = Color(0xFF2F5597)
private val FARBE_HERZ = Color(0xFFC0392B)
private val FARBE_SPO2 = Color(0xFF2E7C8A)
private val FARBE_WASSER = Color(0xFF3A86C8)

private const val SCHRITTZIEL = 10_000.0
private const val SCHLAFZIEL_MIN = 8 * 60.0

/**
 * Die Gesundheitsuebersicht - aus Kiesel-Helper (GesundheitTab und
 * ErnaehrungTab), zu einer Seite zusammengelegt: Boulder hat einen Reiter
 * dafuer, nicht zwei.
 */
@Composable
internal fun GesundheitAnsicht(oeffneNacht: (Long) -> Unit, oeffneTrend: (String) -> Unit) {
    val context = LocalContext.current
    val api = koinInject<HealthDataApi>()
    val libPebble = koinInject<LibPebble>()
    val scope = rememberCoroutineScope()
    var stand by remember { mutableStateOf<GesundheitsStand?>(null) }
    var neu by remember { mutableIntStateOf(0) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { neu++ }
    // Neue Minutendaten von der Uhr: dann stimmen Schritte und Puls nicht mehr.
    LaunchedEffect(Unit) { libPebble.healthDataUpdated.collect { neu++ } }
    LaunchedEffect(neu) { stand = Gesundheitsdaten.lies(context, api) }

    val s = stand
    if (s == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Spacer(Modifier.height(2.dp)) }
        item { Bewegung(s) { oeffneTrend("bewegung") } }
        item { Schlaf(s, oeffneNacht) }
        item { Herz(s) { oeffneTrend("herz") } }
        if (s.spo2.isNotEmpty()) item { Sauerstoff(s) }
        item { Wasser(s) { oeffneTrend("ernaehrung") } }
        if (s.supplemente != null) item { Praeparate(s) }
        item {
            Koffein(s) { name, mg ->
                scope.launch {
                    Gesundheitsdaten.trinkeKoffein(context, name, mg)
                    neu++
                }
            }
        }
        item {
            Energie(s.energie) {
                Gesundheitsdaten.setzeEnergie(context, LocalDate.now(), it)
                neu++
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

private fun wochentag(tag: LocalDate): String = tag.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2)

private fun uhrzeit(context: android.content.Context, t: Instant): String =
    DateFormat.getTimeFormat(context).format(java.util.Date(t.toEpochMilli()))

@Composable
private fun Werte(inhalt: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { inhalt() }
}

@Composable
private fun Hinweis(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Bewegung(s: GesundheitsStand, trend: () -> Unit) {
    Karte(onClick = trend) {
        Text(stringResource(R.string.ks_h_bewegung), style = MaterialTheme.typography.labelMedium)
        Werte {
            Wert(String.format(Locale.getDefault(), "%,d", s.schritte), stringResource(R.string.ks_h_schritte))
            Wert(dauerText(s.aktivMinuten), stringResource(R.string.ks_h_aktiv))
            Wert(kmText(s.meter), stringResource(R.string.ks_t_strecke))
            Wert("${s.kcal} kcal", stringResource(R.string.ks_h_kcal))
        }
        Hinweis(stringResource(R.string.ks_h_schritte_woche))
        Saeulen(s.wocheSchritte.map { (t, w) -> wochentag(t) to w.toDouble() }, FARBE_BEWEGUNG, SCHRITTZIEL)
    }
}

@Composable
private fun Schlaf(s: GesundheitsStand, oeffneNacht: (Long) -> Unit) {
    val context = LocalContext.current
    Karte(onClick = s.nacht?.let { n -> { oeffneNacht(n.beginnUtc) } }) {
        Text(stringResource(R.string.ks_h_schlaf), style = MaterialTheme.typography.labelMedium)
        val n = s.nacht
        if (n == null) {
            Hinweis(stringResource(R.string.ks_h_keine_nacht))
        } else {
            Werte {
                Wert(dauerText(n.schlafMinuten.toLong()), stringResource(R.string.ks_h_geschlafen))
                if (n.mitPhasen) {
                    Wert(dauerText(n.phasen[2].toLong()), stringResource(R.string.ks_h_tief))
                    Wert(dauerText(n.phasen[3].toLong()), stringResource(R.string.ks_h_rem))
                    Wert(dauerText(n.phasen[1].toLong()), stringResource(R.string.ks_h_leicht))
                }
            }
            val wach = if (n.phasen[0] > 0) "  ·  " + stringResource(R.string.ks_h_wach_min, n.phasen[0]) else ""
            Hinweis(uhrzeit(context, n.einschlafen) + " – " + uhrzeit(context, n.aufwachen) + wach)
            if (n.abschnitte.isNotEmpty()) {
                Hypnogramm(n)
                if (n.mitPhasen) Phasenlegende()
            }
            if (!n.mitPhasen) Hinweis(stringResource(R.string.ks_h_ohne_phasen))
        }
        Hinweis(stringResource(R.string.ks_h_schlaf_woche))
        Saeulen(s.wocheSchlaf.map { (t, m) -> wochentag(t) to m.toDouble() }, FARBE_SCHLAF, SCHLAFZIEL_MIN)
    }
}

@Composable
private fun Phasenlegende() {
    val namen = listOf(0 to R.string.ks_h_wach, 3 to R.string.ks_h_rem, 1 to R.string.ks_h_leicht, 2 to R.string.ks_h_tief)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        namen.forEach { (phase, name) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(PHASENFARBEN[phase], CircleShape))
                Text(stringResource(name), Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Herz(s: GesundheitsStand, trend: () -> Unit) {
    Karte(onClick = trend) {
        Text(stringResource(R.string.ks_h_herz), style = MaterialTheme.typography.labelMedium)
        Werte {
            s.nacht?.ruhepuls?.let { Wert("$it bpm", stringResource(R.string.ks_h_ruhepuls)) }
            s.nacht?.hrv?.let { Wert("$it ms", stringResource(R.string.ks_h_hrv)) }
            if (s.puls.isNotEmpty()) {
                Wert("${s.puls.minOf { it.second }}–${s.puls.maxOf { it.second }}", stringResource(R.string.ks_h_spanne_24h))
            }
        }
        if (s.nacht?.ruhepuls == null && s.nacht?.hrv == null) Hinweis(stringResource(R.string.ks_h_ruhepuls_nacht))
        if (s.puls.isEmpty()) {
            Hinweis(stringResource(R.string.ks_h_kein_puls))
        } else {
            val jetzt = Instant.now().epochSecond
            Tageskurve(s.puls, jetzt - 24 * 3600, jetzt, FARBE_HERZ)
        }
    }
}

@Composable
private fun Sauerstoff(s: GesundheitsStand) {
    Karte {
        Text(stringResource(R.string.ks_v_spo2_titel), style = MaterialTheme.typography.labelMedium)
        val werte = s.spo2.map { it.second }
        Werte {
            Wert("${werte.average().roundToInt()} %", stringResource(R.string.ks_v_spo2_schnitt))
            Wert("${werte.min()} %", stringResource(R.string.ks_v_spo2_tiefster))
            Wert("${werte.size}", stringResource(R.string.ks_h_messungen))
        }
        val jetzt = Instant.now().epochSecond
        Tageskurve(s.spo2, jetzt - 24 * 3600, jetzt, FARBE_SPO2, hoehe = 100.dp)
        Hinweis(stringResource(R.string.ks_v_spo2_lang))
    }
}

@Composable
private fun Wasser(s: GesundheitsStand, trend: () -> Unit) {
    val context = LocalContext.current
    Karte(onClick = trend) {
        Text(stringResource(R.string.ks_h_wasser), style = MaterialTheme.typography.labelMedium)
        val ziel = s.wasserZiel
        Werte {
            Wert(String.format(Locale.getDefault(), "%.2f l", s.wasserMl / 1000.0), stringResource(R.string.ks_h_getrunken))
            if (ziel != null) {
                Wert(String.format(Locale.getDefault(), "%.2f l", ziel.ml / 1000.0), stringResource(R.string.ks_h_ziel))
                // Aufgerundet: ein halbes Glas fehlt als ganzes.
                val fehlt = ((ziel.ml - s.wasserMl).coerceAtLeast(0) + ziel.glasMl - 1) / ziel.glasMl.coerceAtLeast(1)
                Wert("$fehlt", stringResource(R.string.ks_h_glaeser_fehlen))
            }
        }
        if (s.glaeser.isNotEmpty()) Hinweis(s.glaeser.joinToString("  ·  ") { uhrzeit(context, it) })
        if (ziel == null) Hinweis(stringResource(R.string.ks_h_kein_drinktervall))
        Saeulen(s.wocheWasser.map { (t, ml) -> wochentag(t) to ml.toDouble() }, FARBE_WASSER, ziel?.ml?.toDouble())
    }
}

@Composable
private fun Praeparate(s: GesundheitsStand) {
    val stand = s.supplemente ?: return
    val platz = stringResource(R.string.ks_s_platz_n)
    val liste = stand.heute { String.format(platz, it) }
    Karte {
        Text(stringResource(R.string.ks_h_praeparate), style = MaterialTheme.typography.labelMedium)
        Text(stringResource(R.string.ks_s_davon_von, liste.count { it.genommen }, liste.size), fontWeight = FontWeight.Bold)
        liste.forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (e.genommen) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = null,
                    tint = if (e.genommen) FARBE_BEWEGUNG else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(e.name, Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun Koffein(s: GesundheitsStand, trinke: (String, Int) -> Unit) {
    val context = LocalContext.current
    val heute = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val heuteMg = s.koffein.filter { it.first.atZone(zone).toLocalDate() == heute }.sumOf { it.second }
    Karte {
        Text(stringResource(R.string.ks_h_koffein), style = MaterialTheme.typography.labelMedium)
        Werte {
            Wert("${heuteMg.roundToInt()} mg", stringResource(R.string.ks_h_heute))
            Wert("${Gesundheitsrechnung.koffeinAktiv(s.koffein, Instant.now()).roundToInt()} mg", stringResource(R.string.ks_h_wirkt_noch))
            s.koffein.lastOrNull()?.let { Wert(uhrzeit(context, it.first), stringResource(R.string.ks_h_zuletzt)) }
        }
        val getraenke = listOf(
            stringResource(R.string.ks_h_kaffee) to 80, stringResource(R.string.ks_h_espresso) to 60,
            stringResource(R.string.ks_h_tee) to 40, stringResource(R.string.ks_h_energy) to 80,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            getraenke.forEach { (name, mg) -> OutlinedButton(onClick = { trinke(name, mg) }) { Text("$name $mg") } }
        }
    }
}

@Composable
private fun Energie(wert: Int?, setze: (Int) -> Unit) {
    Karte {
        Text(stringResource(R.string.ks_h_wie_war_tag), style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            (1..5).forEach { n -> FilterChip(selected = wert == n, onClick = { setze(n) }, label = { Text("$n") }) }
        }
        Hinweis(stringResource(R.string.ks_h_energie_text))
    }
}
