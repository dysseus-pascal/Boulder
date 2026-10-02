package coredevices.pebble.kiesel.ansicht

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Pool
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import coredevices.pebble.kiesel.Spur
import coredevices.pebble.R
import coredevices.pebble.health.frageAlleAkteRechte
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.Reihentag
import coredevices.pebble.kiesel.Sport
import coredevices.pebble.kiesel.Sportrechnung
import coredevices.pebble.kiesel.SpurDienst
import coredevices.pebble.kiesel.TrainingEintrag
import coredevices.pebble.kiesel.Trainingsanalyse
import coredevices.pebble.kiesel.TrainingsDaten
import coredevices.pebble.kiesel.TrainingsUebersicht
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
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
        Modifier.size(groesse.dp).background(Color(sport.farbe).copy(alpha = 0.28f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(sport.zeichen(), contentDescription = null, tint = sport.ton(), modifier = Modifier.size((groesse * 0.6f).dp))
    }
}

/** Der Tonkreis der Health-Seite in der Farbe der Sportart. */
@Composable
internal fun SportKreis(sport: Sport, groesse: Dp = 36.dp, bild: Dp = 20.dp) =
    Tonkreis(sport.zeichen(), Color(sport.farbe), sport.ton(), 0.22f, groesse, bild)

/** Ein fester Zeitpunkt mit Wochentag und Datum: "Wed, Sep 30, 9:14 PM" - jetzt aus Formate. */
@Deprecated(
    "Formate.wochentagZeit(context, t, mitDatum = true); Listen ohne Tageskopf: Formate.zeitpunkt(context, t, mitWochentag = true)",
    ReplaceWith("Formate.wochentagZeit(context, zeit, mitDatum = true)", "coredevices.pebble.kiesel.Formate"),
)
internal fun wannText(context: Context, zeit: Instant): String = Formate.wochentagZeit(context, zeit, mitDatum = true)

/**
 * Wann ein Training begann: "Yesterday, 6:02 PM", "Mon, 7:53 AM", "Sun, Sep 27,
 * 8:16 AM". Ohne [relativ] nie "Today"/"Yesterday" - in der Liste steht die
 * Woche schon im Kopf. DORT OHNE DATUM NUR DIESE WOCHE: sonst stuende "Sun"
 * unter zwei Wochenkoepfen. Die Regel bleibt hier, die Formen kommen aus Formate.
 */
internal fun trainingszeit(context: Context, t: Instant, relativ: Boolean): String {
    val tag = t.atZone(ZoneId.systemDefault()).toLocalDate()
    val heute = LocalDate.now()
    val tage = Duration.between(tag.atStartOfDay(), heute.atStartOfDay()).toDays()
    return when {
        relativ && tage in 0L..1L -> Formate.zeitpunkt(context, t)
        tage in 0..6 && (relativ || !tag.isBefore(heute.with(DayOfWeek.MONDAY))) -> Formate.wochentagZeit(context, t, mitDatum = false)
        else -> Formate.wochentagZeit(context, t, mitDatum = true)
    }
}

/** Der Name eines Trainings - derselbe in Liste, Detail, Zeitstrahl und Widget (TrainingsDaten.name). */
@Composable
internal fun trainingsName(t: TrainingEintrag): String = TrainingsDaten.name(LocalContext.current, t.sitzung)

/** Eine Trainingszeile im Stil der Health-Seite: Sportkreis, Name, Zeit, die Dauer fett rechts. */
@Composable
internal fun TrainingZeile(t: TrainingEintrag, unter: String, onClick: () -> Unit) {
    val (zahl, einheit) = dauerTeile(t.minuten)
    Zeile(trainingsName(t), unter, zahl, einheit, zeichen = { SportKreis(t.sport) }, onClick = onClick)
}

// --- Die Sportseite --------------------------------------------------------------

/** Wohin die Sportseite fuehrt - die Navigation selbst haengt in TrainingScreens. */
internal class Sportwege(
    val detail: (String) -> Unit,
    val alle: (LocalDate?) -> Unit,
    val trends: () -> Unit,
    val spuren: () -> Unit,
)

/**
 * Der Sport-Reiter nach Konzept B "Das letzte Training zuerst": oben das
 * letzte Training gross mit seiner Strecke, darunter die Woche in drei Zahlen
 * und einer Reihe Tage, dann die drei davor. DIE LANGE LISTE UND DIE BILDER
 * LIEGEN EINE EBENE TIEFER: wer den Reiter oeffnet, will sehen, was zuletzt
 * war - nicht ein Vierteljahr durchblaettern.
 */
@Composable
internal fun SportAnsicht(wege: Sportwege) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var daten by remember { mutableStateOf<TrainingsUebersicht?>(null) }
    var fehlendeRechte by remember { mutableStateOf<Set<String>>(emptySet()) }
    var puls by remember { mutableStateOf<Int?>(null) }
    var punkte by remember { mutableStateOf<List<Spur.Punkt>>(emptyList()) }
    var neu by remember { mutableIntStateOf(0) }
    var ortsstand by remember { mutableIntStateOf(0) }
    // Zurueck aus den Systemeinstellungen: dort kann sich die Erlaubnis geaendert haben.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ortsstand++; neu++ }
    LaunchedEffect(neu) {
        val d = TrainingsDaten.uebersicht(context)
        fehlendeRechte = TrainingsDaten.fehlendeRechte(context)
        daten = d
        // Erst die Seite, dann der Puls: die Abfrage haelt sonst jede Rueckkehr auf.
        puls = d.trainings.firstOrNull()?.let { TrainingsDaten.mittelpuls(context, it.sitzung) }
        // Die Spur des letzten Trainings fuer die kleine Karte - eine Datei, kein
        // Akte-Zugriff, beschnitten wie die Strecke daneben.
        punkte = d.trainings.firstOrNull()?.let { t ->
            withContext(Dispatchers.IO) { runCatching { TrainingsDaten.punkte(context, t.sitzung) }.getOrDefault(emptyList()) }
        }.orEmpty()
    }
    val ortStarter = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ortsstand++ }

    val d = daten
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val heute = LocalDate.now()
    val einheiten = remember(d) { d.trainings.map { it.einheit } }
    val (erlaubt, immer) = remember(ortsstand) { SpurDienst.darfOrten(context) to SpurDienst.darfImmerOrten(context) }

    // Die Zeilen, die nur erscheinen, wenn etwas fehlt oder wartet.
    val noetig = buildList<@Composable () -> Unit> {
        if (!d.akteDa) add { AkteZeile(stringResource(R.string.ks_sp_hc_fehlt), null) }
        else if (fehlendeRechte.isNotEmpty()) {
            val trainingsFehlen = HealthPermission.getReadPermission(ExerciseSessionRecord::class) in fehlendeRechte
            add {
                AkteZeile(stringResource(if (trainingsFehlen) R.string.ks_sp_hc_trainings else R.string.ks_sp_hc_teils)) {
                    scope.launch {
                        frageAlleAkteRechte(context)
                        neu++
                    }
                }
            }
        }
        if (!erlaubt || !immer) add {
            RoutenZeile(erlaubt) {
                // "Immer" gibt es nur in den Systemeinstellungen, nicht im Dialog.
                if (!erlaubt) ortStarter.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                else context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        if (d.offen.isNotEmpty()) add {
            val warn = toene().warnung
            Zeile(
                pluralStringResource(R.plurals.ks_sp_spuren, d.offen.size, d.offen.size),
                zeichen = { Tonkreis(Zeichen.ort, warn, warn, 0.18f) }, onClick = wege.spuren,
            )
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Spacer(Modifier.height(2.dp)) }
        if (d.trainings.isEmpty()) {
            item { Wochenkarte(d.trainings, heute, wege) }
            if (noetig.isNotEmpty()) item { Zeilenkarte(stringResource(R.string.ks_sp_einrichten), noetig) }
        } else {
            val t = d.trainings.first()
            item { Kasten { Grosses(t, puls, punkte) { wege.detail(t.id) } } }
            item { Wochenkarte(d.trainings, heute, wege) }
            if (d.trainings.size > 1) item { DavorKarte(d.trainings.drop(1).take(3), wege) }
            if (noetig.isNotEmpty()) item { Zeilenkarte(stringResource(R.string.ks_sp_einrichten), noetig) }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun Wochenkarte(trainings: List<TrainingEintrag>, heute: LocalDate, wege: Sportwege) {
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    val einheiten = trainings.map { it.einheit }
    Kasten {
        if (trainings.isEmpty()) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.ks_sp_leer), fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.ks_sp_leer_unter), fontSize = 14.sp, lineHeight = 20.sp, color = grau)
            }
            return@Kasten
        }
        val lage = Sportrechnung.lage(einheiten, trainings.first().einheit, heute)
        val montag = heute.with(java.time.DayOfWeek.MONDAY)
        val meter = trainings.filter { !it.einheit.tag.isBefore(montag) && !it.einheit.tag.isAfter(heute) }.sumOf { it.meter }
        Kopf(
            stringResource(R.string.ks_sp_diese_woche),
            "· " + if (lage.vorwoche > 0) stringResource(R.string.ks_sp_vorwoche, dauerText(lage.vorwoche)) else stringResource(R.string.ks_sp_vorwoche_keins),
            stringResource(R.string.ks_h_trends), wege.trends,
        )
        // Drei Zahlen statt eines Satzes: wie oft, wie lange, wie weit.
        Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            val (zeit, einheit) = dauerTeile(lage.minuten)
            Kennzahl("${lage.anzahl}", null, stringResource(R.string.ks_sp_trainings))
            Kennzahl(zeit, einheit, stringResource(R.string.ks_sp_zeit))
            if (meter > 0) Kennzahl(String.format(Locale.getDefault(), "%.1f", meter / 1000), "km", stringResource(R.string.ks_t_strecke))
        }
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Sportrechnung.reihe(einheiten, heute).forEach { r ->
                Tagesfeld(r, heute, Modifier.weight(1f), if (r.haupt != null) ({ wege.alle(r.tag) }) else null)
            }
        }
    }
}

/** Ein Tag der Reihe: Buchstabe, der Kreis der laengsten Art, Punkte fuer die anderen, die Minuten. */
@Composable
private fun Tagesfeld(r: Reihentag, heute: LocalDate, modifier: Modifier, onClick: (() -> Unit)?) {
    val farben = MaterialTheme.colorScheme
    val grau = farben.onSurfaceVariant
    val istHeute = r.tag == heute
    val form = RoundedCornerShape(10.dp)
    val arten = listOfNotNull(r.haupt) + r.weitere
    val beschreibung = (listOf(r.tag.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())) +
        arten.map { stringResource(it.nameId) } + listOfNotNull(if (r.minuten > 0) dauerText(r.minuten) else null)).joinToString(", ")
    Column(
        modifier.clip(form)
            .then(if (istHeute) Modifier.border(1.dp, farben.primary.copy(alpha = 0.55f), form) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = beschreibung }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            r.tag.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
            fontSize = 12.sp, lineHeight = 16.sp,
            fontWeight = if (istHeute) FontWeight.SemiBold else FontWeight.Medium, color = if (istHeute) farben.primary else grau,
        )
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            if (r.haupt != null) SportKreis(r.haupt)
            else Box(Modifier.size(6.dp).background(grau.copy(alpha = if (r.tag.isAfter(heute)) 0.16f else 0.40f), CircleShape))
        }
        Row(Modifier.height(6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            r.weitere.forEach { Box(Modifier.size(6.dp).background(Color(it.farbe), CircleShape)) }
        }
        // Mit Einheit: "17" allein sagte erst die Summe darueber, dass es Minuten sind.
        Text(
            if (r.minuten > 0) dauerText(r.minuten) else "", fontSize = 12.sp, lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
        )
    }
}

/** Die drei Trainings vor dem letzten als Zeilen - der Rest liegt in "All workouts". */
@Composable
private fun DavorKarte(trainings: List<TrainingEintrag>, wege: Sportwege) {
    val context = LocalContext.current
    Kasten {
        Kopf(stringResource(R.string.ks_sp_davor), link = stringResource(R.string.ks_sp_alle), onLink = { wege.alle(null) })
        trainings.forEachIndexed { i, e ->
            if (i > 0) Trenner()
            TrainingZeile(e, trainingszeit(context, e.sitzung.startTime, true)) { wege.detail(e.id) }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun Grosses(t: TrainingEintrag, puls: Int?, punkte: List<Spur.Punkt>, onClick: () -> Unit) {
    val context = LocalContext.current
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    // Dieselbe Bezugszeit wie die Dauer: die aktive, ohne die Pausen der Uhr.
    val sekunden = t.sekunden
    val (dZahl, dEinheit) = dauerTeile(t.minuten)
    val dauerName = stringResource(R.string.ks_t_dauer)
    val streckeName = stringResource(R.string.ks_t_strecke)
    val tempoName = stringResource(if (t.sport.rad) R.string.ks_t_tempo_rad else R.string.ks_t_tempo)
    val pulsName = stringResource(R.string.ks_t_puls_schnitt)
    val aufName = stringResource(R.string.ks_t_aufstieg)
    // Fehlt Strecke oder Puls, fehlt die Kennzahl - nie ein Strich an ihrer Stelle.
    val werte = buildList {
        add(Triple(dZahl, dEinheit, dauerName))
        if (t.meter > 0) add(Triple(String.format(Locale.getDefault(), "%.1f", t.meter / 1000), "km", streckeName))
        // Im Becken sagt das Tempo je Kilometer nichts - dort zaehlt die Bahn (Detail).
        if (t.sport != Sport.Schwimmen) {
            if (t.sport.rad) Sportrechnung.kmh(t.meter, sekunden)?.let {
                add(Triple(String.format(Locale.getDefault(), "%.1f", it), "km/h", tempoName))
            } else Sportrechnung.tempo(t.meter, sekunden)?.let {
                add(Triple(String.format(Locale.getDefault(), "%d:%02d", it / 60, it % 60), "/km", tempoName))
            }
        }
        puls?.let { add(Triple("$it", "bpm", pulsName)) }
        // Der Aufstieg statt des Tempos, wenn es bergauf ging: er sagt mehr ueber die Tour.
        val auf = Trainingsanalyse.aufstieg(punkte)
        if (auf >= 10) {
            val i = indexOfFirst { it.third == tempoName }
            val wert = Triple(String.format(Locale.getDefault(), "%.0f", auf), "m", aufName)
            if (i >= 0) set(i, wert) else add(wert)
        }
    }.take(4)
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 14.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SportKreis(t.sport, 48.dp, 26.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(trainingsName(t), fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(trainingszeit(context, t.sitzung.startTime, true), fontSize = 13.sp, lineHeight = 18.sp, color = grau)
            }
            Icon(Zeichen.weiter, null, Modifier.size(16.dp), tint = grau)
        }
        // DIE FLAECHENFARBE, NICHT DER TON: die Kacheln sind in beiden Schemata
        // hell, der abgedunkelte Ton sah im hellen aus wie eine Bahnlinie.
        if (punkte.size >= 2) Kartenvorschau(
            punkte.map { it.lat to it.lon }, Color(t.sport.farbe),
            Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(10.dp)), onClick,
        )
        Row(Modifier.fillMaxWidth().padding(start = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            werte.forEach { (zahl, einheit, name) ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(zahlMitEinheit(zahl, einheit), lineHeight = 26.sp, maxLines = 1, softWrap = false)
                    Text(name, fontSize = 12.sp, lineHeight = 16.sp, color = grau, maxLines = 1)
                }
            }
            // Leere Faecher halten das Raster: zwei Werte stehen links, nicht gestreckt.
            repeat(4 - werte.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** Eine Karte aus Zeilen mit Kopf - fuer "Look closer" und "Set up". */
@Composable
private fun Zeilenkarte(titel: String, zeilen: List<@Composable () -> Unit>) {
    Kasten {
        Kopf(titel)
        zeilen.forEachIndexed { i, z ->
            if (i > 0) Trenner()
            z()
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun AkteZeile(unter: String, erlaube: (() -> Unit)?) {
    val t = toene()
    Knopfzeile(
        stringResource(R.string.ks_sp_hc), unter, { Tonkreis(Zeichen.herz, FLAECHE_HERZ, t.herz) },
        stringResource(R.string.ks_n_erlauben), erlaube, unterFarbe = Farben.warnung.ton(),
    )
}

@Composable
private fun RoutenZeile(erlaubt: Boolean, erlaube: () -> Unit) {
    val warn = toene().warnung
    Knopfzeile(
        stringResource(R.string.ks_sp_routen),
        stringResource(if (erlaubt) R.string.ks_sp_nur_nutzung else R.string.ks_ei_standort_nicht),
        { Tonkreis(Zeichen.ort, warn, warn, 0.18f) },
        stringResource(R.string.ks_n_erlauben), erlaube, unterFarbe = warn,
    )
}

/** Acht Wochen als Streifen neben "Trends": die laufende Woche kraeftig, leere als flacher Strich. */
@Composable
private fun Minibalken(minuten: List<Long>) {
    val gruen = MaterialTheme.colorScheme.primary
    val leer = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    Canvas(Modifier.size(72.dp, 26.dp)) {
        // Eine Stunde als Mindesthoehe, wie im Wochenstapel: eine kurze Woche soll nicht nach viel aussehen.
        val spitze = maxOf(minuten.maxOrNull() ?: 0L, 60L).toFloat()
        val breite = 6.dp.toPx()
        val fach = size.width / minuten.size
        minuten.forEachIndexed { i, m ->
            val h = maxOf(2.dp.toPx(), size.height * m / spitze)
            val farbe = when {
                i == minuten.lastIndex -> gruen
                m > 0 -> gruen.copy(alpha = 0.45f)
                else -> leer
            }
            drawRoundRect(farbe, Offset(fach * i + (fach - breite) / 2, size.height - h), Size(breite, h), CornerRadius(1.5.dp.toPx()))
        }
    }
}
