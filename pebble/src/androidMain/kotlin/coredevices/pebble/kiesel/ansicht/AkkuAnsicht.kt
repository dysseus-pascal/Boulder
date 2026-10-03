package coredevices.pebble.kiesel.ansicht

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import coredevices.pebble.R
import coredevices.pebble.kiesel.Akkurechnung
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.Trainingsanalyse
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.NachtEntity
import org.koin.compose.koinInject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.floor

/** Der Akku der Uhr - aus den Naechten (siehe Akkurechnung). */
@Composable
internal fun AkkuAnsicht() {
    val context = LocalContext.current
    val api = koinInject<HealthDataApi>()
    val libPebble = koinInject<LibPebble>()
    val uhren by libPebble.watches.collectAsState()
    val aktuell = uhren.filterIsInstance<ConnectedPebbleDevice>().firstNotNullOfOrNull { it.batteryLevel }
    var naechte by remember { mutableStateOf<List<NachtEntity>?>(null) }
    suspend fun lade() {
        val jetzt = Instant.now()
        naechte = runCatching { api.getNaechte(jetzt.minus(Duration.ofDays(14)).epochSecond, jetzt.epochSecond) }.getOrDefault(emptyList())
    }
    // Einmal beim Oeffnen, und wieder, wenn die Uhr etwas schickt - etwa die
    // Nacht, die gerade ankommt.
    LaunchedEffect(Unit) {
        lade()
        libPebble.healthDataUpdated.collect { lade() }
    }
    val geladen = naechte
    if (geladen == null) {
        Ladekreis()
        return
    }
    val stand = remember(geladen, aktuell) { Akkurechnung.rechne(geladen, aktuell, Instant.now()) }
    val zone = ZoneId.systemDefault()
    // Dieselben Naechte wie die Schlafseite, die laengste je Aufwachtag: ein Nachschlaf
    // am Morgen stand sonst als eigene Nacht da. Gerechnet wird weiter mit allen
    // Stuecken - jedes traegt zwei echte Akkustaende.
    val tagesnaechte = remember(geladen) { Gesundheitsrechnung.jeTag(geladen, zone).map { it.beginnUtc }.toSet() }
    val liste = stand.naechte.filter { it.beginnUtc in tagesnaechte }

    Seite {
        Kasten {
            Column(Modifier.padding(INNEN)) {
                Kennzahlen {
                    val jetzt = aktuell?.let { Formate.prozentTeile(it) }
                    // "now" bleibt auch ohne Wert stehen: sein Strich sagt, dass gerade keine Uhr verbunden ist.
                    Kennzahl(jetzt?.zahl ?: Formate.FEHLT, jetzt?.einheit, stringResource(R.string.ks_akku_jetzt))
                    // Abgerundet wie die Restzeit im Satz auf Health: lieber zu wenig als zu viel.
                    Akkuwert(stand.tageUebrig?.let { floor(it * 10) / 10 }, 1, null, stringResource(R.string.ks_akku_uebrig), geschaetzt = true)
                    Akkuwert(stand.jeTag, 0, "%", stringResource(R.string.ks_akku_je_tag), geschaetzt = true)
                    Akkuwert(stand.tageJeLadung, 1, null, stringResource(R.string.ks_akku_je_ladung), geschaetzt = true)
                    Akkuwert(stand.nachtJeStunde, 1, "%/h", stringResource(R.string.ks_akku_nacht))
                    Akkuwert(stand.tagJeStunde, 1, "%/h", stringResource(R.string.ks_akku_tag))
                }
                // Nur der Leerzustand; wie geschaetzt wird, erklaert die Seite nicht (keine Textwaende).
                if (stand.naechte.isEmpty()) Unterzeile(stringResource(R.string.ks_akku_keine))
            }
        }
        if (stand.punkte.size >= 2) {
            // Die Achse in Tagen ab Mitternacht des ersten Tages: jeder ganze Wert ist ein
            // Tagesbeginn und traegt sein Datum. Gezaehlte Tage ("1") sagten ohne Erklaerung nichts.
            val ersterTag = Instant.ofEpochSecond(stand.punkte.first().first).atZone(zone).toLocalDate()
            val letzterTag = Instant.ofEpochSecond(stand.punkte.last().first).atZone(zone).toLocalDate()
            val mitternacht = ersterTag.atStartOfDay(zone).toEpochSecond()
            // TalkBack hoert dieselben Zahlen, die man an der Linie abliest: Anfang, Ende, Tiefpunkt.
            val satz = stringResource(
                R.string.ks_akku_bild,
                Formate.datumKurz(ersterTag), Formate.datumKurz(letzterTag),
                Formate.prozent(stand.punkte.first().second), Formate.prozent(stand.punkte.last().second),
                Formate.prozent(stand.punkte.minOf { it.second }),
            )
            Abschnitt(stringResource(R.string.ks_akku_verlauf)) {
                // Der Satz ERSETZT den des Bildes (Bereich und Strecke): fuer den Akku sagen Datum und Prozent mehr.
                Verlauf(
                    stand.punkte.map { (t, p) -> Trainingsanalyse.Verlaufspunkt((t - mitternacht) / 86400.0, p.toDouble()) },
                    Farben.akku.ton(), "%", zaehlachse = true, bereich = 0.0..100.0,
                    achsenText = { Formate.datumKurz(ersterTag.plusDays(it.toLong())) },
                    beschreibung = satz,
                )
            }
        }
        if (liste.isNotEmpty()) {
            Kasten {
                Kopf(stringResource(R.string.ks_akku_naechte))
                val laden = stringResource(R.string.ks_akku_geladen)
                liste.asReversed().forEachIndexed { i, n ->
                    if (i > 0) Trenner(INNEN)
                    // Beim Laden gibt es keinen Verbrauch: dann steht "charging" im Satz, rechts keine Zahl.
                    val rate = n.jeStunde?.takeIf { !n.geladen }?.let { Formate.zahl(it, 1) }
                    Zeile(
                        Formate.datum(n.aufwachen.atZone(zone).toLocalDate()),
                        unter = listOfNotNull(
                            Formate.uhrzeit(context, n.einschlafen, zone) + " – " + Formate.uhrzeit(context, n.aufwachen, zone),
                            // Von-bis wie die Zeiten davor, mit demselben Strich: der Textpfeil war das einzige "→" der App.
                            Formate.prozent(n.prozentVon) + " – " + Formate.prozent(n.prozentBis),
                            laden.takeIf { n.geladen },
                        ).joinToString(" · "),
                        zahl = if (n.geladen) null else rate ?: Formate.FEHLT,
                        einheit = if (rate != null) "%/h" else null,
                    )
                }
            }
        }
    }
}

/**
 * Ein Akkuwert als [Kennzahl]. OHNE WERT FEHLT ER GANZ, wie jede fehlende
 * Kennzahl in Sport und Detail: ohne verbundene Uhr standen sonst vier
 * Striche gross nebeneinander. Mit [geschaetzt] steht "≈" davor - Restzeit
 * und Ladung sind Hochrechnungen, keine Messungen.
 */
@Composable
private fun Akkuwert(w: Double?, nachkomma: Int, einheit: String?, name: String, geschaetzt: Boolean = false) {
    if (w == null) return
    val text = Formate.zahl(w, nachkomma)
    Kennzahl(if (geschaetzt) Formate.ungefaehr(text) else text, einheit, name)
}
