package coredevices.pebble.kiesel.ansicht

import android.widget.Toast
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coredevices.pebble.R
import coredevices.pebble.kiesel.Akkurechnung
import coredevices.pebble.kiesel.Bandurteil
import coredevices.pebble.kiesel.GesundheitWidget
import coredevices.pebble.kiesel.GesundheitsStand
import coredevices.pebble.kiesel.Gesundheitsdaten
import coredevices.pebble.kiesel.Fenster
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.versuche
import coredevices.pebble.kiesel.Herzurteil
import coredevices.pebble.kiesel.TrainingEintrag
import coredevices.pebble.kiesel.TrainingsDaten
import coredevices.pebble.kiesel.Vergleich
import coredevices.pebble.kiesel.hrvUrteil
import coredevices.pebble.kiesel.tagesSatz
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.NachtEntity
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

/** Wohin die Seite fuehrt - die Navigation selbst haengt in GesundheitScreens. */
internal class Gesundheitswege(
    val nacht: (Long) -> Unit,
    val trend: (String) -> Unit,
    val herz: () -> Unit,
    val training: (String) -> Unit,
    val akku: () -> Unit,
    val koerper: () -> Unit,
)

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
    var stand by remember { mutableStateOf<GesundheitsStand?>(null) }
    var trainings by remember { mutableStateOf<List<TrainingEintrag>>(emptyList()) }
    var akkuNaechte by remember { mutableStateOf<List<NachtEntity>>(emptyList()) }
    var uhrzeitDesStands by remember { mutableStateOf(LocalTime.now()) }
    var neu by remember { mutableIntStateOf(0) }
    var koffeinBlatt by remember { mutableStateOf(false) }
    var praeparatBlatt by remember { mutableStateOf(false) }
    var bearbeite by remember { mutableStateOf<Bearbeitung?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { neu++ }
    // Neue Minutendaten von der Uhr: dann stimmen Schritte und Puls nicht mehr.
    LaunchedEffect(Unit) { libPebble.healthDataUpdated.collect { neu++ } }
    LaunchedEffect(neu) {
        stand = Gesundheitsdaten.lies(context, api)
        uhrzeitDesStands = LocalTime.now()
        geladen()
        trainings = TrainingsDaten.heute(context)
        val jetzt = Instant.now()
        // Dieselben Naechte wie die Akkuseite, damit die Zeile hier dasselbe sagt.
        akkuNaechte = versuche("Akku", "Naechte") { api.getNaechte(jetzt.minus(Duration.ofDays(Fenster.AKKU_TAGE)).epochSecond, jetzt.epochSecond) }.orEmpty()
    }

    val s = stand
    if (s == null) {
        Ladekreis()
        return
    }
    val satz = Gesundheitsrechnung.tagesSatz(s, uhrzeitDesStands)
    val akku = remember(akkuNaechte, akkuJetzt) { Akkurechnung.rechne(akkuNaechte, akkuJetzt, Instant.now()) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = SEITENRAND), verticalArrangement = Arrangement.spacedBy(KARTENABSTAND)) {
        item { Spacer(Modifier.height(2.dp)) }
        item { Tagessatz(s, akkuJetzt, Akkurechnung.restzeit(akku.tageUebrig), wege.akku) }
        item { Tagesstrahl(s, trainings, wege) { neu++ } }
        item { Koerperliste(s, wege, zeigeKoffein = { koffeinBlatt = true }, zeigePraeparate = { praeparatBlatt = true }) }
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
                if (!Gesundheitsdaten.trinkeKoffein(context, art)) Toast.makeText(context, fehler, Toast.LENGTH_LONG).show()
                GesundheitWidget.stosseAn(context)
                neu++
            }
        }
    }
    if (praeparatBlatt) PraeparatBlatt(s) { praeparatBlatt = false }
    bearbeite?.let { b -> Bearbeitungsblatt(b, onDismiss = { bearbeite = null }) { bearbeite = null; neu++ } }
}

/**
 * Das Urteil zur HRV fuer den Nachtblock - nur, wenn es eins gibt; erklaert
 * wird nichts. Das zum Ruhepuls steht schon im Satz darueber und hier nicht
 * noch einmal.
 */
@Composable
internal fun herzHinweis(s: GesundheitsStand): String? {
    if (s.nacht == null) return null
    // Die HRV nach ihrem Band, wie die HRV-Karte der Herzseite
    val hrv = Gesundheitsrechnung.hrvUrteil(s.hrvTage.lastOrNull()) ?: return null
    return urteilSatz(Herzurteil(0, null, null, 0), hrv)
}

/** Der Satz zu Ruhepuls und HRV, wenn wenigstens eins davon ein Urteil hat. */
@Composable
internal fun urteilSatz(ruhe: Herzurteil, hrv: Bandurteil?): String {
    if (ruhe.urteil == Vergleich.WIE_IMMER && hrv?.urteil == Vergleich.WIE_IMMER) return stringResource(R.string.ks_h_beide_wie_immer)
    val teile = listOfNotNull(
        when (ruhe.urteil) {
            Vergleich.WIE_IMMER -> stringResource(R.string.ks_h_ruhe_wie_immer)
            Vergleich.HOEHER -> stringResource(R.string.ks_h_ruhe_hoeher, ruhe.abstand)
            Vergleich.TIEFER -> stringResource(R.string.ks_h_ruhe_tiefer, ruhe.abstand)
            null -> null
        },
        hrv?.let {
            when (it.urteil) {
                Vergleich.WIE_IMMER -> stringResource(R.string.ks_h_hrv_wie_immer)
                Vergleich.HOEHER -> stringResource(R.string.ks_h_hrv_hoeher, it.abstand)
                Vergleich.TIEFER -> stringResource(R.string.ks_h_hrv_tiefer, it.abstand)
            }
        },
    )
    return teile.joinToString(" ")
}
