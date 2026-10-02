package coredevices.pebble.kiesel.ansicht

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Gesundheitsdaten
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.Schlafurteil
import coredevices.pebble.kiesel.schlafurteil
import io.rebble.libpebblecommon.connection.HealthDataApi
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Eine Nacht im Einzelnen, nach Konzept F: dieselben Daten wie im Zeitstrahl,
 * aber gross - die Dauer, die Phasen mit Anteil und Zeit, der Verlauf durch
 * die Nacht, der Koerper im Schlaf und die Woche davor. Geblaettert wird hier,
 * nicht ueber die Navigation - sonst stuende jede Nacht einzeln im
 * Zurueck-Stapel.
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
    val grau = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(enabled = d.vorige != null, onClick = { d.vorige?.let { beginn = it } }) { Text("‹ " + stringResource(R.string.ks_h_frueher)) }
            TextButton(onClick = oeffneTrend) { Text(stringResource(R.string.ks_h_trends)) }
            TextButton(enabled = d.naechste != null, onClick = { d.naechste?.let { beginn = it } }) { Text(stringResource(R.string.ks_h_spaeter) + " ›") }
        }

        // DIE DAUER GROSS: wer die Nacht oeffnet, will zuerst wissen, wie lang sie war.
        Kasten {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(zahlMitEinheit(stundenMinuten(n.schlafMinuten), "h", 52, 22), lineHeight = 58.sp)
                val urteil = Gesundheitsrechnung.schlafurteil(n.schlafMinuten)
                Text(
                    stringResource(
                        when (urteil) {
                            Schlafurteil.AUSGERUHT -> R.string.ks_h_urteil_ausgeruht
                            Schlafurteil.KURZ -> R.string.ks_h_urteil_kurz
                            Schlafurteil.SEHR_KURZ -> R.string.ks_h_urteil_sehr_kurz
                        }
                    ),
                    fontSize = 18.sp, fontWeight = FontWeight.Medium,
                    color = if (urteil == Schlafurteil.AUSGERUHT) PHASENFARBEN[1] else toene().warnung,
                )
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Zeitpaar(uhrzeit(context, n.einschlafen), stringResource(R.string.ks_n_eingeschlafen))
                    Zeitpaar(uhrzeit(context, n.aufwachen), stringResource(R.string.ks_n_aufgewacht))
                }
            }
        }

        if (n.mitPhasen) {
            val imBett = n.phasen[0] + n.phasen[1] + n.phasen[2] + n.phasen[3] + n.phasen[4]
            Kasten {
                Kopf(stringResource(R.string.ks_n_phasen), "· " + stringResource(R.string.ks_n_im_bett, stundenMinuten(imBett)))
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                    val reihe = listOf(2 to R.string.ks_h_tief, 3 to R.string.ks_h_rem, 1 to R.string.ks_h_leicht, 0 to R.string.ks_h_wach)
                    Phasenbalken(reihe.map { (p, _) -> n.phasen[p] to PHASENFARBEN[p] })
                    Spacer(Modifier.height(8.dp))
                    reihe.forEachIndexed { i, (p, name) ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).background(PHASENFARBEN[p], CircleShape))
                            Text(stringResource(name), Modifier.weight(1f).padding(start = 12.dp), fontSize = 16.sp)
                            Text("${(n.phasen[p] * 100f / imBett.coerceAtLeast(1)).roundToInt()} %", Modifier.width(48.dp), fontSize = 13.sp, color = grau, textAlign = TextAlign.End)
                            Text(zahlMitEinheit(stundenMinuten(n.phasen[p]), "h", 22, 13), Modifier.width(84.dp), textAlign = TextAlign.End, maxLines = 1)
                        }
                    }
                }
            }
        }

        // Geladen wird eine halbe Stunde vor und nach dem Schlaf; gezeigt nur die
        // Nacht selbst - Punkte davor zeichnete die Kurve sonst in die Achsenspalte.
        val von = n.abschnitte.minOfOrNull { it.von.epochSecond } ?: n.einschlafen.epochSecond
        val bis = n.abschnitte.maxOfOrNull { it.bis.epochSecond } ?: n.aufwachen.epochSecond
        val puls = d.puls.filter { it.first in von..bis }
        val spo2Punkte = d.spo2.filter { it.first in von..bis }
        if (n.abschnitte.isNotEmpty() || puls.isNotEmpty()) {
            Kasten {
                Kopf(stringResource(R.string.ks_n_durch_die_nacht))
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                    if (n.abschnitte.isNotEmpty()) Hypnogramm(n, hoehe = 150.dp)
                    if (puls.isNotEmpty()) {
                        Text(stringResource(R.string.ks_n_puls), fontSize = 13.sp, color = grau, modifier = Modifier.padding(top = 10.dp))
                        Tageskurve(puls, von, bis, Color(0xFFC0392B), hoehe = 110.dp)
                    }
                    if (spo2Punkte.isNotEmpty()) {
                        Text("SpO2", fontSize = 13.sp, color = grau, modifier = Modifier.padding(top = 10.dp))
                        Tageskurve(spo2Punkte, von, bis, Color(0xFF2E7C8A), hoehe = 90.dp, trendFenster = 1800)
                    }
                }
            }
        } else if (!n.mitPhasen) {
            Text(stringResource(R.string.ks_h_ohne_phasen), fontSize = 13.sp, color = grau)
        }

        // Der Koerper im Schlaf, je gegen den Schnitt der Naechte davor.
        val ruheSchnitt = d.davor.map { it.ruhepuls }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
        val hrvSchnitt = d.davor.map { it.hrv }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
        // Dieselben Punkte wie die Kurve, damit "lowest" einer ist, den man darin sieht.
        val spo2 = spo2Punkte.map { it.second }
        if (n.ruhepuls != null || n.hrv != null || spo2.isNotEmpty()) {
            Kasten {
                Kopf(stringResource(R.string.ks_n_koerper))
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    n.ruhepuls?.let { Koerperwert("$it", "bpm", stringResource(R.string.ks_h_ruhepuls), ruheSchnitt?.let { s -> stringResource(R.string.ks_n_schnitt, "$s") }, Modifier.weight(1f)) }
                    n.hrv?.let { Koerperwert("$it", "ms", stringResource(R.string.ks_h_hrv), hrvSchnitt?.let { s -> stringResource(R.string.ks_n_schnitt, "$s") }, Modifier.weight(1f)) }
                    if (spo2.isNotEmpty()) Koerperwert(
                        "${spo2.average().roundToInt()}", "%", stringResource(R.string.ks_d_spo2),
                        stringResource(R.string.ks_n_tiefster, spo2.min()), Modifier.weight(1f),
                    )
                }
            }
        }

        // Die Woche davor als Saeulen, diese Nacht hervorgehoben.
        val reihe = d.davor.map { it.schlafMinuten to it.beginnUtc } + (n.schlafMinuten to n.beginnUtc)
        if (reihe.size >= 2) {
            val schnitt = reihe.map { it.first }.average().roundToInt()
            Kasten {
                Kopf(stringResource(R.string.ks_n_sieben), "· " + stringResource(R.string.ks_n_schnitt_h, stundenMinuten(schnitt)))
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val spitze = maxOf(9 * 60, reihe.maxOf { it.first }).toFloat()
                    reihe.forEachIndexed { i, (minuten, b) ->
                        val diese = i == reihe.lastIndex
                        // Die Nacht gehoert zum Tag, an dem man aufwacht - wie im Zeitstrahl.
                        val tag = Instant.ofEpochSecond(b).atZone(zone).plusHours(12).toLocalDate()
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stundenMinuten(minuten), fontSize = 11.sp, color = grau, maxLines = 1)
                            val farbe = if (diese) PHASENFARBEN[1] else PHASENFARBEN[1].copy(alpha = 0.4f)
                            Canvas(Modifier.fillMaxWidth().height(70.dp)) {
                                val h = size.height * minuten / spitze
                                drawRoundRect(farbe, Offset(0f, size.height - h), Size(size.width, h), CornerRadius(4.dp.toPx()))
                            }
                            Text(
                                tag.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2), fontSize = 12.sp,
                                fontWeight = if (diese) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (diese) MaterialTheme.colorScheme.onSurface else grau,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun Zeitpaar(zeit: String, name: String) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(zeit, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(name, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 1.dp))
    }
}

@Composable
private fun Koerperwert(zahl: String, einheit: String, name: String, unter: String?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(zahlMitEinheit(zahl, einheit, 26, 14), lineHeight = 32.sp, maxLines = 1)
        Text(name, fontSize = 13.sp)
        if (unter != null) Text(unter, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Die Phasen als breiter Balken - Anteile, gross genug zum Lesen. */
@Composable
private fun Phasenbalken(teile: List<Pair<Int, Color>>) {
    val summe = teile.sumOf { it.first }.coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
        val luecke = 2.dp.toPx()
        val sichtbar = teile.filter { it.first > 0 }
        val platz = size.width - luecke * (sichtbar.size - 1).coerceAtLeast(0)
        var x = 0f
        sichtbar.forEach { (wert, farbe) ->
            val breite = platz * wert / summe
            drawRoundRect(farbe, Offset(x, 0f), Size(breite, size.height), CornerRadius(5.dp.toPx()))
            x += breite + luecke
        }
    }
}
