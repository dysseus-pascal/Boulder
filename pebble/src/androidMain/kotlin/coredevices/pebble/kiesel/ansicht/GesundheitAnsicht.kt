package coredevices.pebble.kiesel.ansicht

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Akkurechnung
import coredevices.pebble.kiesel.GesundheitWidget
import coredevices.pebble.kiesel.GesundheitsStand
import coredevices.pebble.kiesel.Gesundheitsdaten
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.HrvReparatur
import coredevices.pebble.kiesel.TrainingsDaten
import coredevices.pebble.kiesel.TrainingsUebersicht
import coredevices.pebble.kiesel.tagesSatz
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.connection.LibPebble
import org.koin.compose.koinInject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/** Wohin die Seite fuehrt - die Navigation selbst haengt in GesundheitScreens. */
internal data class Gesundheitswege(
    val nacht: (Long) -> Unit,
    val trend: (String) -> Unit,
    val herz: () -> Unit,
    val akku: () -> Unit,
    /** Die Sportseite - seit 0.51 eine Unterseite von Health statt eines Reiters. */
    val sport: () -> Unit,
    /** Das Detail eines Trainings aus dem Tagesband. */
    val training: (String) -> Unit,
    /** Die Seite Water. */
    val wasser: () -> Unit,
    /** Die Seite Stack: Praeparate und Koffein, mit "Add caffeine" (bis 0.50 eigene Blaetter). */
    val stack: () -> Unit,
)

/** Was die Seite zeigt: der Stand und wann er gelesen wurde. */
private class Gesundheitsseite(val stand: GesundheitsStand, val gelesen: LocalDateTime)

/**
 * Die zuletzt gezeigte Seite, solange der Prozess lebt (siehe [rememberStand]),
 * nur vom selben Tag - nach Mitternacht spraeche sie sonst von gestern als heute.
 */
@Volatile
private var zuletzt: Gesundheitsseite? = null

private fun zuletztHeute() = zuletzt?.takeIf { it.gelesen.toLocalDate() == LocalDate.now() }

/**
 * Der Lader der Gesundheitsseiten (Health, Heart). NUR DER AUFBAU DARF DEN
 * STAND VON EBEN NEHMEN ([Gesundheitsdaten.FRISCH_MS]): Health -> Heart ->
 * Health las sonst dreimal in Sekunden dasselbe. Jeder weitere Anlass - ein
 * Schub, ein eigener Eintrag, die Rueckkehr aus einer anderen App - liest neu,
 * damit kein Glas und kein Eintrag veraltet dasteht.
 */
@Composable
internal fun rememberGesundheitslader(): suspend () -> GesundheitsStand {
    val context = LocalContext.current
    val api = koinInject<HealthDataApi>()
    val erster = remember { AtomicBoolean(true) }
    return remember(context, api) {
        { Gesundheitsdaten.lies(context, api, if (erster.getAndSet(false)) Gesundheitsdaten.FRISCH_MS else 0) }
    }
}

/**
 * Die Gesundheitsseite: oben ein Satz zum Tag mit dem Uhrenakku, darunter
 * der Tag als Band, das Sport-Feld, dann Heart & Body, Water und Stack als
 * Zeilen und die Schritte der Woche. Hier wird geladen; was zu sehen ist,
 * zeichnet [GesundheitInhalt] aus dem fertigen Stand.
 */
@Composable
internal fun GesundheitAnsicht(wege: Gesundheitswege) {
    val context = LocalContext.current
    val lies = rememberGesundheitslader()
    val libPebble = koinInject<LibPebble>()
    val uhren by libPebble.watches.collectAsState()
    val akkuJetzt = uhren.filterIsInstance<ConnectedPebbleDevice>().firstNotNullOfOrNull { it.batteryLevel }
    var neu by remember { mutableIntStateOf(0) }
    val api = koinInject<HealthDataApi>()
    // EINMAL JE INSTALLATION (0.52): die HRV der Naechte, die eine Fassung ohne HRV
    // ueberschrieb, aus Boulders eigenen Saetzen der Akte zurueck. Hier beim Oeffnen
    // von Health, abseits von Verbindung und Benachrichtigungen; danach neu lesen.
    LaunchedEffect(Unit) {
        if (HrvReparatur.beimOeffnen(context, api) > 0) {
            GesundheitWidget.stosseAn(context)
            neu++
        }
    }

    // Neue Minutendaten von der Uhr (rememberUhrschub): dann stimmen Schritte und Puls nicht mehr.
    val seite = rememberStand(remember { zuletztHeute() }, neu, rememberUhrschub()) {
        Gesundheitsseite(lies(), LocalDateTime.now()).also { zuletzt = it }
    }
    // Die Trainings fuer Band und Sport-Feld - DIESELBE UEBERSICHT WIE DIE SPORTSEITE,
    // zuerst die zuletzt geladene. Nicht mit jedem Schub der Uhr: Trainings kommen aus der
    // Akte, nicht aus den Minutendaten; neu beim Aufbau und bei der Rueckkehr in die App.
    val sport = rememberStand(TrainingsDaten.zuletzt) { TrainingsDaten.uebersicht(context) }
    val imperial = einheitenImperial()

    if (seite == null) {
        Ladekreis()
        return
    }
    GesundheitInhalt(seite.stand, sport, akkuJetzt, Instant.now(), imperial, wege) { neu++ }
}

/**
 * Was die Gesundheitsseite zeigt, aus einem fertigen Stand - ohne zu laden,
 * damit ein Test sie mit Beispieldaten zeichnen kann. [jetzt] statt der Uhr:
 * Satz, Band und Zeilen urteilen nach derselben Zeit.
 */
@Composable
internal fun GesundheitInhalt(
    s: GesundheitsStand,
    sport: TrainingsUebersicht?,
    akkuJetzt: Int?,
    jetzt: Instant,
    imperial: Boolean,
    wege: Gesundheitswege,
    geaendert: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val heute = jetzt.atZone(zone).toLocalDate()
    // Jetzt, nicht die Lesezeit des Stands: die Zeile Stack darunter urteilt ebenso nach der Uhr, und Satz und Zeile sollen dasselbe sagen.
    val platz = stringResource(R.string.ks_s_platz_n)
    val satz = Gesundheitsrechnung.tagesSatz(s, LocalDateTime.ofInstant(jetzt, zone)) { String.format(platz, it) }
    // Dieselben Naechte wie die Akkuseite, damit die Zeile hier dasselbe sagt.
    val akku = remember(s.akkuNaechte, akkuJetzt, jetzt) { Akkurechnung.rechne(s.akkuNaechte, akkuJetzt, jetzt) }
    val trainings = sport?.trainings.orEmpty()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = SEITENRAND), verticalArrangement = Arrangement.spacedBy(KARTENABSTAND)) {
        item { Spacer(Modifier.height(2.dp)) }
        item { Tagessatz(satz, akkuJetzt, Akkurechnung.restzeit(akku.tageUebrig), wege.akku) }
        item { Tagesband(s, trainings, jetzt, imperial, wege, geaendert) }
        item { Sportfeld(sport, heute, imperial, wege.sport) }
        item { Koerperliste(s, jetzt, wege) }
        if (!satz.ohneDaten) item { Schrittwoche(s) { wege.trend("bewegung") } }
        item { Spacer(Modifier.height(12.dp)) }
    }
}
