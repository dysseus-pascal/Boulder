package coredevices.pebble.kiesel.ansicht

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Hiking
import androidx.compose.material.icons.outlined.PedalBike
import androidx.compose.material.icons.outlined.Pool
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import coredevices.pebble.kiesel.versuche
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// --- Kleine Helfer -------------------------------------------------------------

/**
 * Das Zeichen einer Sportart - Materials OUTLINED-Fassung: die gefuellten
 * standen neben Boulders duennen Strichzeichen wie aus einer anderen App.
 * Bis es eigene Strichpfade gibt (siehe [Zeichen]).
 */
internal fun Sport.zeichen(): ImageVector = when (this) {
    Sport.Laufen -> Icons.AutoMirrored.Outlined.DirectionsRun
    Sport.Bike, Sport.Mtb -> Icons.AutoMirrored.Outlined.DirectionsBike
    // Ein Rad ohne Fahrer: erkennbar ein Rad, und doch nicht die Ausfahrt daneben in der Liste.
    Sport.Indoor -> Icons.Outlined.PedalBike
    Sport.Wandern -> Icons.Outlined.Hiking
    Sport.Kraft -> Icons.Outlined.FitnessCenter
    Sport.Yoga -> Icons.Outlined.SelfImprovement
    Sport.Schwimmen -> Icons.Outlined.Pool
    Sport.Atem -> Icons.Outlined.Air
    Sport.Anderes -> Icons.Outlined.Timer
}

/** Der Tonkreis in der Farbe der Sportart - in Liste, Woche, Detail und Verteilung derselbe. */
@Composable
internal fun SportKreis(sport: Sport, groesse: Dp = 36.dp, bild: Dp = 20.dp) =
    Tonkreis(sport.zeichen(), Farben.sport(sport), groesse = groesse, bild = bild)

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

/**
 * Die Strecke eines Trainings in Liste, Grossem, Detail und im Health-
 * Zeitstrahl. SCHWIMMEN BLEIBT METRISCH, auch mit "Imperial Units"
 * ([Sport.immerMetrisch], dieselbe Regel liest das Widget).
 */
internal fun streckeVon(t: TrainingEintrag, imperial: Boolean): Formate.Angabe =
    Formate.distanz(t.meter, imperial && !t.sport.immerMetrisch)

/** Eine Trainingszeile im Stil der Health-Seite: Sportkreis, Name, Zeit, die Dauer fett rechts. */
@Composable
internal fun TrainingZeile(t: TrainingEintrag, unter: String, onClick: () -> Unit) {
    val dauer = Formate.dauerTeile(t.minuten)
    Zeile(trainingsName(t), unter, dauer.zahl, dauer.einheit, zeichen = { SportKreis(t.sport) }, onClick = onClick)
}

// --- Die Sportseite --------------------------------------------------------------

/** Wohin die Sportseite fuehrt - die Navigation selbst haengt in TrainingScreens. */
internal class Sportwege(
    val detail: (String) -> Unit,
    val alle: (LocalDate?) -> Unit,
    val trends: () -> Unit,
    val spuren: () -> Unit,
)

/** Was der Sport-Reiter zeigt: die Uebersicht, fehlende Rechte, Puls und Spur des letzten Trainings. */
private class Sportseite(val daten: TrainingsUebersicht, val fehlendeRechte: Set<String>, val puls: Int?, val punkte: List<Spur.Punkt>)

/** Der zuletzt gezeigte Reiter, solange der Prozess lebt (siehe [rememberStand]). */
@Volatile
private var zuletzt: Sportseite? = null

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
    var neu by remember { mutableIntStateOf(0) }
    var ortsstand by remember { mutableIntStateOf(0) }
    // Zurueck aus den Systemeinstellungen: dort kann sich die Erlaubnis geaendert haben.
    // Die Daten laedt rememberStand bei der Rueckkehr selbst neu.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ortsstand++ }
    val seite = rememberStand(zuletzt, neu) { zeige ->
        val d = TrainingsDaten.uebersicht(context)
        val rechte = TrainingsDaten.fehlendeRechte(context)
        val erstes = d.trainings.firstOrNull()
        // Erst die Seite, dann der Puls: die Abfrage haelt sonst jede Rueckkehr auf.
        // Bis dahin Puls und Spur von vorher, wenn oben noch dasselbe Training steht.
        val vorher = zuletzt?.takeIf { it.daten.trainings.firstOrNull()?.id == erstes?.id }
        zeige(Sportseite(d, rechte, vorher?.puls, vorher?.punkte.orEmpty()))
        val puls = erstes?.let { TrainingsDaten.mittelpuls(context, it.sitzung) }
        // Die Spur des letzten Trainings fuer die kleine Karte - eine Datei, kein
        // Akte-Zugriff, beschnitten wie die Strecke daneben. Gelesen nur, wenn sie
        // neu ist: TrainingsDaten merkt sich die zuletzt gelesene samt Groesse.
        val punkte = erstes?.let { t ->
            withContext(Dispatchers.IO) { versuche("Kieselsport", "Spur fuer die Karte") { TrainingsDaten.punkte(context, t.sitzung) }.orEmpty() }
        }.orEmpty()
        Sportseite(d, rechte, puls, punkte).also { zuletzt = it }
    }
    val ortStarter = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ortsstand++ }

    if (seite == null) {
        Ladekreis()
        return
    }
    val d = seite.daten
    val fehlendeRechte = seite.fehlendeRechte
    val puls = seite.puls
    val punkte = seite.punkte
    val heute = LocalDate.now()
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
            Zeile(
                pluralStringResource(R.plurals.ks_sp_spuren, d.offen.size, d.offen.size),
                zeichen = { Tonkreis(Zeichen.ort, Farben.warnung, alpha = 0.18f) }, onClick = wege.spuren,
            )
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = SEITENRAND), verticalArrangement = Arrangement.spacedBy(KARTENABSTAND)) {
        item { Spacer(Modifier.height(2.dp)) }
        if (d.trainings.isEmpty()) {
            item { Wochenkarte(d.trainings, heute, wege) }
            if (noetig.isNotEmpty()) item { Zeilenkarte(stringResource(R.string.ks_sp_einrichten), noetig) }
        } else {
            val t = d.trainings.first()
            item { Grosses(t, puls, punkte) { wege.detail(t.id) } }
            item { Wochenkarte(d.trainings, heute, wege) }
            if (d.trainings.size > 1) item { DavorKarte(d.trainings.drop(1).take(3), wege) }
            if (noetig.isNotEmpty()) item { Zeilenkarte(stringResource(R.string.ks_sp_einrichten), noetig) }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun Wochenkarte(trainings: List<TrainingEintrag>, heute: LocalDate, wege: Sportwege) {
    val imperial = einheitenImperial()
    val einheiten = trainings.map { it.einheit }
    Kasten {
        if (trainings.isEmpty()) {
            // Ein kurzer Leersatz - woher Trainings kommen, erklaert die Seite nicht.
            Kopf(stringResource(R.string.ks_sp_diese_woche))
            Leerzeile(stringResource(R.string.ks_sp_leer))
            return@Kasten
        }
        val lage = Sportrechnung.lage(einheiten, heute)
        val montag = heute.with(DayOfWeek.MONDAY)
        val meter = trainings.filter { !it.einheit.tag.isBefore(montag) && !it.einheit.tag.isAfter(heute) }.sumOf { it.meter }
        Kopf(
            stringResource(R.string.ks_sp_diese_woche),
            if (lage.vorwoche > 0) stringResource(R.string.ks_sp_vorwoche, Formate.dauer(lage.vorwoche)) else stringResource(R.string.ks_sp_vorwoche_keins),
            stringResource(R.string.ks_h_trends), wege.trends,
        )
        // Drei Zahlen statt eines Satzes: wie oft, wie lange, wie weit.
        Box(Modifier.padding(start = INNEN, end = INNEN, top = 2.dp)) {
            Kennzahlen {
                val zeit = Formate.dauerTeile(lage.minuten)
                Kennzahl(Formate.zahl(lage.anzahl.toLong()), null, stringResource(R.string.ks_sp_trainings))
                Kennzahl(zeit.zahl, zeit.einheit, stringResource(R.string.ks_sp_zeit))
                if (meter > 0) Formate.distanz(meter, imperial).let { Kennzahl(it.zahl, it.einheit, stringResource(R.string.ks_t_strecke)) }
            }
        }
        // Die Felder ohne Rand und Luft dazwischen: so ist jedes auch auf einem
        // 360-dp-Telefon 48 dp breit, ein Ziel fuer den Finger (mit Rand waren es 44).
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp)) {
            Sportrechnung.reihe(einheiten, heute).forEach { r ->
                Tagesfeld(r, heute, Modifier.weight(1f), if (r.haupt != null) ({ wege.alle(r.tag) }) else null)
            }
        }
    }
}

/** Ein Tag der Reihe: Wochentag, der Kreis der laengsten Art, Punkte fuer die anderen, die Minuten. */
@Composable
private fun Tagesfeld(r: Reihentag, heute: LocalDate, modifier: Modifier, onClick: (() -> Unit)?) {
    val farben = MaterialTheme.colorScheme
    val grau = farben.onSurfaceVariant
    val istHeute = r.tag == heute
    val form = RoundedCornerShape(10.dp)
    val arten = listOfNotNull(r.haupt) + r.weitere
    val beschreibung = (listOf(Formate.wochentagLang(r.tag.dayOfWeek)) +
        arten.map { stringResource(it.nameId) } + listOfNotNull(if (r.minuten > 0) Formate.dauer(r.minuten) else null)).joinToString(", ")
    // Rand und leere Punkte deckend mit der Karte gemischt statt Alpha.
    val rand = farben.primary.aufKarte(0.55f)
    val leer = grau.aufKarte(if (r.tag.isAfter(heute)) 0.16f else 0.40f)
    Column(
        modifier.clip(form)
            .then(if (istHeute) Modifier.border(1.dp, rand, form) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = beschreibung }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // Zwei Buchstaben wie in jeder Siebenerreihe der App ("Mo", "Tu").
        Text(
            Formate.wochentagKurz(r.tag.dayOfWeek),
            fontSize = 12.sp, lineHeight = 16.sp,
            fontWeight = if (istHeute) FontWeight.SemiBold else FontWeight.Medium, color = if (istHeute) farben.primary else grau,
        )
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            if (r.haupt != null) SportKreis(r.haupt)
            else Box(Modifier.size(6.dp).background(leer, CircleShape))
        }
        Row(Modifier.height(6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            r.weitere.forEach { Box(Modifier.size(6.dp).background(Farben.sport(it).flaeche(), CircleShape)) }
        }
        // Die Einheit klein daneben wie bei jeder Zahl; mit grosser Schrift bricht sie unter die Zahl.
        Text(
            if (r.minuten > 0) zahlMitEinheit(Formate.dauerTeile(r.minuten), 12, 10) else AnnotatedString(""),
            lineHeight = 16.sp, maxLines = 2, textAlign = TextAlign.Center,
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

/**
 * Das letzte Training gross: derselbe Kopf wie auf seiner Detailseite, die
 * Strecke auf der Karte und bis zu vier Kennzahlen in festen Faechern. Die
 * ganze Karte fuehrt ins Detail.
 */
@Composable
private fun Grosses(t: TrainingEintrag, puls: Int?, punkte: List<Spur.Punkt>, onClick: () -> Unit) {
    val context = LocalContext.current
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    val imperial = einheitenImperial()
    // Dieselbe Bezugszeit wie die Dauer: die aktive, ohne die Pausen der Uhr.
    val sekunden = t.sekunden
    val dauerName = stringResource(R.string.ks_t_dauer)
    val streckeName = stringResource(R.string.ks_t_strecke)
    val tempoName = stringResource(if (t.sport.rad) R.string.ks_t_tempo_rad else R.string.ks_t_tempo)
    val pulsName = stringResource(R.string.ks_t_puls_schnitt)
    val aufName = stringResource(R.string.ks_t_aufstieg)
    // Fehlt Strecke oder Puls, fehlt die Kennzahl - nie ein Strich an ihrer Stelle.
    val werte = buildList<Pair<Formate.Angabe, String>> {
        add(Formate.dauerTeile(t.minuten) to dauerName)
        if (t.meter > 0) add(streckeVon(t, imperial) to streckeName)
        // Im Becken sagt das Tempo je Kilometer nichts - dort zaehlt die Bahn (Detail).
        if (t.sport != Sport.Schwimmen) {
            val tempo = if (t.sport.rad) Formate.geschwindigkeit(t.meter, sekunden, imperial) else Formate.tempo(t.meter, sekunden, imperial)
            tempo?.let { add(it to tempoName) }
        }
        puls?.let { add(Formate.menge(it.toLong(), "bpm") to pulsName) }
        // Der Aufstieg statt des Tempos, wenn es bergauf ging: er sagt mehr ueber die Tour.
        val auf = Trainingsanalyse.aufstieg(punkte)
        if (auf >= 10) {
            val i = indexOfFirst { it.second == tempoName }
            val wert = Formate.hoehe(auf, imperial) to aufName
            if (i >= 0) set(i, wert) else add(wert)
        }
    }.take(4)
    Kasten(onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Detailkopf(trainingsName(t), trainingszeit(context, t.sitzung.startTime, true)) { SportKreis(t.sport, 48.dp, 26.dp) }
            }
            Icon(Zeichen.weiter, null, Modifier.padding(end = 12.dp).size(16.dp), tint = grau)
        }
        Column(Modifier.padding(start = INNEN, end = 12.dp, top = 4.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // DIE FLAECHENFARBE, NICHT DER TON: die Kacheln sind in beiden Schemata
            // hell, der abgedunkelte Ton sah im hellen aus wie eine Bahnlinie.
            val linie = remember(punkte) { punkte.map { it.lat to it.lon } }
            if (punkte.size >= 2) Kartenvorschau(
                linie, Farben.sport(t.sport).flaeche(),
                Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(10.dp)), onClick,
            )
            // Vier feste Faecher bis zur Schrift 1,15; darueber liefe "12.3 km" aus seinem
            // Fach, dann brechen die Kennzahlen um wie im Detail.
            if (LocalDensity.current.fontScale <= 1.15f) {
                Row(Modifier.fillMaxWidth().padding(start = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    werte.forEach { (angabe, name) -> Kennzahl(angabe.zahl, angabe.einheit, name, Modifier.weight(1f)) }
                    // Leere Faecher halten das Raster: zwei Werte stehen links, nicht gestreckt.
                    repeat(4 - werte.size) { Spacer(Modifier.weight(1f)) }
                }
            } else Kennzahlen {
                werte.forEach { (angabe, name) -> Kennzahl(angabe.zahl, angabe.einheit, name) }
            }
        }
    }
}

/** Eine Karte aus Zeilen mit Kopf - fuer "Set up". */
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
    Knopfzeile(
        stringResource(R.string.ks_sp_hc), unter, { Tonkreis(Zeichen.herz, Farben.puls) },
        stringResource(R.string.ks_n_erlauben), erlaube, unterFarbe = Farben.warnung.ton(),
    )
}

@Composable
private fun RoutenZeile(erlaubt: Boolean, erlaube: () -> Unit) {
    Knopfzeile(
        stringResource(R.string.ks_sp_routen),
        stringResource(if (erlaubt) R.string.ks_sp_nur_nutzung else R.string.ks_ei_standort_nicht),
        { Tonkreis(Zeichen.ort, Farben.warnung, alpha = 0.18f) },
        stringResource(R.string.ks_n_erlauben), erlaube, unterFarbe = Farben.warnung.ton(),
    )
}
