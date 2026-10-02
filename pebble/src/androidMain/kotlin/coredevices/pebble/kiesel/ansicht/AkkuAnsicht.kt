package coredevices.pebble.kiesel.ansicht

import android.text.format.DateFormat
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Akkurechnung
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.Trainingsanalyse
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.NachtEntity
import org.koin.compose.koinInject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Der Akku der Uhr - aus den Naechten (siehe Akkurechnung). */
@Composable
internal fun AkkuAnsicht() {
    val context = LocalContext.current
    val api = koinInject<HealthDataApi>()
    val libPebble = koinInject<LibPebble>()
    val uhren by libPebble.watches.collectAsState()
    val aktuell = uhren.filterIsInstance<ConnectedPebbleDevice>().firstNotNullOfOrNull { it.batteryLevel }
    var naechte by remember { mutableStateOf<List<NachtEntity>?>(null) }
    suspend fun lade() {
        val jetzt = Instant.now()
        naechte = runCatching { api.getNaechte(jetzt.minus(Duration.ofDays(14)).epochSecond, jetzt.epochSecond) }.getOrDefault(emptyList())
    }
    // Einmal beim Oeffnen, und wieder, wenn die Uhr etwas schickt - etwa die
    // Nacht, die gerade ankommt.
    LaunchedEffect(Unit) {
        lade()
        libPebble.healthDataUpdated.collect { lade() }
    }
    val geladen = naechte
    if (geladen == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val stand = remember(geladen, aktuell) { Akkurechnung.rechne(geladen, aktuell, Instant.now()) }
    val zone = ZoneId.systemDefault()
    fun uhr(t: Instant) = DateFormat.getTimeFormat(context).format(java.util.Date(t.toEpochMilli()))
    fun zahl(w: Double?, nachkomma: Int) = w?.let { String.format(Locale.getDefault(), "%.${nachkomma}f", it) } ?: "–"
    // Ohne Wert nur der Strich: "≈ –" las sich wie ein Darstellungsfehler.
    fun ungefaehr(w: Double?, nachkomma: Int) = w?.let { "≈ " + zahl(it, nachkomma) } ?: "–"
    // Dieselben Naechte wie die Schlafseite, die laengste je Aufwachtag: ein Nachschlaf
    // am Morgen stand sonst als eigene Nacht da. Gerechnet wird weiter mit allen
    // Stuecken - jedes traegt zwei echte Akkustaende.
    val tagesnaechte = remember(geladen) { Gesundheitsrechnung.jeTag(geladen, zone).map { it.beginnUtc }.toSet() }
    val liste = stand.naechte.filter { it.beginnUtc in tagesnaechte }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Karte {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Wert(aktuell?.let { "$it %" } ?: "–", stringResource(R.string.ks_akku_jetzt))
                // Abgerundet wie die Restzeit im Satz auf Health: lieber zu wenig als zu viel.
                Wert(ungefaehr(stand.tageUebrig?.let { kotlin.math.floor(it * 10) / 10 }, 1), stringResource(R.string.ks_akku_uebrig))
                Wert(ungefaehr(stand.jeTag, 0), stringResource(R.string.ks_akku_je_tag))
                Wert(ungefaehr(stand.tageJeLadung, 1), stringResource(R.string.ks_akku_je_ladung))
                Wert(zahl(stand.nachtJeStunde, 1), stringResource(R.string.ks_akku_nacht))
                Wert(zahl(stand.tagJeStunde, 1), stringResource(R.string.ks_akku_tag))
            }
            // Nur der Leerzustand; wie geschaetzt wird, erklaert die Seite nicht (keine Textwaende).
            if (stand.naechte.isEmpty()) {
                Text(stringResource(R.string.ks_akku_keine), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (stand.punkte.size >= 2) {
            Ueberschrift(stringResource(R.string.ks_akku_verlauf))
            Karte {
                // Die Achse in Tagen ab Mitternacht des ersten Tages: jeder ganze Wert ist ein
                // Tagesbeginn und traegt sein Datum. Gezaehlte Tage ("1") sagten ohne Erklaerung nichts.
                val ersterTag = Instant.ofEpochSecond(stand.punkte.first().first).atZone(zone).toLocalDate()
                val mitternacht = ersterTag.atStartOfDay(zone).toEpochSecond()
                Verlauf(
                    stand.punkte.map { (t, p) -> Trainingsanalyse.Verlaufspunkt((t - mitternacht) / 86400.0, p.toDouble()) },
                    Color(0xFF2CB0C6), "%", zaehlachse = true, bereich = 0.0..100.0,
                    achsenText = { kurzDatum(ersterTag.plusDays(it.toLong())) },
                )
            }
        }
        if (liste.isNotEmpty()) {
            Ueberschrift(stringResource(R.string.ks_akku_naechte))
            Karte {
                liste.asReversed().forEach { n ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(n.aufwachen.atZone(zone).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)), fontWeight = FontWeight.Bold)
                            Text(uhr(n.einschlafen) + " – " + uhr(n.aufwachen), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${n.prozentVon} % → ${n.prozentBis} %", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.padding(6.dp))
                        Text(
                            if (n.geladen) stringResource(R.string.ks_akku_geladen) else n.jeStunde?.let { zahl(it, 1) + " %/h" } ?: "–",
                            fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
