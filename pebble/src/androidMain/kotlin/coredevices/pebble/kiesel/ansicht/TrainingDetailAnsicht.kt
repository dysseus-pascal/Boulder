package coredevices.pebble.kiesel.ansicht

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import androidx.health.connect.client.records.ExerciseSegment
import coredevices.pebble.R
import org.koin.compose.koinInject
import io.rebble.libpebblecommon.connection.HealthDataApi
import coredevices.pebble.kiesel.Akte
import coredevices.pebble.kiesel.Kieselsport
import coredevices.pebble.kiesel.Sport
import coredevices.pebble.kiesel.Sportrechnung
import coredevices.pebble.kiesel.Spur
import coredevices.pebble.kiesel.TrainingDetail
import coredevices.pebble.kiesel.Trainingsanalyse
import coredevices.pebble.kiesel.Trainingsanalyse.Verlaufspunkt
import coredevices.pebble.kiesel.TrainingsDaten
import coredevices.pebble.kiesel.Trainingswerte
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
    val api = koinInject<HealthDataApi>()
    var detail by remember { mutableStateOf<TrainingDetail?>(null) }
    var geladen by remember { mutableStateOf(false) }
    var grenzen by remember { mutableStateOf<Pair<Int?, Int>?>(null) }
    var koerper by remember { mutableStateOf<Trainingswerte.Koerper?>(null) }
    LaunchedEffect(id) {
        val neu = TrainingsDaten.detail(context, id)
        // Ruhe- und Maximalpuls und das Gewicht wie in der Akte (Trainingswerte)
        // - vor der Anzeige, sonst springen kcal und Zonen nach dem Oeffnen.
        neu?.eintrag?.sitzung?.startTime?.let { start ->
            grenzen = Trainingswerte.herzgrenzenVor(context, api, start)
            koerper = Trainingswerte.koerperVor(context, Akte(context).bereit(), start)
        }
        detail = neu
        geladen = true
        // HIER IM VORDERGRUND LIEST DIE AKTE DIE WAAGE: standen die kcal mit dem
        // eingestellten Gewicht drin (Rechnung im Hintergrund), ersetzt diese
        // Rechnung sie einmal - Akte und Detail bleiben dieselbe Zahl.
        val s = neu?.eintrag?.sitzung ?: return@LaunchedEffect
        if (s.metadata.clientRecordId == Kieselsport.kennung(s.startTime.epochSecond)) {
            Trainingswerte.schreibeKalorienAusPuls(context, s.startTime.epochSecond)?.let { kcal ->
                detail = detail?.copy(kcal = kcal)
            }
        }
    }
    // Die Route einer fremden App gibt die Akte nur nach einer Zustimmung je Training heraus.
    val routeStarter = rememberLauncherForActivityResult(ExerciseRouteRequestContract()) { route ->
        val d = detail
        if (route != null && d != null) {
            val s = d.eintrag.sitzung
            val punkte = Spur.inSitzung(TrainingsDaten.ausRoute(route), s.startTime, s.endTime)
            if (punkte.size >= 2) {
                val meter = Spur.laenge(punkte)
                detail = d.copy(punkte = punkte, eintrag = d.eintrag.copy(meter = meter))
                // Fuer Liste, Woche und Widget: die Zustimmung gilt nur dieses eine Mal.
                TrainingsDaten.merkeStrecke(context, s, meter)
            }
        }
    }
    val d = detail
    val g = grenzen
    if (d == null || g == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (!geladen) CircularProgressIndicator() else Text(stringResource(R.string.ks_t_noch_keins))
        }
        return
    }
    val t = d.eintrag
    val s = t.sitzung
    // Derselbe Name wie in der Liste - auch ein deutscher Titel aus Kiesel-Helper auf Englisch.
    val name = trainingsName(t)
    LaunchedEffect(name) { setzeTitel(name) }
    // Derselbe Maximalpuls fuer Zonen und kcal wie ueberall in Boulder (Herzgrenzen).
    val (ruhepuls, maxpuls) = g
    // Der Ton, nicht die Flaeche: er traegt Linien und Schrift auf der Karte (siehe Sport.ton).
    val ton = t.sport.ton()
    val routeHolen = if (d.punkte.size < 2 && TrainingsDaten.routeGesperrt(s)) ({ routeStarter.launch(s.metadata.id) }) else null

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
                // Dauer und Tempo aus derselben, aktiven Zeit - wie auf der Sportseite und der Uhr.
                Wert(dauerText(t.minuten), stringResource(R.string.ks_t_dauer))
                if (t.meter > 0) {
                    Wert(kmText(t.meter), stringResource(R.string.ks_t_strecke))
                    if (t.sport.rad) {
                        Sportrechnung.kmh(t.meter, t.sekunden)?.let { Wert(kmhText(it), stringResource(R.string.ks_t_tempo_rad)) }
                    } else if (t.sport != Sport.Schwimmen) {
                        Sportrechnung.tempo(t.meter, t.sekunden)?.let { Wert(tempoText(it), stringResource(R.string.ks_t_tempo)) }
                    }
                    // Die Punkte sind schon auf die Sitzung beschnitten (TrainingsDaten.punkte).
                    val auf = remember(d.punkte) { Trainingsanalyse.aufstieg(d.punkte) }
                    if (auf > 0) Wert(String.format(Locale.getDefault(), "%.0f m", auf), stringResource(R.string.ks_t_aufstieg))
                }
                if (d.puls.isNotEmpty()) {
                    Wert(d.puls.map { it.bpm }.average().toInt().toString(), stringResource(R.string.ks_t_puls_schnitt))
                    Wert(d.puls.maxOf { it.bpm }.toString(), stringResource(R.string.ks_t_puls_max))
                    // DIE AKTIVEN KCAL, DIESELBEN WIE IN DER AKTE: dort steht, was
                    // Boulder aus dem Puls schrieb; sonst dieselbe Rechnung hier.
                    (d.kcal ?: koerper?.let { k ->
                        Trainingsanalyse.aktiveKalorien(d.puls, k.kg, k.alter, k.geschlecht, ruhepuls, maxpuls)
                    })?.takeIf { it > 0 }?.let {
                        Wert("≈ " + it.roundToInt(), stringResource(R.string.ks_t_kcal))
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
            else -> Strecke(d, ton, maxpuls, routeHolen)
        }
        Sauerstoff(d, ton)

        // NUR BEI FREMDEN: die eigene Notiz traegt Saetze und Bahnen, und die
        // stehen oben schon als Zahlen. Aeltere eigene Notizen hatten dazu
        // Puls und kcal der Uhr, roh - sie widersprachen den Zahlen darueber.
        s.notes?.takeIf { it.isNotBlank() && !t.eigen }?.let { notiz ->
            Karte {
                Text(stringResource(R.string.ks_n_notiz), style = MaterialTheme.typography.labelMedium)
                Text(notiz, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Tempo als min/km aus Sekunden je Kilometer. */
private fun tempoText(sekundenJeKm: Long): String =
    String.format(Locale.getDefault(), "%d:%02d /km", sekundenJeKm / 60, sekundenJeKm % 60)

private fun kmhText(kmh: Double): String = String.format(Locale.getDefault(), "%.1f km/h", kmh)

/** Tempo als Text aus m/s - auf dem Rad km/h, sonst min/km; unter 0,2 m/s gibt es keins. */
private fun tempoText(ms: Double, rad: Boolean): String = when {
    rad -> kmhText(ms * 3.6)
    else -> Sportrechnung.tempo(ms, 1)?.let { tempoText(it) } ?: "–"
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
    Ueberschrift(stringResource(R.string.ks_v_pulszonen))
    Karte {
        Zonenbalken(zonen)
        Zonenliste(zonen, maxpuls)
    }
}

/** Der Puls ueber die Zeit, mit den Zonen dahinter und senkrechten Baendern (Saetze, Bahnen). */
@Composable
private fun PulsUeberZeit(d: TrainingDetail, ton: Color, maxpuls: Int, xBaender: List<Pair<Double, Double>> = emptyList()) {
    if (d.puls.size < 2) return
    Ueberschrift(stringResource(R.string.ks_v_puls_zeit))
    Karte {
        Verlauf(
            d.puls.map { Verlaufspunkt(it.sekunde.toDouble(), it.bpm.toDouble()) },
            ton, "bpm", baender = zonenbaender(maxpuls), zeitachse = true, xBaender = xBaender,
        )
    }
}

// --- Laufen, Bike, MTB, Wandern --------------------------------------------------

@Composable
private fun Strecke(d: TrainingDetail, ton: Color, maxpuls: Int, routeHolen: (() -> Unit)?) {
    val sport = d.eintrag.sport
    val strecke = remember(d) { Trainingsanalyse.strecke(d.punkte) }
    if (strecke.size < 2) {
        if (routeHolen != null) {
            TextButton(onClick = routeHolen) { Text(stringResource(R.string.ks_t_route_zeigen)) }
        } else if (sport in setOf(Sport.Laufen, Sport.Bike, Sport.Mtb, Sport.Wandern)) {
            Hinweis(stringResource(R.string.ks_t_keine_strecke))
        }
        PulsUeberZeit(d, ton, maxpuls)
        if (d.puls.size < 2) Hinweis(stringResource(R.string.ks_v_nichts_weiter))
        return
    }
    // Puls und Pausen zaehlen ab dem Beginn der Sitzung, die Strecke ab ihrem
    // ersten Punkt - der kommt einige Sekunden spaeter, wenn das GPS fand.
    val versatz = d.punkte.first().zeit - d.eintrag.sitzung.startTime.epochSecond
    val puls = remember(d) { d.puls.map { it.copy(sekunde = it.sekunde - versatz) } }
    val pausen = remember(d) { d.eintrag.pausen.map { (a, b) -> a - versatz to b - versatz } }
    val rad = sport.rad
    val wandern = sport == Sport.Wandern
    val hoehe = remember(strecke) { Trainingsanalyse.hoeheUeberStrecke(strecke) }
    val mitHoehe = hoehe.size >= 2 && hoehe.maxOf { it.wert } - hoehe.minOf { it.wert } >= 10
    val (langsam, schnell) = remember(strecke) { Trainingsanalyse.tempoSpanne(strecke) }

    // BEIM WANDERN ZUERST DIE HOEHE: dort ist sie die Geschichte der Tour.
    if (wandern && mitHoehe) Hoehenkarte(d, hoehe, strecke)

    val pulsStrecke = remember(d, strecke) { Trainingsanalyse.pulsUeberStrecke(puls, strecke) }
    if (pulsStrecke.size >= 2) {
        Ueberschrift(stringResource(R.string.ks_v_puls_strecke))
        Karte {
            Verlauf(pulsStrecke, ton, "bpm", baender = zonenbaender(maxpuls))
        }
    }

    // Auf dem Rad heisst es Geschwindigkeit, wie oben im Kopf.
    Ueberschrift(stringResource(if (rad) R.string.ks_v_tempo_rad else R.string.ks_v_tempo))
    Karte {
        val bewegt = Trainingsanalyse.bewegt(strecke)
        Werte {
            // KEIN ZWEITER SCHNITT: der steht oben, aus der aktiven Zeit wie
            // auf der Uhr. Hier die Spitze und die Zeit in Bewegung.
            Wert(tempoText(strecke.maxOf { it.tempo }, rad), stringResource(R.string.ks_v_spitze))
            if (wandern && mitHoehe) {
                val mh = Trainingsanalyse.aufstieg(d.punkte) / (bewegt.coerceAtLeast(60) / 3600.0)
                Wert(String.format(Locale.getDefault(), "%.0f m/h", mh), stringResource(R.string.ks_t_aufstieg))
            } else {
                Wert(dauerText(bewegt / 60), stringResource(R.string.ks_v_bewegt))
            }
        }
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
            Farbkarte(strecke, remember(strecke) { Trainingsanalyse.steigung(strecke) }, -15.0, 15.0, kartenform)
            Farblegende(stringResource(R.string.ks_v_bergab, "−15"), stringResource(R.string.ks_v_bergauf, "+15"))
        }
    } else {
        Ueberschrift(stringResource(if (rad) R.string.ks_v_tempo_karte_rad else R.string.ks_v_tempo_karte))
        Karte {
            Farbkarte(strecke, strecke.map { it.tempo }, langsam, schnell, kartenform)
            Farblegende(tempoText(langsam, rad), tempoText(schnell, rad))
        }
    }

    if (!wandern && mitHoehe) Hoehenkarte(d, hoehe, strecke)

    val km = remember(d, strecke) { Trainingsanalyse.kilometer(strecke, puls, pausen) }
    if (km.size >= 2) Kilometerkarte(km, rad, ton)
}

@Composable
private fun Hoehenkarte(d: TrainingDetail, hoehe: List<Verlaufspunkt>, strecke: List<Trainingsanalyse.Streckenpunkt>) {
    val m = stringResource(R.string.ks_v_m_ue_m)
    Ueberschrift(stringResource(R.string.ks_v_hoehenprofil))
    Karte {
        Werte {
            Wert(String.format(Locale.getDefault(), "%.0f m", Trainingsanalyse.aufstieg(d.punkte)), stringResource(R.string.ks_t_aufstieg))
            Wert(String.format(Locale.getDefault(), "%.0f %s", hoehe.minOf { it.wert }, m), stringResource(R.string.ks_v_tiefster))
            Wert(String.format(Locale.getDefault(), "%.0f %s", hoehe.maxOf { it.wert }, m), stringResource(R.string.ks_v_hoechster))
        }
        val steil = remember(strecke) { Trainingsanalyse.steigung(strecke).maxOrNull() ?: 0.0 }
        if (steil >= 5) Hinweis(stringResource(R.string.ks_v_steilste, steil.roundToInt().toString()))
        Verlauf(hoehe, Sport.Wandern.ton(), "m", hoehe = 130.dp)
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
                    // Der angefangene Kilometer mit seiner Laenge statt einer Fussnote.
                    if (k.meter < 900) String.format(Locale.getDefault(), "%.1f", k.meter / 1000) else k.nummer.toString(),
                    zeitText(k.sekunden),
                    tempoText(ms, rad).substringBefore(" "),
                    k.pulsMittel?.roundToInt()?.toString() ?: "–",
                    if (k.aufstieg >= 1) "+" + k.aufstieg.roundToInt() else "–",
                ),
                KM_SPALTEN, hervor = if (k == schnellste) 2 to ton else null,
            )
        }
    }
}

/** Minuten und Sekunden - fuer Kilometer und Saetze, die selten eine Stunde dauern. */
private fun zeitText(sekunden: Long): String =
    if (sekunden >= 3600) dauerText(sekunden / 60)
    else String.format(Locale.getDefault(), "%d:%02d", sekunden / 60, sekunden % 60)

/** Breite je Spalte; null heisst: nimmt den Rest. */
private val KM_SPALTEN = listOf<Dp?>(44.dp, null, 64.dp, 48.dp, 48.dp)
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
    // Die Pausen der Uhr (PAUSE) sind weder Satz noch Ruhe zwischen Saetzen.
    val saetze = s.segments.filter {
        it.segmentType != ExerciseSegment.EXERCISE_SEGMENT_TYPE_REST && it.segmentType != ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE
    }.sortedBy { it.startTime }.map { Triple(sek(it.startTime), sek(it.endTime), it.repetitions) }
    val pausen = s.segments.filter { it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_REST }
        .map { sek(it.startTime) to sek(it.endTime) }
    // Die Ruhe nach jedem Satz bis zum naechsten: was an Ruhe dazwischen liegt.
    val ruhe = saetze.zipWithNext { a, b ->
        pausen.filter { (von, bis) -> von >= a.second && bis <= b.first }.sumOf { (von, bis) -> bis - von }
    }

    PulsUeberZeit(d, ton, maxpuls,
        saetze.map { it.first.toDouble() to it.second.toDouble() })

    if (saetze.isEmpty()) {
        if (d.puls.size < 2) Hinweis(stringResource(R.string.ks_v_keine_saetze))
        return
    }
    Ueberschrift(stringResource(R.string.ks_v_saetze_titel))
    Karte {
        // Dieselbe Rechnung wie die Notiz in der Akte (Kieselsport.ruheSchnitt).
        val schnitt = Kieselsport.ruheSchnitt(ruhe)
        Werte {
            Wert(saetze.size.toString(), stringResource(R.string.ks_v_saetze))
            Wert(saetze.sumOf { it.third }.toString(), stringResource(R.string.ks_v_wdh) + " " + stringResource(R.string.ks_v_gesamt))
            if (schnitt > 0) Wert("$schnitt s", stringResource(R.string.ks_v_pause_schnitt))
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
            val pause = ruhe.getOrNull(i)?.takeIf { it > 0 }
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
    PulsUeberZeit(d, ton, maxpuls)
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
    PulsUeberZeit(d, ton, maxpuls,
        bahnen.filterIndexed { i, _ -> i % 2 == 0 }.map { it.first.toDouble() to it.second.toDouble() })

    if (bahnen.isEmpty()) {
        if (d.puls.size < 2) Hinweis(stringResource(R.string.ks_v_keine_bahnen))
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
            Verlauf(
                d.spo2.map { Verlaufspunkt(it.first.toDouble(), it.second) },
                ton, "%", flaeche = false, hoehe = 120.dp, zeitachse = true,
            )
        }
    }
}
