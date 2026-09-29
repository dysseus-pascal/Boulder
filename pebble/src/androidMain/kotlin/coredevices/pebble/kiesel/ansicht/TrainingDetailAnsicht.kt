package coredevices.pebble.kiesel.ansicht

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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.ExerciseSegment
import coredevices.pebble.R
import org.koin.compose.koinInject
import io.rebble.libpebblecommon.database.entity.HealthGender
import io.rebble.libpebblecommon.connection.LibPebble
import coredevices.pebble.kiesel.KieselEinstellungen
import coredevices.pebble.kiesel.Sport
import coredevices.pebble.kiesel.Spur
import coredevices.pebble.kiesel.TrainingDetail
import coredevices.pebble.kiesel.Trainingsanalyse
import coredevices.pebble.kiesel.Trainingsanalyse.Verlaufspunkt
import coredevices.pebble.kiesel.TrainingsDaten
import java.time.Duration
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Ein Training im Einzelnen - je Sportart anders.
 *
 * DIE KARTE GEHOERT NICHT ZUM YOGA (aus Kiesel-Helper). Jede Art hat ihre
 * eigene Frage: beim Laufen und auf dem Rad, wo es schnell war; beim
 * Wandern, wo es steil war; beim Kraft, wie schnell der Puls in der Pause
 * faellt; beim Yoga, ob man ruhiger wurde; im Becken, ob die Bahnen gleich
 * blieben. Die Seite zeigt die Antwort auf diese Frage und nicht dieselben
 * Bilder fuer alle.
 */
@Composable
internal fun TrainingDetailAnsicht(id: String, setzeTitel: (String) -> Unit) {
    val context = LocalContext.current
    // Gewicht, Alter und Geschlecht der Uhr - fuer die Kalorien aus dem Puls.
    val koerper by koinInject<LibPebble>().healthSettings.collectAsState(null)
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
    val ton = Color(t.sport.farbe)

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
                        Wert(tempoText(t.meter / sekunden, false), stringResource(R.string.ks_t_tempo))
                    }
                    val auf = Spur.hoehenmeter(d.punkte)
                    if (auf > 0) Wert(String.format(Locale.getDefault(), "%.0f m", auf), stringResource(R.string.ks_t_aufstieg))
                }
                if (d.puls.isNotEmpty()) {
                    Wert(d.puls.map { it.bpm }.average().toInt().toString(), stringResource(R.string.ks_t_puls_schnitt))
                    Wert(d.puls.maxOf { it.bpm }.toString(), stringResource(R.string.ks_t_puls_max))
                    koerper?.let { k ->
                        val geschlecht = when (k.gender) {
                            HealthGender.Male -> Trainingsanalyse.Geschlecht.Mann
                            HealthGender.Female -> Trainingsanalyse.Geschlecht.Frau
                            HealthGender.Other -> Trainingsanalyse.Geschlecht.Andere
                        }
                        Trainingsanalyse.kalorien(d.puls, k.weightDag / 100.0, k.ageYears, geschlecht)?.let {
                            Wert("≈ " + it.roundToInt(), stringResource(R.string.ks_t_kcal))
                        }
                    }
                }
            }
        }

        // Die Zonen gibt es ueberall, wo ein Puls gemessen wurde.
        if (d.puls.size >= 2) Zonenkarte(d, maxpuls)

        when (t.sport) {
            Sport.Kraft -> Kraft(d, ton, maxpuls)
            Sport.Yoga -> Yoga(d, ton, maxpuls)
            Sport.Schwimmen -> Schwimmen(d, ton, maxpuls)
            else -> Strecke(d, ton, maxpuls)
        }
        Sauerstoff(d, ton)

        s.notes?.takeIf { it.isNotBlank() }?.let { notiz ->
            Karte {
                Text(stringResource(R.string.ks_n_notiz), style = MaterialTheme.typography.labelMedium)
                Text(notiz, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Tempo als Text: auf dem Rad km/h, sonst min/km; unter 0,2 m/s gibt es keins. */
private fun tempoText(ms: Double, rad: Boolean): String = when {
    rad -> String.format(Locale.getDefault(), "%.1f km/h", ms * 3.6)
    ms > 0.2 -> (1000 / ms).roundToInt().let { String.format(Locale.getDefault(), "%d:%02d /km", it / 60, it % 60) }
    else -> "–"
}

@Composable
private fun Hinweis(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Werte(inhalt: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { inhalt() }
}

// --- Zonen ---------------------------------------------------------------------

@Composable
private fun Zonenkarte(d: TrainingDetail, maxpuls: Int) {
    val zonen = Trainingsanalyse.zonenSekunden(d.puls, maxpuls)
    val summe = zonen.sum().coerceAtLeast(1)
    Ueberschrift(stringResource(R.string.ks_v_pulszonen))
    Karte {
        Hinweis(stringResource(R.string.ks_v_zeit_je_zone, maxpuls))
        Zonenbalken(zonen)
        Zonenliste(zonen, maxpuls)
        Text(
            stringResource(
                when {
                    (zonen[4] + zonen[5]) * 100 / summe >= 50 -> R.string.ks_v_hart
                    zonen[2] + zonen[3] >= summe * 6 / 10 -> R.string.ks_v_grundlage
                    zonen[0] + zonen[1] >= summe / 2 -> R.string.ks_v_locker
                    else -> R.string.ks_v_gemischt
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Der Puls ueber die Zeit, mit den Zonen dahinter und senkrechten Baendern (Saetze, Bahnen). */
@Composable
private fun PulsUeberZeit(d: TrainingDetail, ton: Color, maxpuls: Int, text: String, xBaender: List<Pair<Double, Double>> = emptyList()) {
    if (d.puls.size < 2) return
    Ueberschrift(stringResource(R.string.ks_v_puls_zeit))
    Karte {
        Hinweis(text)
        Verlauf(
            d.puls.map { Verlaufspunkt(it.sekunde.toDouble(), it.bpm.toDouble()) },
            ton, "bpm", baender = zonenbaender(maxpuls), zeitachse = true, xBaender = xBaender,
        )
    }
}

// --- Laufen, Bike, MTB, Wandern --------------------------------------------------

@Composable
private fun Strecke(d: TrainingDetail, ton: Color, maxpuls: Int) {
    val sport = d.eintrag.sport
    val strecke = remember(d) { Trainingsanalyse.strecke(d.punkte) }
    if (strecke.size < 2) {
        if (sport in setOf(Sport.Laufen, Sport.Bike, Sport.Mtb, Sport.Wandern)) {
            Hinweis(stringResource(R.string.ks_t_keine_strecke))
        }
        PulsUeberZeit(d, ton, maxpuls, stringResource(R.string.ks_v_ohne_strecke_zeit))
        if (d.puls.size < 2) Karte { Hinweis(stringResource(R.string.ks_v_nichts_weiter)) }
        return
    }
    val rad = sport.rad
    val wandern = sport == Sport.Wandern
    val hoehe = remember(strecke) { Trainingsanalyse.hoeheUeberStrecke(strecke) }
    val mitHoehe = hoehe.size >= 2 && hoehe.maxOf { it.wert } - hoehe.minOf { it.wert } >= 10
    val (langsam, schnell) = remember(strecke) { Trainingsanalyse.tempoSpanne(strecke) }

    // BEIM WANDERN ZUERST DIE HOEHE: dort ist sie die Geschichte der Tour.
    if (wandern && mitHoehe) Hoehenkarte(d, hoehe, strecke)

    val pulsStrecke = remember(d, strecke) { Trainingsanalyse.pulsUeberStrecke(d.puls, strecke) }
    if (pulsStrecke.size >= 2) {
        Ueberschrift(stringResource(R.string.ks_v_puls_strecke))
        Karte {
            Hinweis(stringResource(R.string.ks_v_puls_stieg))
            Verlauf(pulsStrecke, ton, "bpm", baender = zonenbaender(maxpuls))
        }
    }

    Ueberschrift(stringResource(R.string.ks_v_tempo))
    Karte {
        val bewegt = Trainingsanalyse.bewegt(strecke)
        Werte {
            // DER SCHNITT IN BEWEGUNG: die Zeit an der Ampel steht oben in
            // der Dauer, hier geht es darum, wie schnell es lief.
            Wert(tempoText(strecke.last().meter / bewegt.coerceAtLeast(1), rad), stringResource(R.string.ks_v_schnitt))
            Wert(tempoText(strecke.maxOf { it.tempo }, rad), stringResource(R.string.ks_v_spitze))
            if (wandern && mitHoehe) {
                val mh = Spur.hoehenmeter(d.punkte) / (bewegt.coerceAtLeast(60) / 3600.0)
                Wert(String.format(Locale.getDefault(), "%.0f m/h", mh), stringResource(R.string.ks_t_aufstieg))
            } else {
                Wert(dauerText(bewegt / 60), stringResource(R.string.ks_v_bewegt))
            }
        }
        Hinweis(stringResource(if (wandern) R.string.ks_v_ueber_strecke else R.string.ks_v_ueber_strecke_farbe))
        Verlauf(
            Trainingsanalyse.tempoUeberStrecke(strecke), ton, "km/h",
            farbeJeWert = if (wandern) null else { kmh -> tempofarbe((((kmh / 3.6) - langsam) / (schnell - langsam)).toFloat()) },
            flaeche = wandern,
        )
    }

    // Die Karte: das Tempo als Farbe - beim Wandern die Steigung.
    val kartenform = Modifier.fillMaxWidth().padding(top = 6.dp).height(300.dp).clip(RoundedCornerShape(10.dp))
    if (wandern && mitHoehe) {
        Ueberschrift(stringResource(R.string.ks_v_steigung_karte))
        Karte {
            Hinweis(stringResource(R.string.ks_v_steigung_legende))
            Farbkarte(strecke, remember(strecke) { Trainingsanalyse.steigung(strecke) }, -15.0, 15.0, kartenform)
            Farblegende(stringResource(R.string.ks_v_bergab, "−15"), stringResource(R.string.ks_v_bergauf, "+15"))
        }
    } else {
        Ueberschrift(stringResource(R.string.ks_v_tempo_karte))
        Karte {
            Hinweis(stringResource(R.string.ks_v_tempo_legende))
            Farbkarte(strecke, strecke.map { it.tempo }, langsam, schnell, kartenform)
            Farblegende(tempoText(langsam, rad), tempoText(schnell, rad))
        }
    }

    if (!wandern && mitHoehe) Hoehenkarte(d, hoehe, strecke)

    val km = remember(d, strecke) { Trainingsanalyse.kilometer(strecke, d.puls) }
    if (km.size >= 2) Kilometerkarte(km, rad, ton)
}

@Composable
private fun Hoehenkarte(d: TrainingDetail, hoehe: List<Verlaufspunkt>, strecke: List<Trainingsanalyse.Streckenpunkt>) {
    val m = stringResource(R.string.ks_v_m_ue_m)
    Ueberschrift(stringResource(R.string.ks_v_hoehenprofil))
    Karte {
        Werte {
            Wert(String.format(Locale.getDefault(), "%.0f m", Spur.hoehenmeter(d.punkte)), stringResource(R.string.ks_t_aufstieg))
            Wert(String.format(Locale.getDefault(), "%.0f %s", hoehe.minOf { it.wert }, m), stringResource(R.string.ks_v_tiefster))
            Wert(String.format(Locale.getDefault(), "%.0f %s", hoehe.maxOf { it.wert }, m), stringResource(R.string.ks_v_hoechster))
        }
        val steil = remember(strecke) { Trainingsanalyse.steigung(strecke).maxOrNull() ?: 0.0 }
        if (steil >= 5) Hinweis(stringResource(R.string.ks_v_steilste, steil.roundToInt().toString()))
        Verlauf(hoehe, Color(Sport.Wandern.farbe), "m", hoehe = 130.dp)
    }
}

@Composable
private fun Kilometerkarte(km: List<Trainingsanalyse.Kilometer>, rad: Boolean, ton: Color) {
    val schnellste = km.filter { it.meter >= 900 }.minByOrNull { it.sekunden / it.meter }
    Ueberschrift(stringResource(R.string.ks_v_km_fuer_km))
    Karte {
        Tabellenzeile(
            listOf("km", stringResource(R.string.ks_t_zeit), if (rad) "km/h" else "/km",
                stringResource(R.string.ks_v_puls_kurz), stringResource(R.string.ks_v_auf)),
            KM_SPALTEN, kopf = true,
        )
        km.forEach { k ->
            HorizontalDivider()
            val ms = k.meter / k.sekunden.coerceAtLeast(1)
            Tabellenzeile(
                listOf(
                    k.nummer.toString() + if (k.meter < 900) "*" else "",
                    zeitText(k.sekunden),
                    tempoText(ms, rad).substringBefore(" "),
                    k.pulsMittel?.roundToInt()?.toString() ?: "–",
                    if (k.aufstieg >= 1) "+" + k.aufstieg.roundToInt() else "–",
                ),
                KM_SPALTEN, hervor = if (k == schnellste) 2 to ton else null,
            )
        }
        if (km.any { it.meter < 900 }) Hinweis(stringResource(R.string.ks_v_angefangen))
    }
}

/** Minuten und Sekunden - fuer Kilometer und Saetze, die selten eine Stunde dauern. */
private fun zeitText(sekunden: Long): String =
    if (sekunden >= 3600) dauerText(sekunden / 60)
    else String.format(Locale.getDefault(), "%d:%02d", sekunden / 60, sekunden % 60)

/** Breite je Spalte; null heisst: nimmt den Rest. */
private val KM_SPALTEN = listOf<Dp?>(40.dp, null, 64.dp, 48.dp, 48.dp)
private val SATZ_SPALTEN = listOf<Dp?>(40.dp, 48.dp, null, 56.dp, 56.dp)

/**
 * Eine Zeile einer kleinen Tabelle. Die letzte Spalte steht rechts, [hervor]
 * hebt eine Spalte fett und farbig hervor (der schnellste Kilometer, der
 * Satz mit den meisten Wiederholungen).
 */
@Composable
private fun Tabellenzeile(zellen: List<String>, spalten: List<Dp?>, kopf: Boolean = false, hervor: Pair<Int, Color>? = null) {
    val stil = if (kopf) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium
    val farbe = if (kopf) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().padding(vertical = if (kopf) 2.dp else 5.dp)) {
        zellen.forEachIndexed { i, z ->
            val breite = spalten[i]
            val mod = if (breite == null) Modifier.weight(1f) else Modifier.width(breite)
            val h = hervor?.takeIf { it.first == i }
            Text(
                z, mod, style = stil,
                color = h?.second ?: farbe,
                fontWeight = if (h != null) FontWeight.Bold else null,
                textAlign = if (i == zellen.lastIndex) TextAlign.End else TextAlign.Start,
            )
        }
    }
}

// --- Kraft ---------------------------------------------------------------------

/**
 * Beim Kraft zaehlt der Satz - und die Pause danach: wie schnell der Puls
 * wieder faellt, sagt mehr ueber die Form als jede Zahl im Satz.
 */
@Composable
private fun Kraft(d: TrainingDetail, ton: Color, maxpuls: Int) {
    val s = d.eintrag.sitzung
    fun sek(i: java.time.Instant) = Duration.between(s.startTime, i).seconds
    val saetze = s.segments.filter { it.segmentType != ExerciseSegment.EXERCISE_SEGMENT_TYPE_REST }
        .map { Triple(sek(it.startTime), sek(it.endTime), it.repetitions) }
    val pausen = s.segments.filter { it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_REST }
        .map { sek(it.startTime) to sek(it.endTime) }

    PulsUeberZeit(d, ton, maxpuls, stringResource(R.string.ks_v_baender_saetze),
        saetze.map { it.first.toDouble() to it.second.toDouble() })

    if (saetze.isEmpty()) {
        if (d.puls.size < 2) Karte { Hinweis(stringResource(R.string.ks_v_keine_saetze)) }
        return
    }
    Ueberschrift(stringResource(R.string.ks_v_saetze_titel))
    Karte {
        val pausenSek = pausen.map { (a, b) -> b - a }
        Werte {
            Wert(saetze.size.toString(), stringResource(R.string.ks_v_saetze))
            Wert(saetze.sumOf { it.third }.toString(), stringResource(R.string.ks_v_wdh) + " " + stringResource(R.string.ks_v_gesamt))
            if (pausenSek.isNotEmpty()) Wert("${pausenSek.average().roundToInt()} s", stringResource(R.string.ks_v_pause_schnitt))
        }
        Trainingsanalyse.erholung(d.puls, pausen)?.let { e ->
            Text(
                stringResource(
                    when {
                        e >= 20 -> R.string.ks_v_erholung_schnell
                        e >= 10 -> R.string.ks_v_erholung_solide
                        else -> R.string.ks_v_erholung_oben
                    },
                    e.roundToInt().toString(),
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
        Tabellenzeile(
            listOf(stringResource(R.string.ks_v_satz), stringResource(R.string.ks_v_wdh), stringResource(R.string.ks_t_dauer),
                stringResource(R.string.ks_t_puls_schnitt), stringResource(R.string.ks_v_pause)),
            SATZ_SPALTEN, kopf = true,
        )
        val meiste = saetze.maxOf { it.third }
        saetze.forEachIndexed { i, (von, bis, wdh) ->
            HorizontalDivider()
            // Die Pause nach dem Satz: die erste, die an seinem Ende beginnt.
            val pause = pausen.firstOrNull { it.first >= bis - 2 }?.let { it.second - it.first }
            Tabellenzeile(
                listOf(
                    (i + 1).toString(),
                    wdh.toString(),
                    "${bis - von} s",
                    Trainingsanalyse.pulsMittel(d.puls, von, bis)?.roundToInt()?.toString() ?: "–",
                    pause?.let { "$it s" } ?: "–",
                ),
                SATZ_SPALTEN, hervor = if (wdh == meiste && meiste > 0) 1 to ton else null,
            )
        }
    }
}

// --- Yoga ----------------------------------------------------------------------

/**
 * Beim Yoga ist die Frage, ob man ruhiger wurde: der Puls am Anfang gegen
 * den am Ende, der tiefste Wert, und die HRV, die die Uhr dabei mass.
 */
@Composable
private fun Yoga(d: TrainingDetail, ton: Color, maxpuls: Int) {
    PulsUeberZeit(d, ton, maxpuls, stringResource(R.string.ks_v_ruhig))
    Ueberschrift(stringResource(R.string.ks_v_ruhe))
    Karte {
        if (d.puls.size >= 2) {
            val ende = d.puls.last().sekunde
            val anfang = Trainingsanalyse.pulsMittel(d.puls, 0, 120)
            val schluss = Trainingsanalyse.pulsMittel(d.puls, ende - 120, ende)
            Werte {
                anfang?.let { Wert("${it.roundToInt()} bpm", stringResource(R.string.ks_v_anfang)) }
                schluss?.let { Wert("${it.roundToInt()} bpm", stringResource(R.string.ks_v_ende)) }
                Wert("${d.puls.minOf { it.bpm }} bpm", stringResource(R.string.ks_v_tiefster))
            }
            if (anfang != null && schluss != null) {
                val dif = anfang - schluss
                val n = dif.roundToInt().toString()
                Text(
                    when {
                        dif >= 8 -> stringResource(R.string.ks_v_gefallen, n)
                        dif >= 3 -> stringResource(R.string.ks_v_leicht_gefallen, n)
                        dif > -3 -> stringResource(R.string.ks_v_geblieben)
                        else -> stringResource(R.string.ks_v_gestiegen)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        when {
            d.hrv != null -> {
                Werte { Wert("${d.hrv.roundToInt()} ms", stringResource(R.string.ks_v_hrv) + " (RMSSD)") }
                Hinweis(stringResource(R.string.ks_v_hrv_text))
            }
            d.puls.size < 2 -> Hinweis(stringResource(R.string.ks_v_kein_puls_hrv))
            else -> Hinweis(stringResource(R.string.ks_v_keine_hrv))
        }
    }
}

// --- Schwimmen -----------------------------------------------------------------

/**
 * Im Becken zaehlt die Bahn: wie gleich sie blieben, und wo die Kraft
 * nachliess. Der Puls laeuft darunter mit, jede zweite Bahn als Band.
 */
@Composable
private fun Schwimmen(d: TrainingDetail, ton: Color, maxpuls: Int) {
    val s = d.eintrag.sitzung
    val bahnen = s.laps.map {
        Triple(
            Duration.between(s.startTime, it.startTime).seconds,
            Duration.between(s.startTime, it.endTime).seconds,
            it.length?.inMeters,
        )
    }
    PulsUeberZeit(d, ton, maxpuls, stringResource(R.string.ks_v_jede_zweite_bahn),
        bahnen.filterIndexed { i, _ -> i % 2 == 0 }.map { it.first.toDouble() to it.second.toDouble() })

    if (bahnen.isEmpty()) {
        if (d.puls.size < 2) Karte { Hinweis(stringResource(R.string.ks_v_keine_bahnen)) }
        return
    }
    val zeiten = bahnen.map { (it.second - it.first).toDouble() }
    val laenge = bahnen.firstNotNullOfOrNull { it.third } ?: 0.0
    val meter = bahnen.sumOf { it.third ?: laenge }
    val schwankung = Trainingsanalyse.schwankung(zeiten)
    val je100 = if (meter > 0) zeiten.sum() / meter * 100 else 0.0

    Ueberschrift(stringResource(R.string.ks_v_bahnen_titel))
    Karte {
        Werte {
            Wert(
                bahnen.size.toString() + if (laenge > 0) " " + stringResource(R.string.ks_v_a_m, laenge.roundToInt().toString()) else "",
                stringResource(R.string.ks_v_bahnen),
            )
            if (meter > 0) Wert("${meter.roundToInt()} m", stringResource(R.string.ks_t_strecke))
            if (je100 > 0) Wert(zeitText(je100.roundToInt().toLong()) + " min", stringResource(R.string.ks_v_je_100))
        }
        Text(
            stringResource(
                when {
                    schwankung <= 3 -> R.string.ks_v_sehr_gleich
                    schwankung <= 8 -> R.string.ks_v_gleich
                    else -> R.string.ks_v_ungleich
                },
                schwankung.roundToInt().toString(),
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        if (zeiten.size >= 2) {
            // Jede Bahn ein Punkt, gefaerbt von der schnellsten (rot) zur langsamsten (blau).
            val schnellste = zeiten.min()
            val langsamste = zeiten.max()
            Verlauf(
                zeiten.mapIndexed { i, z -> Verlaufspunkt(i + 1.0, z) },
                ton, stringResource(R.string.ks_v_s_je_bahn),
                farbeJeWert = { z -> tempofarbe(((langsamste - z) / (langsamste - schnellste).coerceAtLeast(1.0)).toFloat()) },
                flaeche = false, hoehe = 140.dp, zaehlachse = true,
            )
            Hinweis(stringResource(R.string.ks_v_bahn_legende))
        }
    }
}

// --- Blutsauerstoff --------------------------------------------------------------

/**
 * Der Blutsauerstoff waehrend des Trainings.
 *
 * BEIM YOGA IMMER, sonst nur mit Werten. Yoga ist still genug, dass die Uhr
 * gueltig misst - fehlen dort die Werte, ist das eine Auskunft. Beim Laufen
 * verwirft die Uhr fast jede Messung; eine leere Karte dort waere nur Laerm.
 */
@Composable
private fun Sauerstoff(d: TrainingDetail, ton: Color) {
    if (d.spo2.isEmpty() && d.eintrag.sport != Sport.Yoga) return
    Ueberschrift(stringResource(R.string.ks_v_spo2_titel))
    Karte {
        if (d.spo2.isEmpty()) {
            Hinweis(stringResource(R.string.ks_v_spo2_keine))
            return@Karte
        }
        val werte = d.spo2.map { it.second }
        Werte {
            Wert("${werte.average().roundToInt()} %", stringResource(R.string.ks_v_spo2_schnitt))
            Wert("${werte.min().roundToInt()} %", stringResource(R.string.ks_v_spo2_tiefster))
            Wert("${werte.max().roundToInt()} %", stringResource(R.string.ks_v_spo2_hoechster))
        }
        if (d.spo2.size >= 2) {
            Hinweis(stringResource(R.string.ks_v_spo2_zeit))
            Verlauf(
                d.spo2.map { Verlaufspunkt(it.first.toDouble(), it.second) },
                ton, "%", flaeche = false, hoehe = 120.dp, zeitachse = true,
            )
        }
        Hinweis(pluralStringResource(R.plurals.ks_v_spo2_n, d.spo2.size, d.spo2.size))
        Hinweis(stringResource(R.string.ks_v_spo2_lang))
    }
}
