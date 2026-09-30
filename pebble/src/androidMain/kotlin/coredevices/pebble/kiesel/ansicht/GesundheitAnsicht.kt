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
import coredevices.pebble.kiesel.DURCHGESCHLAFEN_MIN
import coredevices.pebble.kiesel.GesundheitWidget
import coredevices.pebble.kiesel.GesundheitsStand
import coredevices.pebble.kiesel.Gesundheitsdaten
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.HRV_SPANNE_PROZENT
import coredevices.pebble.kiesel.Herzurteil
import coredevices.pebble.kiesel.LERNNAECHTE
import coredevices.pebble.kiesel.Nacht
import coredevices.pebble.kiesel.Praeparatlage
import coredevices.pebble.kiesel.RUHEPULS_SPANNE
import coredevices.pebble.kiesel.SCHLAFZIEL_MIN
import coredevices.pebble.kiesel.SCHRITTZIEL
import coredevices.pebble.kiesel.Schlafurteil
import coredevices.pebble.kiesel.Schrittlage
import coredevices.pebble.kiesel.Tagesmerkmal
import coredevices.pebble.kiesel.Tagessatz
import coredevices.pebble.kiesel.Tageszeit
import coredevices.pebble.kiesel.TrainingEintrag
import coredevices.pebble.kiesel.TrainingsDaten
import coredevices.pebble.kiesel.VERGLEICHSNAECHTE
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
import java.time.format.TextStyle
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
 * Die Gesundheitsseite nach dem Entwurf "Empfehlung": oben ein Satz und zwei
 * Ringe fuer die beiden echten Ziele, darunter der Tag in Zeilen. KEINE
 * DIAGRAMME AUF DEM ERSTEN BILDSCHIRM: der Puls hat seine eigene Seite, die
 * Nacht die Nachtansicht, die Wochen die Trends. Wer die Seite oeffnet, will
 * wissen, wie es steht - nicht eine Achse lesen.
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
    var trendBlatt by remember { mutableStateOf(false) }

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
        item { Tageskarte(s, satz, wege) }
        item { HeuteKarte(s, wege, zeigeKoffein = { koffeinBlatt = true }, zeigePraeparate = { praeparatBlatt = true }, zeigeTrends = { trendBlatt = true }) }
        s.nacht?.let { n -> item { NachtKarte(n) { wege.nacht(n.beginnUtc) } } }
        if (!satz.ohneDaten) item { SchrittKarte(s) { wege.trend("bewegung") } }
        item { GenauerKarte(wege) { trendBlatt = true } }
        item { UhrKarte(training, akkuJetzt, akku, wege) }
        item { WorteKarte() }
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
    if (trendBlatt) TrendBlatt(onDismiss = { trendBlatt = false }) { gruppe ->
        trendBlatt = false
        wege.trend(gruppe)
    }
}

private fun zahl(n: Long): String = String.format(Locale.getDefault(), "%,d", n)

private fun tagText(context: Context, tag: LocalDate): String = DateUtils.formatDateTime(
    context, tag.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
    DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL or DateUtils.FORMAT_NO_YEAR,
)

/** Das Datum oben rechts neben "Health": "Wed, Sep 30". */
internal fun heuteText(context: Context): String = tagText(context, LocalDate.now())

@Composable
private fun urteilWort(u: Schlafurteil): String = stringResource(
    when (u) {
        Schlafurteil.AUSGERUHT -> R.string.ks_h_urteil_ausgeruht
        Schlafurteil.KURZ -> R.string.ks_h_urteil_kurz
        Schlafurteil.SEHR_KURZ -> R.string.ks_h_urteil_sehr_kurz
    }
)

@Composable
private fun Tageskarte(s: GesundheitsStand, satz: Tagessatz, wege: Gesundheitswege) {
    val context = LocalContext.current
    val t = toene()
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    val n = s.nacht
    val schritte = zahl(satz.schritte)
    val wort = satz.urteil?.let { urteilWort(it) }
    val text = when {
        satz.ohneDaten -> stringResource(R.string.ks_h_satz_leer)
        wort == null -> stringResource(if (satz.tageszeit == Tageszeit.ABEND) R.string.ks_h_satz_nur_heute else R.string.ks_h_satz_nur_bisher, schritte)
        satz.tageszeit == Tageszeit.MORGEN -> when {
            satz.durchgeschlafen -> stringResource(R.string.ks_h_satz_durch, wort)
            satz.wachMinuten == null -> stringResource(R.string.ks_h_satz_schlaf, wort, dauerText(n?.schlafMinuten?.toLong() ?: 0))
            else -> stringResource(R.string.ks_h_satz_wach, wort, satz.wachMinuten)
        }
        satz.tageszeit == Tageszeit.TAG -> stringResource(R.string.ks_h_satz_bisher, wort, schritte)
        else -> stringResource(R.string.ks_h_satz_heute, wort, schritte)
    }
    val unter = when {
        satz.ohneDaten -> stringResource(R.string.ks_h_unter_leer)
        n == null -> stringResource(R.string.ks_h_unter_keine_nacht)
        satz.tageszeit == Tageszeit.MORGEN || satz.wachMinuten == null ->
            stringResource(R.string.ks_h_unter_morgen, dauerText(n.schlafMinuten.toLong()), uhrzeit(context, n.einschlafen), uhrzeit(context, n.aufwachen))
        satz.durchgeschlafen -> stringResource(R.string.ks_h_unter_durch, uhrzeit(context, n.einschlafen), uhrzeit(context, n.aufwachen))
        else -> stringResource(R.string.ks_h_unter_wach, satz.wachMinuten, uhrzeit(context, n.einschlafen), uhrzeit(context, n.aufwachen))
    }
    Kasten {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    buildAnnotatedString {
                        // Das Urteil steht vorn und traegt die Farbe des Schlafs.
                        if (wort != null && text.startsWith(wort)) {
                            withStyle(SpanStyle(color = t.schlaf)) { append(wort) }
                            append(text.substring(wort.length))
                        } else append(text)
                    },
                    fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(unter, fontSize = 14.sp, lineHeight = 20.sp, color = grau)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) { Schlafring(n, satz) { n?.let { wege.nacht(it.beginnUtc) } } }
                Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) { Schrittring(satz) { wege.trend("bewegung") } }
            }
            if (!satz.ohneDaten) {
                Column {
                    Trenner(0.dp)
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Zeichen.info, null, Modifier.padding(top = 1.dp).size(16.dp), tint = grau)
                        Text(herzHinweis(s), fontSize = 12.sp, lineHeight = 16.sp, color = grau)
                    }
                }
            }
        }
    }
}

/** Was ueber Ruhepuls und HRV zu sagen ist - oder wie lange das Lernen noch dauert. */
@Composable
private fun herzHinweis(s: GesundheitsStand): String {
    val n = s.nacht ?: return stringResource(R.string.ks_h_ohne_nacht_hinweis, uhrzeit(LocalContext.current, zweiundzwanzigUhr()))
    if (n.ruhepuls == null && n.hrv == null) return stringResource(R.string.ks_h_ohne_herz_nacht)
    val ruhe = Gesundheitsrechnung.ruhepulsUrteil(n.ruhepuls, s.ruhepulsWoche)
    val hrv = Gesundheitsrechnung.hrvUrteil(n.hrv, s.hrvWoche)
    if (ruhe.urteil == null && hrv.urteil == null) {
        return stringResource(R.string.ks_h_lernen, LERNNAECHTE, maxOf(ruhe.frueher, hrv.frueher))
    }
    return urteilSatz(ruhe, hrv)
}

/** Der Satz zu Ruhepuls und HRV, wenn wenigstens eins davon ein Urteil hat. */
@Composable
internal fun urteilSatz(ruhe: Herzurteil, hrv: Herzurteil): String {
    if (ruhe.urteil == Vergleich.WIE_IMMER && hrv.urteil == Vergleich.WIE_IMMER) return stringResource(R.string.ks_h_beide_wie_immer)
    val teile = listOfNotNull(
        when (ruhe.urteil) {
            Vergleich.WIE_IMMER -> stringResource(R.string.ks_h_ruhe_wie_immer)
            Vergleich.HOEHER -> stringResource(R.string.ks_h_ruhe_hoeher, ruhe.abstand)
            Vergleich.TIEFER -> stringResource(R.string.ks_h_ruhe_tiefer, ruhe.abstand)
            null -> null
        },
        when (hrv.urteil) {
            Vergleich.WIE_IMMER -> stringResource(R.string.ks_h_hrv_wie_immer)
            Vergleich.HOEHER -> stringResource(R.string.ks_h_hrv_hoeher, hrv.abstand)
            Vergleich.TIEFER -> stringResource(R.string.ks_h_hrv_tiefer, hrv.abstand)
            null -> null
        },
    )
    return teile.joinToString(" ")
}

/** Die Bettzeit, die gilt, solange keine Nacht gemessen ist (siehe Gesundheitsrechnung.bettzeit). */
private fun zweiundzwanzigUhr(): Instant = LocalDate.now().atTime(22, 0).atZone(ZoneId.systemDefault()).toInstant()

/** Ring mit Beschriftung darunter - ein Knopf, wenn er weiterfuehrt. */
@Composable
private fun Ringknopf(
    beschreibung: String,
    onClick: (() -> Unit)?,
    titel: String,
    unter: String,
    unterFarbe: Color,
    pfeil: ImageVector? = null,
    ring: @Composable () -> Unit,
) {
    Column(
        Modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = beschreibung }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ring()
        Text(titel, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (pfeil != null) Icon(pfeil, null, Modifier.size(14.dp), tint = unterFarbe)
            Text(unter, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = unterFarbe)
        }
    }
}

@Composable
private fun RingMitte(gross: String, klein: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(gross, fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
        Text(klein, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Schlafring(n: Nacht?, satz: Tagessatz, oeffne: () -> Unit) {
    val t = toene()
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    val stunden = SCHLAFZIEL_MIN / 60
    val titel = stringResource(R.string.ks_h_letzte_nacht)
    if (n == null) {
        val leer = stringResource(R.string.ks_h_nicht_erfasst)
        Ringknopf("$titel, $leer", null, titel, leer, grau) {
            Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                Leerring(grau.copy(alpha = 0.35f))
                Icon(Zeichen.mond, null, Modifier.size(30.dp), tint = t.schlaf)
            }
        }
        return
    }
    val (anteil, plus) = Gesundheitsrechnung.ringAnteil(n.schlafMinuten.toDouble(), SCHLAFZIEL_MIN.toDouble())
    val abstand = satz.schlafAbstand ?: 0
    val unter = when {
        abstand > 0 -> stringResource(R.string.ks_h_ueber_ziel, dauerText(abstand.toLong()))
        abstand == 0 -> stringResource(R.string.ks_h_ziel_erreicht)
        else -> stringResource(R.string.ks_h_unter_ziel, dauerText(-abstand.toLong()))
    }
    val fuge = kartenFarben().containerColor
    Ringknopf(
        stringResource(R.string.ks_h_ring_schlaf_a11y, dauerText(n.schlafMinuten.toLong()), stunden), oeffne, titel, unter,
        if (abstand > 0) t.schlaf else grau, pfeil = if (abstand > 0) Zeichen.hoch else null,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Zielring(anteil, plus, t.schlaf, t.schlaf.copy(alpha = 0.20f), t.schlafPlus, fuge)
            RingMitte(stundenMinuten(n.schlafMinuten), stringResource(R.string.ks_h_ring_stunden, stunden))
        }
    }
}

@Composable
private fun Schrittring(satz: Tagessatz, oeffne: () -> Unit) {
    val (anteil, _) = Gesundheitsrechnung.ringAnteil(satz.schritte.toDouble(), SCHRITTZIEL.toDouble())
    val unter = when (satz.schrittlage) {
        Schrittlage.BEGONNEN -> stringResource(R.string.ks_h_tag_begonnen)
        Schrittlage.NOCH -> stringResource(R.string.ks_h_noch, zahl(satz.schritteFehlen))
        Schrittlage.ERREICHT -> stringResource(R.string.ks_h_ziel_erreicht)
        Schrittlage.ANTEIL -> stringResource(R.string.ks_h_anteil_ziel, satz.zielProzent)
    }
    val fuge = kartenFarben().containerColor
    Ringknopf(
        stringResource(R.string.ks_h_ring_schritte_a11y, zahl(satz.schritte), zahl(SCHRITTZIEL)), oeffne,
        stringResource(R.string.ks_h_schritte_heute), unter,
        if (satz.schrittlage == Schrittlage.ERREICHT) toene().bewegung else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Kein Ueberlauf: bei den Schritten ist das Ziel die Aussage, nicht wie weit darueber.
            Zielring(anteil, 0f, FLAECHE_BEWEGUNG, FLAECHE_BEWEGUNG.copy(alpha = 0.22f), FLAECHE_BEWEGUNG, fuge)
            RingMitte(zahl(satz.schritte), stringResource(R.string.ks_h_ring_von, zahl(SCHRITTZIEL)))
        }
    }
}

/** Liter ohne Einheit; ohne Wasser "0" statt "0.0" - eine Null braucht keine Nachkommastelle. */
private fun liter(ml: Int): String = if (ml == 0) "0" else Gesundheitsrechnung.literKurz(ml)

@Composable
private fun HeuteKarte(s: GesundheitsStand, wege: Gesundheitswege, zeigeKoffein: () -> Unit, zeigePraeparate: () -> Unit, zeigeTrends: () -> Unit) {
    val context = LocalContext.current
    val t = toene()
    val jetzt = Instant.now()
    Kasten {
        Kopf(
            stringResource(R.string.ks_h_heute_bisher),
            s.letzteDaten?.let { stringResource(R.string.ks_h_synchron, zeitpunkt(context, it)) },
            stringResource(R.string.ks_h_trends), zeigeTrends,
        )

        Zeile(
            stringResource(R.string.ks_h_aktiv), stringResource(R.string.ks_h_km_kcal, kmText(s.meter), s.kcal),
            "${s.aktivMinuten}", "min", zeichen = { Tonkreis(Zeichen.aktiv, FLAECHE_BEWEGUNG, t.bewegung, 0.24f) },
        ) { wege.trend("bewegung") }
        Trenner()

        val letzter = s.puls.maxByOrNull { it.first }
        val ruhepuls = s.nacht?.ruhepuls
        // Mit Uhrzeit vorn ohne "last night" - sonst bricht die Zeile um, die Vorlage haelt alle einzeilig.
        val pulsUnter = when {
            letzter == null -> stringResource(R.string.ks_h_kein_puls_kurz)
            Gesundheitsrechnung.pulsFrisch(letzter.first, jetzt.epochSecond) -> stringResource(R.string.ks_h_puls_jetzt) + " · " +
                (ruhepuls?.let { stringResource(R.string.ks_h_ruhe_letzte_nacht, it) } ?: stringResource(R.string.ks_h_keine_ruhe))
            else -> zeitpunkt(context, Instant.ofEpochSecond(letzter.first)) + " · " +
                (ruhepuls?.let { stringResource(R.string.ks_h_ruhe_n, it) } ?: stringResource(R.string.ks_h_keine_ruhe))
        }
        Zeile(
            stringResource(R.string.ks_h_herz_n), pulsUnter, letzter?.second?.toString() ?: "–", "bpm",
            zeichen = { Tonkreis(Zeichen.herz, FLAECHE_HERZ, t.herz) },
        ) { wege.herz() }
        Trenner()

        val ziel = s.wasserZiel
        val letztesGlas = s.glaeser.lastOrNull()
        val wasserUnter = when {
            letztesGlas == null -> stringResource(R.string.ks_h_kein_glas) + " · " +
                (ziel?.let { stringResource(R.string.ks_h_ziel_liter, liter(it.ml) + " l") } ?: stringResource(R.string.ks_h_kein_ziel))
            ziel != null -> pluralStringResource(R.plurals.ks_h_glaeser_von, ziel.glaeser, s.glaeser.size, ziel.glaeser, uhrzeit(context, letztesGlas))
            else -> pluralStringResource(R.plurals.ks_h_glaeser_zuletzt, s.glaeser.size, s.glaeser.size, uhrzeit(context, letztesGlas)) +
                " · " + stringResource(R.string.ks_h_kein_ziel)
        }
        Zeile(
            stringResource(R.string.ks_h_wasser_n), wasserUnter, liter(s.wasserMl), "l",
            zeichen = { Tonkreis(Zeichen.wasser, FLAECHE_WASSER, t.wasser) },
        ) { wege.trend("ernaehrung") }

        s.supplemente?.let { stand ->
            Trenner()
            val platz = stringResource(R.string.ks_s_platz_n)
            val z = Gesundheitsrechnung.praeparatzeile(stand.heute { String.format(platz, it) })
            val a = z.namen.getOrElse(0) { "" }
            val b = z.namen.getOrElse(1) { "" }
            val unter = when (z.lage) {
                Praeparatlage.KEINE -> stringResource(R.string.ks_h_p_keine)
                Praeparatlage.ALLE -> stringResource(R.string.ks_h_p_alle)
                Praeparatlage.OFFEN -> when {
                    z.weitere > 0 -> stringResource(R.string.ks_h_p_offen_n, a, b, z.weitere)
                    z.namen.size == 2 -> stringResource(R.string.ks_h_p_offen_2, a, b)
                    else -> stringResource(R.string.ks_h_p_offen_1, a)
                }
                Praeparatlage.FAELLIG -> when {
                    z.weitere > 0 -> stringResource(R.string.ks_h_p_faellig_n, a, b, z.weitere)
                    z.namen.size == 2 -> stringResource(R.string.ks_h_p_faellig_2, a, b)
                    else -> stringResource(R.string.ks_h_p_faellig_1, a)
                }
            }
            Zeile(
                stringResource(R.string.ks_h_praeparate_n), unter, "${z.genommen}", stringResource(R.string.ks_h_von_n, z.faellig),
                zeichen = { Tonkreis(Zeichen.kapsel, FLAECHE_PRAEPARAT, t.bewegung, 0.18f) },
                // Die Warnfarbe ist der einzige Akzent der Seite - nur fuer das, was nach dem ersten Haken liegen blieb.
                unterFarbe = if (z.lage == Praeparatlage.OFFEN) t.warnung else null,
                onClick = zeigePraeparate,
            )
        }
        Trenner()

        val aktiv = Gesundheitsrechnung.koffeinAktiv(s.koffein, jetzt).roundToInt()
        val koffeinUnter = if (aktiv <= 0) stringResource(R.string.ks_h_koffein_keins) else {
            val bett = Gesundheitsrechnung.bettzeit(s.nacht?.einschlafen, jetzt, ZoneId.systemDefault())
            bett?.let { stringResource(R.string.ks_h_koffein_bett, Gesundheitsrechnung.koffeinAktiv(s.koffein, it).roundToInt()) }
                ?: stringResource(R.string.ks_h_wirkt_noch)
        }
        Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Zeile(
                stringResource(R.string.ks_h_koffein_n), koffeinUnter, "$aktiv", "mg",
                zeichen = { Tonkreis(Zeichen.tasse, FLAECHE_KOFFEIN, t.koffein, 0.30f) },
                onClick = zeigeKoffein, chevron = false, modifier = Modifier.weight(1f),
            )
            OutlinedIconButton(
                onClick = zeigeKoffein, modifier = Modifier.padding(end = 10.dp).size(44.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Icon(Zeichen.plus, stringResource(R.string.ks_h_koffein_dazu), Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun NachtKarte(n: Nacht, oeffne: () -> Unit) {
    val context = LocalContext.current
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    Kasten {
        Kopf(
            stringResource(R.string.ks_h_letzte_nacht_k),
            stringResource(R.string.ks_h_zeitraum, uhrzeit(context, n.einschlafen), uhrzeit(context, n.aufwachen)),
            stringResource(R.string.ks_h_nachtansicht), oeffne,
        )
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    zahlMitEinheit(stundenMinuten(n.schlafMinuten), stringResource(R.string.ks_h_h_geschlafen), 24, 14),
                    Modifier.weight(1f).alignByBaseline(), lineHeight = 30.sp,
                )
                // Ohne Abschnitte waere "0 min awake" eine Behauptung aus Unwissen.
                if (n.abschnitte.isNotEmpty()) Text(stringResource(R.string.ks_h_wach_min, n.phasen[0]), Modifier.alignByBaseline(), fontSize = 13.sp, color = grau)
            }
            if (n.mitPhasen) {
                val anteile = Gesundheitsrechnung.phasenAnteile(n.phasen)
                // Reihenfolge wie im Entwurf: tief, REM, leicht - das Erholsame zuerst.
                val phasen = listOf(2 to R.string.ks_h_tief, 3 to R.string.ks_h_rem, 1 to R.string.ks_h_leicht)
                Anteilsbalken(phasen.map { (p, _) -> n.phasen[p] to PHASENFARBEN[p] })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    phasen.forEachIndexed { i, (p, name) ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(10.dp).background(PHASENFARBEN[p], CircleShape))
                                Text(stringResource(name), fontSize = 13.sp, lineHeight = 18.sp, color = grau)
                            }
                            Text(zahlMitEinheit(stundenMinuten(n.phasen[p]) + " h", "${anteile[i]} %", 18, 12), lineHeight = 24.sp)
                        }
                    }
                }
            } else if (n.abschnitte.isNotEmpty()) {
                Text(stringResource(R.string.ks_h_ohne_phasen), fontSize = 12.sp, lineHeight = 16.sp, color = grau)
            }
        }
    }
}

@Composable
private fun SchrittKarte(s: GesundheitsStand, trend: () -> Unit) {
    val context = LocalContext.current
    val woche = Gesundheitsrechnung.schrittwoche(s.wocheSchritte)
    Kasten {
        Kopf(
            stringResource(R.string.ks_h_schritte_tage), stringResource(R.string.ks_h_ziel_zahl, zahl(SCHRITTZIEL)),
            stringResource(R.string.ks_h_trends), trend,
        )
        woche.tage.forEachIndexed { i, tag ->
            if (i > 0) Trenner(14.dp)
            val unter = when (tag.merkmal) {
                Tagesmerkmal.HEUTE -> s.letzteDaten?.let { stringResource(R.string.ks_h_bisher_sync, zeitpunkt(context, it)) } ?: stringResource(R.string.ks_h_bisher)
                Tagesmerkmal.MEISTE -> stringResource(if (woche.seit == null) R.string.ks_h_meiste_woche else R.string.ks_h_meiste_bisher)
                Tagesmerkmal.ERSTER -> stringResource(R.string.ks_h_erster_tag)
                Tagesmerkmal.ZIEL -> stringResource(R.string.ks_h_ziel_erreicht)
                Tagesmerkmal.KEINS -> null
            }
            Zeile(
                if (tag.merkmal == Tagesmerkmal.HEUTE) stringResource(R.string.ks_h_heute_n) else tagText(context, tag.tag),
                unter, zahl(tag.schritte), minHoehe = 56.dp, onClick = trend,
            )
        }
        val seit = woche.seit
        val voll = woche.volleWocheAb
        // Zaehlt die Seite erst seit heute, sagt die eine Zeile schon alles.
        if (seit != null && voll != null && woche.tage.size > 1) {
            Text(
                stringResource(R.string.ks_h_zaehlt_seit, seit.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()), tagText(context, voll)),
                Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp),
                fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun GenauerKarte(wege: Gesundheitswege, zeigeTrends: () -> Unit) {
    val t = toene()
    Kasten {
        Kopf(stringResource(R.string.ks_h_genauer))
        Zeile(
            stringResource(R.string.ks_h_herz_heute), stringResource(R.string.ks_h_herz_heute_text),
            zeichen = { Tonkreis(Zeichen.herz, FLAECHE_HERZ, t.herz) }, onClick = wege.herz,
        )
        Trenner()
        Zeile(
            stringResource(R.string.ks_h_trends), stringResource(R.string.ks_h_trends_text),
            zeichen = { Tonkreis(Zeichen.trend, FLAECHE_PRAEPARAT, t.bewegung, 0.18f) }, onClick = zeigeTrends,
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun UhrKarte(training: TrainingEintrag?, akkuJetzt: Int?, akku: Akkurechnung.Stand, wege: Gesundheitswege) {
    val context = LocalContext.current
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    Kasten {
        Kopf(stringResource(R.string.ks_h_uhr))
        if (training != null) {
            val tag = training.sitzung.startTime.atZone(ZoneId.systemDefault()).toLocalDate()
            val wann = when (tag) {
                LocalDate.now() -> stringResource(R.string.ks_h_heute_klein)
                LocalDate.now().minusDays(1) -> stringResource(R.string.ks_h_gestern_klein)
                else -> tagText(context, tag)
            }
            val unter = stringResource(R.string.ks_h_letztes_training, wann) + if (training.meter > 0) " · " + kmText(training.meter) else ""
            val t = toene()
            // Die Toene der Bewegung statt der Sportfarbe: Velo-Amber stuende neben dem einen Warnakzent der Seite.
            Zeile(
                training.sitzung.title ?: stringResource(training.sport.nameId), unter, "${training.minuten}", "min",
                zeichen = { Tonkreis(training.sport.zeichen(), FLAECHE_BEWEGUNG, t.bewegung, 0.24f) },
            ) { wege.training(training.id) }
            Trenner()
        }
        val tage = akku.tageUebrig?.roundToInt()
        val unter = when {
            akkuJetzt == null -> stringResource(R.string.ks_h_nicht_verbunden)
            tage != null -> pluralStringResource(R.plurals.ks_h_akku_tage, tage, tage)
            else -> stringResource(R.string.ks_h_akku_offen)
        }
        Zeile(
            stringResource(R.string.ks_h_akku), unter, akkuJetzt?.toString() ?: "–", if (akkuJetzt != null) "%" else null,
            zeichen = { Tonkreis(Zeichen.uhr, grau, grau, 0.14f) },
        ) { wege.akku() }
        Spacer(Modifier.height(4.dp))
    }
}

/** Die Fussnote: welche Schwelle hinter welchem Wort steht. */
@Composable
private fun WorteKarte() {
    val context = LocalContext.current
    val farben = MaterialTheme.colorScheme
    val begriff = SpanStyle(fontWeight = FontWeight.Medium, color = farben.onSurface)
    fun um(stunde: Int) = uhrzeit(context, LocalDate.now().atTime(stunde, 0).atZone(ZoneId.systemDefault()).toInstant())
    val punkte = listOf(
        stringResource(R.string.ks_h_w_schlaf, SCHLAFZIEL_MIN / 60),
        stringResource(R.string.ks_h_w_durch, DURCHGESCHLAFEN_MIN),
        stringResource(R.string.ks_h_w_gewohnt, RUHEPULS_SPANNE, HRV_SPANNE_PROZENT, VERGLEICHSNAECHTE, LERNNAECHTE),
        stringResource(R.string.ks_h_w_noch, um(11), um(18)),
        stringResource(R.string.ks_h_w_bett),
    )
    Kasten {
        Kopf(stringResource(R.string.ks_h_worte))
        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            punkte.forEach { p ->
                Row {
                    Text("•", Modifier.padding(start = 4.dp, end = 8.dp), fontSize = 13.sp, lineHeight = 19.sp, color = farben.onSurface)
                    Text(mitBegriffen(p, begriff), fontSize = 13.sp, lineHeight = 19.sp, color = farben.onSurfaceVariant)
                }
            }
        }
    }
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

/** Die vier Trendgruppen zur Wahl - die Trendseite selbst zeigt immer nur eine. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrendBlatt(onDismiss: () -> Unit, oeffne: (String) -> Unit) {
    val t = toene()
    val gruppen = listOf("schlaf" to R.string.ks_tr_schlaf, "herz" to R.string.ks_tr_herz, "bewegung" to R.string.ks_tr_bewegung, "ernaehrung" to R.string.ks_tr_ernaehrung)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Kopf(stringResource(R.string.ks_h_trends_titel))
            gruppen.forEachIndexed { i, (gruppe, name) ->
                if (i > 0) Trenner()
                Zeile(stringResource(name), onClick = { oeffne(gruppe) }, zeichen = {
                    when (gruppe) {
                        "schlaf" -> Tonkreis(Zeichen.mond, PHASENFARBEN[2], t.schlaf, 0.30f)
                        "herz" -> Tonkreis(Zeichen.herz, FLAECHE_HERZ, t.herz)
                        "bewegung" -> Tonkreis(Zeichen.aktiv, FLAECHE_BEWEGUNG, t.bewegung, 0.24f)
                        else -> Tonkreis(Zeichen.wasser, FLAECHE_WASSER, t.wasser)
                    }
                })
            }
        }
    }
}
