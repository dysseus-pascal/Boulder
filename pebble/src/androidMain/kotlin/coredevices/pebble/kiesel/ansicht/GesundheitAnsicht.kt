package coredevices.pebble.kiesel.ansicht

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Akkurechnung
import coredevices.pebble.kiesel.Akteneintraege
import coredevices.pebble.kiesel.GesundheitWidget
import coredevices.pebble.kiesel.GesundheitsStand
import coredevices.pebble.kiesel.Gesundheitsdaten
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.TrainingEintrag
import coredevices.pebble.kiesel.TrainingsDaten
import coredevices.pebble.kiesel.tagesSatz
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** Wohin die Seite fuehrt - die Navigation selbst haengt in GesundheitScreens. */
internal class Gesundheitswege(
    val nacht: (Long) -> Unit,
    val trend: (String) -> Unit,
    val herz: () -> Unit,
    val akku: () -> Unit,
    val koerper: () -> Unit,
)

/** Was die Seite zeigt: der Stand, wann er gelesen wurde, und die Trainings des Tages. */
private class Gesundheitsseite(val stand: GesundheitsStand, val gelesen: LocalDateTime, val trainings: List<TrainingEintrag>)

/**
 * Die zuletzt gezeigte Seite, solange der Prozess lebt (siehe [rememberStand]),
 * nur vom selben Tag - nach Mitternacht spraeche sie sonst von gestern als heute.
 */
@Volatile
private var zuletzt: Gesundheitsseite? = null

private fun zuletztHeute() = zuletzt?.takeIf { it.gelesen.toLocalDate() == LocalDate.now() }

/**
 * Die Gesundheitsseite nach Konzept F: oben ein Satz zum Tag mit dem
 * Uhrenakku, darunter der Tag als Zeitstrahl (nur zum Ansehen), dann Herz,
 * Wasser, Praeparate und Koffein als Zeilen und die Schritte der Woche. Das
 * letzte Training steht im Sport-Reiter, nicht hier.
 */
@Composable
internal fun GesundheitAnsicht(wege: Gesundheitswege, geladen: () -> Unit = {}) {
    val context = LocalContext.current
    val api = koinInject<HealthDataApi>()
    val libPebble = koinInject<LibPebble>()
    val scope = rememberCoroutineScope()
    val uhren by libPebble.watches.collectAsState()
    val akkuJetzt = uhren.filterIsInstance<ConnectedPebbleDevice>().firstNotNullOfOrNull { it.batteryLevel }
    var neu by remember { mutableIntStateOf(0) }
    var koffeinBlatt by remember { mutableStateOf(false) }
    var praeparatBlatt by remember { mutableStateOf(false) }
    var bearbeite by remember { mutableStateOf<Bearbeitung?>(null) }

    // Einmal genommen: an ihm erkennt die Koerperliste, dass noch der letzte Stand dasteht.
    val vorher = remember { zuletztHeute() }
    // Neue Minutendaten von der Uhr (rememberUhrschub): dann stimmen Schritte und Puls nicht mehr.
    val seite = rememberStand(vorher, neu, rememberUhrschub()) { zeige ->
        val stand = Gesundheitsdaten.lies(context, api)
        val gelesen = LocalDateTime.now()
        geladen()
        // Erst der Tag, dann die Trainings aus der Akte - bis dahin die bisherigen.
        zeige(Gesundheitsseite(stand, gelesen, zuletztHeute()?.trainings.orEmpty()))
        Gesundheitsseite(stand, gelesen, TrainingsDaten.heute(context)).also { zuletzt = it }
    }

    if (seite == null) {
        Ladekreis()
        return
    }
    val s = seite.stand
    val trainings = seite.trainings
    // Jetzt, nicht die Lesezeit des Stands: die Praeparatzeile darunter urteilt ebenso nach der Uhr, und Satz und Zeile sollen dasselbe sagen.
    val platz = stringResource(R.string.ks_s_platz_n)
    val satz = Gesundheitsrechnung.tagesSatz(s, LocalDateTime.now()) { String.format(platz, it) }
    // Dieselben Naechte wie die Akkuseite, damit die Zeile hier dasselbe sagt.
    val akku = remember(s.akkuNaechte, akkuJetzt) { Akkurechnung.rechne(s.akkuNaechte, akkuJetzt, Instant.now()) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = SEITENRAND), verticalArrangement = Arrangement.spacedBy(KARTENABSTAND)) {
        item { Spacer(Modifier.height(2.dp)) }
        item { Tagessatz(satz, akkuJetzt, Akkurechnung.restzeit(akku.tageUebrig), wege.akku) }
        item { Tagesstrahl(s, satz.durchgeschlafen, trainings, wege) { neu++ } }
        item { Koerperliste(s, frisch = seite !== vorher, wege, zeigeKoffein = { koffeinBlatt = true }, zeigePraeparate = { praeparatBlatt = true }) }
        if (!satz.ohneDaten) item { Schrittwoche(s) { wege.trend("bewegung") } }
        item { Spacer(Modifier.height(12.dp)) }
    }

    if (koffeinBlatt) {
        KoffeinBlatt(
            s, onDismiss = { koffeinBlatt = false },
            bearbeite = { koffeinBlatt = false; bearbeite = it },
        ) { art ->
            koffeinBlatt = false
            val fehler = context.getString(R.string.ks_h_koffein_fehler)
            scope.launch {
                // Ohne Rueckmeldung glaubte man den Kaffee eingetragen, und die Zahl stuende still.
                if (!Akteneintraege.trinkeKoffein(context, art)) Toast.makeText(context, fehler, Toast.LENGTH_LONG).show()
                GesundheitWidget.stosseAn(context)
                neu++
            }
        }
    }
    if (praeparatBlatt) PraeparatBlatt(s) { praeparatBlatt = false }
    bearbeite?.let { b -> Bearbeitungsblatt(b, onDismiss = { bearbeite = null }) { bearbeite = null; neu++ } }
}
