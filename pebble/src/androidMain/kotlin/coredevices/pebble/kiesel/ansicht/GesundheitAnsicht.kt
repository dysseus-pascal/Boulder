package coredevices.pebble.kiesel.ansicht

import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coredevices.pebble.R
import coredevices.pebble.kiesel.Akkurechnung
import coredevices.pebble.kiesel.Bandurteil
import coredevices.pebble.kiesel.GesundheitWidget
import coredevices.pebble.kiesel.GesundheitsStand
import coredevices.pebble.kiesel.Gesundheitsdaten
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.Herzurteil
import coredevices.pebble.kiesel.Nacht
import coredevices.pebble.kiesel.Praeparatlage
import coredevices.pebble.kiesel.SCHLAFZIEL_MIN
import coredevices.pebble.kiesel.SCHRITTZIEL
import coredevices.pebble.kiesel.Schlafurteil
import coredevices.pebble.kiesel.Schrittlage
import coredevices.pebble.kiesel.Tagesmerkmal
import coredevices.pebble.kiesel.Tagessatz
import coredevices.pebble.kiesel.Tageszeit
import coredevices.pebble.kiesel.TrainingEintrag
import coredevices.pebble.kiesel.TrainingsDaten
import coredevices.pebble.kiesel.Vergleich
import coredevices.pebble.kiesel.hrvUrteil
import coredevices.pebble.kiesel.phasenAnteile
import coredevices.pebble.kiesel.praeparatzeile
import coredevices.pebble.kiesel.pulsFrisch
import coredevices.pebble.kiesel.ringAnteil
import coredevices.pebble.kiesel.ruhepulsUrteil
import coredevices.pebble.kiesel.schrittwoche
import coredevices.pebble.kiesel.tagesSatz
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.NachtEntity
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/** Wohin die Seite fuehrt - die Navigation selbst haengt in GesundheitScreens. */
internal class Gesundheitswege(
    val nacht: (Long) -> Unit,
    val trend: (String) -> Unit,
    val herz: () -> Unit,
    val training: (String) -> Unit,
    val akku: () -> Unit,
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
    var training by remember { mutableStateOf<TrainingEintrag?>(null) }
    var akkuNaechte by remember { mutableStateOf<List<NachtEntity>>(emptyList()) }
    var uhrzeitDesStands by remember { mutableStateOf(LocalTime.now()) }
    var neu by remember { mutableIntStateOf(0) }
    var koffeinBlatt by remember { mutableStateOf(false) }
    var praeparatBlatt by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { neu++ }
    // Neue Minutendaten von der Uhr: dann stimmen Schritte und Puls nicht mehr.
    LaunchedEffect(Unit) { libPebble.healthDataUpdated.collect { neu++ } }
    LaunchedEffect(neu) {
        stand = Gesundheitsdaten.lies(context, api)
        uhrzeitDesStands = LocalTime.now()
        geladen()
        training = TrainingsDaten.letztes(context)
        val jetzt = Instant.now()
        akkuNaechte = runCatching { api.getNaechte(jetzt.minus(Duration.ofDays(14)).epochSecond, jetzt.epochSecond) }.getOrDefault(emptyList())
    }

    val s = stand
    if (s == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val satz = Gesundheitsrechnung.tagesSatz(s, uhrzeitDesStands)
    val akku = remember(akkuNaechte, akkuJetzt) { Akkurechnung.rechne(akkuNaechte, akkuJetzt, Instant.now()) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Spacer(Modifier.height(2.dp)) }
        item { Tagessatz(s, akkuJetzt, akku.tageUebrig?.roundToInt(), wege.akku) }
        item { Tagesstrahl(s, training, wege) { neu++ } }
        item { Koerperliste(s, wege, zeigeKoffein = { koffeinBlatt = true }, zeigePraeparate = { praeparatBlatt = true }) }
        if (!satz.ohneDaten) item { Schrittwoche(s) { wege.trend("bewegung") } }
        item { Spacer(Modifier.height(12.dp)) }
    }

    if (koffeinBlatt) {
        KoffeinBlatt(onDismiss = { koffeinBlatt = false }) { name, mg ->
            koffeinBlatt = false
            val fehler = context.getString(R.string.ks_h_koffein_fehler)
            scope.launch {
                // Ohne Rueckmeldung glaubte man den Kaffee eingetragen, und die Zahl stuende still.
                if (!Gesundheitsdaten.trinkeKoffein(context, name, mg)) Toast.makeText(context, fehler, Toast.LENGTH_LONG).show()
                GesundheitWidget.stosseAn(context)
                neu++
            }
        }
    }
    if (praeparatBlatt) PraeparatBlatt(s) { praeparatBlatt = false }
}

private fun zahl(n: Long): String = String.format(Locale.getDefault(), "%,d", n)

private fun tagText(context: Context, tag: LocalDate): String = DateUtils.formatDateTime(
    context, tag.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
    DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL or DateUtils.FORMAT_NO_YEAR,
)

/** Das Datum oben rechts neben "Health": "Wed, Sep 30". */
internal fun heuteText(context: Context): String = tagText(context, LocalDate.now())

/** Das Urteil zu Ruhepuls und HRV - nur, wenn es eins gibt; erklaert wird nichts. */
@Composable
internal fun herzHinweis(s: GesundheitsStand): String? {
    val n = s.nacht ?: return null
    val ruhe = Gesundheitsrechnung.ruhepulsUrteil(n.ruhepuls, s.ruhepulsWoche)
    // Die HRV nach ihrem Band, wie die HRV-Karte der Herzseite
    val hrv = Gesundheitsrechnung.hrvUrteil(s.hrvTage.lastOrNull())
    if (ruhe.urteil == null && hrv == null) return null
    return urteilSatz(ruhe, hrv)
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



@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KoffeinBlatt(onDismiss: () -> Unit, trinke: (String, Int) -> Unit) {
    val t = toene()
    val getraenke = listOf(
        stringResource(R.string.ks_h_kaffee) to 80, stringResource(R.string.ks_h_espresso) to 60,
        stringResource(R.string.ks_h_tee) to 40, stringResource(R.string.ks_h_energy) to 80,
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Kopf(stringResource(R.string.ks_h_koffein_dazu).uppercase())
            getraenke.forEachIndexed { i, (name, mg) ->
                if (i > 0) Trenner()
                val beschreibung = stringResource(R.string.ks_h_getraenk, name, mg)
                Zeile(
                    name, null, "$mg", "mg", zeichen = { Tonkreis(Zeichen.tasse, FLAECHE_KOFFEIN, t.koffein, 0.30f) },
                    beschreibung = beschreibung, onClick = { trinke(name, mg) }, chevron = false,
                )
            }
        }
    }
}

/** Die Praeparate des Tages, abgehakt oder offen - die Liste, die frueher auf der Karte stand. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PraeparatBlatt(s: GesundheitsStand, onDismiss: () -> Unit) {
    val stand = s.supplemente ?: return
    val t = toene()
    val rand = MaterialTheme.colorScheme.outline
    val platz = stringResource(R.string.ks_s_platz_n)
    val liste = stand.heute { String.format(platz, it) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Kopf(stringResource(R.string.ks_h_praeparate_heute).uppercase(), stringResource(R.string.ks_s_davon_von, liste.count { it.genommen }, liste.size))
            if (liste.isEmpty()) Zeile(stringResource(R.string.ks_h_p_keine))
            liste.forEachIndexed { i, e ->
                if (i > 0) Trenner()
                Zeile(
                    e.name,
                    zeichen = {
                        if (e.genommen) Tonkreis(Zeichen.haken, FLAECHE_PRAEPARAT, t.bewegung, 0.18f)
                        else Box(Modifier.size(36.dp).padding(9.dp).border(1.6.dp, rand, CircleShape))
                    },
                )
            }
        }
    }
}
