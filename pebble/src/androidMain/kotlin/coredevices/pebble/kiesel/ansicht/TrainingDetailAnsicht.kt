package coredevices.pebble.kiesel.ansicht

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import androidx.health.connect.client.records.ExerciseSegment
import coredevices.pebble.R
import org.koin.compose.koinInject
import io.rebble.libpebblecommon.connection.HealthDataApi
import coredevices.pebble.kiesel.Akte
import coredevices.pebble.kiesel.Formate
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
import kotlin.math.roundToInt
import kotlin.math.roundToLong

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
        if (!geladen) Ladekreis() else Leerseite(stringResource(R.string.ks_t_noch_keins))
        return
    }
    val t = d.eintrag
    val s = t.sitzung
    // Derselbe Name wie in der Liste - auch ein deutscher Titel aus Kiesel-Helper auf Englisch.
    val name = trainingsName(t)
    LaunchedEffect(name) { setzeTitel(name) }
    // Derselbe Maximalpuls fuer Zonen und kcal wie ueberall in Boulder (Herzgrenzen).
    val (ruhepuls, maxpuls) = g
    val imperial = einheitenImperial()
    // Der Ton, nicht die Flaeche: er traegt Linien und Schrift auf der Karte (siehe Farben.sport).
    val ton = Farben.sport(t.sport).ton()
    val routeHolen = if (d.punkte.size < 2 && TrainingsDaten.routeGesperrt(s)) ({ routeStarter.launch(s.metadata.id) }) else null

    Seite {
        // Derselbe Kopf und dieselben Kennzahlen wie das grosse Training im Sport-Reiter.
        Kasten {
            Detailkopf(
                name, Formate.wochentagZeit(context, s.startTime, mitDatum = true),
                if (!t.eigen) stringResource(R.string.ks_n_quelle, d.quelle) else null,
            ) { SportKreis(t.sport, 48.dp, 26.dp) }
            Box(Modifier.padding(start = INNEN, end = INNEN, bottom = 14.dp)) {
                Kennzahlen {
                    // Dauer und Tempo aus derselben, aktiven Zeit - wie auf der Sportseite und der Uhr.
                    Kennzahl(Formate.dauerTeile(t.minuten), stringResource(R.string.ks_t_dauer))
                    if (t.meter > 0) {
                        Kennzahl(streckeVon(t, imperial), stringResource(R.string.ks_t_strecke))
                        if (t.sport.rad) {
                            Formate.geschwindigkeit(t.meter, t.sekunden, imperial)?.let { Kennzahl(it, stringResource(R.string.ks_t_tempo_rad)) }
                        } else if (t.sport != Sport.Schwimmen) {
                            Formate.tempo(t.meter, t.sekunden, imperial)?.let { Kennzahl(it, stringResource(R.string.ks_t_tempo)) }
                        }
                        // Die Punkte sind schon auf die Sitzung beschnitten (TrainingsDaten.punkte).
                        val auf = remember(d.punkte) { Trainingsanalyse.aufstieg(d.punkte) }
                        if (auf > 0) Kennzahl(Formate.hoehe(auf, imperial), stringResource(R.string.ks_t_aufstieg))
                    }
                    if (d.puls.isNotEmpty()) {
                        Kennzahl(Formate.zahl(d.puls.map { it.bpm }.average().toInt().toLong()), "bpm", stringResource(R.string.ks_t_puls_schnitt))
                        Kennzahl(Formate.zahl(d.puls.maxOf { it.bpm }), "bpm", stringResource(R.string.ks_t_puls_max))
                        // DIE AKTIVEN KCAL, DIESELBEN WIE IN DER AKTE: dort steht, was
                        // Boulder aus dem Puls schrieb; sonst dieselbe Rechnung hier.
                        (d.kcal ?: koerper?.let { k ->
                            Trainingsanalyse.aktiveKalorien(d.puls, k.kg, k.alter, k.geschlecht, ruhepuls, maxpuls)
                        })?.takeIf { it > 0 }?.let {
                            Kennzahl(Formate.ungefaehr(Formate.zahl(it.roundToLong())), "kcal", stringResource(R.string.ks_t_kcal))
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
            else -> Strecke(d, ton, maxpuls, imperial, routeHolen)
        }
        Sauerstoff(d, ton)

        // NUR BEI FREMDEN: die eigene Notiz traegt Saetze und Bahnen, und die
        // stehen oben schon als Zahlen. Aeltere eigene Notizen hatten dazu
        // Puls und kcal der Uhr, roh - sie widersprachen den Zahlen darueber.
        s.notes?.takeIf { it.isNotBlank() && !t.eigen }?.let { notiz ->
            Abschnitt(stringResource(R.string.ks_n_notiz)) { Fliesstext(notiz) }
        }
    }
}

/** Eine Kennzahl aus einer Angabe von Formate - Zahl und Einheit getrennt. */
@Composable
private fun Kennzahl(angabe: Formate.Angabe, name: String) = Kennzahl(angabe.zahl, angabe.einheit, name)

/** Ein Tempo aus m/s: auf dem Rad als Geschwindigkeit, sonst je km oder mi; unter 0,2 m/s gibt es keins. */
private fun tempoAngabe(ms: Double, rad: Boolean, imperial: Boolean): Formate.Angabe? =
    if (rad) Formate.geschwindigkeit(ms * 3.6, imperial) else Sportrechnung.tempo(ms, 1)?.let { Formate.tempo(it, imperial) }

/** Kilometer und Saetze dauern selten eine Stunde: "4:05", darueber die Dauer mit Einheit. */
private fun zeit(sekunden: Long): String = if (sekunden >= 3600) Formate.dauer(sekunden / 60) else Formate.minutenSekunden(sekunden)


// --- Zonen ---------------------------------------------------------------------

@Composable
private fun Zonenkarte(d: TrainingDetail, maxpuls: Int) {
    val zonen = Trainingsanalyse.zonenSekunden(d.puls, maxpuls)
    Abschnitt(stringResource(R.string.ks_v_pulszonen)) {
        Zonenbalken(zonen)
        Zonenliste(zonen, maxpuls)
    }
}

/** Der Puls ueber die Zeit, mit den Zonen dahinter und senkrechten Baendern (Saetze, Bahnen). */
@Composable
private fun PulsUeberZeit(d: TrainingDetail, ton: Color, maxpuls: Int, xBaender: List<Pair<Double, Double>> = emptyList()) {
    if (d.puls.size < 2) return
    Abschnitt(stringResource(R.string.ks_v_puls_zeit)) {
        Verlauf(
            d.puls.map { Verlaufspunkt(it.sekunde.toDouble(), it.bpm.toDouble()) },
            ton, "bpm", baender = zonenbaender(maxpuls), zeitachse = true, xBaender = xBaender,
        )
    }
}

// --- Laufen, Bike, MTB, Wandern --------------------------------------------------

@Composable
private fun Strecke(d: TrainingDetail, ton: Color, maxpuls: Int, imperial: Boolean, routeHolen: (() -> Unit)?) {
    val sport = d.eintrag.sport
    val strecke = remember(d) { Trainingsanalyse.strecke(d.punkte) }
    if (strecke.size < 2) {
        // Die Route einer fremden App gibt es erst nach einer Zustimmung - eine Tat auf einer Zeile.
        if (routeHolen != null) Kasten {
            Knopfzeile(
                stringResource(R.string.ks_t_route), zeichen = { Tonkreis(Zeichen.ort, Farben.sport(sport)) },
                knopf = stringResource(R.string.ks_t_zeigen), onClick = routeHolen,
            )
        }
        val ohneRoute = routeHolen == null && sport in setOf(Sport.Laufen, Sport.Bike, Sport.Mtb, Sport.Wandern)
        val ohnePuls = d.puls.size < 2
        if (ohneRoute || ohnePuls) Kasten {
            if (ohneRoute) Leerzeile(stringResource(R.string.ks_t_keine_strecke))
            if (ohnePuls) Leerzeile(stringResource(R.string.ks_v_nichts_weiter))
        }
        PulsUeberZeit(d, ton, maxpuls)
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
    if (wandern && mitHoehe) Hoehenkarte(d, hoehe, strecke, imperial)

    val pulsStrecke = remember(d, strecke) { Trainingsanalyse.pulsUeberStrecke(puls, strecke) }
    if (pulsStrecke.size >= 2) {
        Abschnitt(stringResource(R.string.ks_v_puls_strecke)) {
            Verlauf(pulsStrecke, ton, "bpm", baender = zonenbaender(maxpuls), imperial = imperial)
        }
    }

    // Auf dem Rad heisst es Geschwindigkeit, wie oben im Kopf.
    Abschnitt(stringResource(if (rad) R.string.ks_v_tempo_rad else R.string.ks_v_tempo)) {
        val bewegt = Trainingsanalyse.bewegt(strecke)
        Kennzahlen {
            // KEIN ZWEITER SCHNITT: der steht oben, aus der aktiven Zeit wie
            // auf der Uhr. Hier die Spitze und die Zeit in Bewegung.
            tempoAngabe(strecke.maxOf { it.tempo }, rad, imperial)?.let { Kennzahl(it, stringResource(R.string.ks_v_spitze)) }
            if (wandern && mitHoehe) {
                val mh = Trainingsanalyse.aufstieg(d.punkte) / (bewegt.coerceAtLeast(60) / 3600.0)
                Kennzahl(Formate.steigrate(mh, imperial), stringResource(R.string.ks_t_aufstieg))
            } else {
                Kennzahl(Formate.dauerTeile(bewegt / 60), stringResource(R.string.ks_v_bewegt))
            }
        }
        // Die Kurve in der Einheit des Schalters; gefaerbt im Ton der Skala, die die Karte als Flaeche traegt.
        val kurve = remember(strecke, imperial) {
            Trainingsanalyse.tempoUeberStrecke(strecke).map { it.copy(wert = Formate.geschwindigkeitIn(it.wert, imperial)) }
        }
        val unten = Formate.geschwindigkeitIn(langsam * 3.6, imperial)
        val oben = Formate.geschwindigkeitIn(schnell * 3.6, imperial)
        val dunkel = Farben.dunkel()
        Verlauf(
            kurve, ton, Formate.geschwindigkeitEinheit(imperial),
            farbeJeWert = if (wandern) null else { v -> Farben.tempo(((v - unten) / (oben - unten)).toFloat()).tonIm(dunkel) },
            flaeche = wandern, imperial = imperial,
        )
    }

    // Die Karte: das Tempo als Farbe - beim Wandern die Steigung.
    val kartenform = Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(10.dp))
    if (wandern && mitHoehe) {
        Abschnitt(stringResource(R.string.ks_v_steigung_karte)) {
            Farbkarte(strecke, remember(strecke) { Trainingsanalyse.steigung(strecke) }, -15.0, 15.0, kartenform)
            Farblegende(
                stringResource(R.string.ks_v_bergab, Formate.vorzeichen(-15.0)),
                stringResource(R.string.ks_v_bergauf, Formate.vorzeichen(15.0)),
            )
        }
    } else {
        Abschnitt(stringResource(if (rad) R.string.ks_v_tempo_karte_rad else R.string.ks_v_tempo_karte)) {
            Farbkarte(strecke, strecke.map { it.tempo }, langsam, schnell, kartenform)
            Farblegende(
                tempoAngabe(langsam, rad, imperial)?.text ?: Formate.FEHLT,
                tempoAngabe(schnell, rad, imperial)?.text ?: Formate.FEHLT,
            )
        }
    }

    if (!wandern && mitHoehe) Hoehenkarte(d, hoehe, strecke, imperial)

    // Mit "Imperial Units" Meile fuer Meile - eine km-Tabelle neben mi oben passte nicht.
    val stueck = if (imperial) Formate.METER_JE_MEILE else 1000.0
    val km = remember(d, strecke, stueck) { Trainingsanalyse.kilometer(strecke, puls, pausen, stueck) }
    if (km.size >= 2) Kilometerkarte(km, rad, ton, imperial, stueck)
}

@Composable
private fun Hoehenkarte(d: TrainingDetail, hoehe: List<Verlaufspunkt>, strecke: List<Trainingsanalyse.Streckenpunkt>, imperial: Boolean) {
    Abschnitt(stringResource(R.string.ks_v_hoehenprofil)) {
        val steil = remember(strecke) { Trainingsanalyse.steigung(strecke).maxOrNull() ?: 0.0 }
        Kennzahlen {
            Kennzahl(Formate.hoehe(Trainingsanalyse.aufstieg(d.punkte), imperial), stringResource(R.string.ks_t_aufstieg))
            Kennzahl(Formate.hoehe(hoehe.minOf { it.wert }, imperial), stringResource(R.string.ks_v_tiefster))
            Kennzahl(Formate.hoehe(hoehe.maxOf { it.wert }, imperial), stringResource(R.string.ks_v_hoechster))
            // Erst ab 5 % - flacher ist kein Anstieg, der eine eigene Zahl braucht.
            if (steil >= 5) Kennzahl(Formate.prozentTeile(steil.roundToInt()), stringResource(R.string.ks_v_steilste))
        }
        Verlauf(
            hoehe.map { it.copy(wert = Formate.hoeheIn(it.wert, imperial)) }, Farben.sport(Sport.Wandern).ton(),
            Formate.hoeheEinheit(imperial), hoehe = 130.dp, imperial = imperial,
        )
    }
}

@Composable
private fun Kilometerkarte(km: List<Trainingsanalyse.Kilometer>, rad: Boolean, ton: Color, imperial: Boolean, stueck: Double) {
    // Ein angefangener Abschnitt am Ende zaehlt nicht als schnellster.
    val voll = stueck * 0.9
    val schnellste = km.filter { it.meter >= voll }.minByOrNull { it.sekunden / it.meter }
    Abschnitt(stringResource(if (imperial) R.string.ks_v_meile_fuer_meile else R.string.ks_v_km_fuer_km)) {
        Tabelle(
            // Der Aufstieg mit seiner Einheit im Kopf: "+40" neben mi liess offen, ob Fuss oder Meter.
            kopf = listOf(
                Formate.streckeEinheit(imperial), stringResource(R.string.ks_t_zeit),
                if (rad) Formate.geschwindigkeitEinheit(imperial) else Formate.tempoEinheit(imperial),
                stringResource(R.string.ks_v_puls_kurz), stringResource(R.string.ks_v_auf, Formate.hoeheEinheit(imperial)),
            ),
            zeilen = km.map { k ->
                listOf(
                    // Der angefangene Abschnitt mit seiner Laenge statt einer Fussnote.
                    if (k.meter < voll) Formate.zahl(Formate.streckeIn(k.meter, imperial), 1) else Formate.zahl(k.nummer.toLong()),
                    zeit(k.sekunden),
                    (if (rad) Formate.geschwindigkeit(k.meter, k.sekunden, imperial) else Formate.tempo(k.meter, k.sekunden, imperial))?.zahl ?: Formate.FEHLT,
                    k.pulsMittel?.let { Formate.zahl(it) } ?: Formate.FEHLT,
                    if (k.aufstieg >= 1) Formate.vorzeichen(Formate.hoeheIn(k.aufstieg, imperial)) else Formate.FEHLT,
                )
            },
            spalten = KM_SPALTEN,
            hervor = { i -> if (km[i] == schnellste) 2 to ton else null },
        )
    }
}

/** Breite je Spalte; null heisst: nimmt den Rest. */
private val KM_SPALTEN = listOf<Dp?>(44.dp, null, 64.dp, 48.dp, 48.dp)
private val SATZ_SPALTEN = listOf<Dp?>(40.dp, 48.dp, null, 56.dp, 56.dp)

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
        if (d.puls.size < 2) Kasten { Leerzeile(stringResource(R.string.ks_v_keine_saetze)) }
        return
    }
    Abschnitt(stringResource(R.string.ks_v_saetze_titel)) {
        // Dieselbe Rechnung wie die Notiz in der Akte (Kieselsport.ruheSchnitt).
        val schnitt = Kieselsport.ruheSchnitt(ruhe)
        Kennzahlen {
            Kennzahl(Formate.zahl(saetze.size.toLong()), null, stringResource(R.string.ks_v_saetze))
            Kennzahl(Formate.zahl(saetze.sumOf { it.third }.toLong()), null, stringResource(R.string.ks_v_wdh_gesamt))
            if (schnitt > 0) Kennzahl(Formate.zahl(schnitt), "s", stringResource(R.string.ks_v_pause_schnitt))
        }
        Trainingsanalyse.erholung(d.puls, pausen)?.let { e ->
            Fliesstext(
                stringResource(
                    when {
                        e >= 20 -> R.string.ks_v_erholung_schnell
                        e >= 10 -> R.string.ks_v_erholung_solide
                        else -> R.string.ks_v_erholung_oben
                    },
                    Formate.zahl(e),
                ),
            )
        }
        val meiste = saetze.maxOf { it.third }
        Tabelle(
            kopf = listOf(
                stringResource(R.string.ks_v_satz), stringResource(R.string.ks_v_wdh), stringResource(R.string.ks_t_dauer),
                stringResource(R.string.ks_t_puls_schnitt), stringResource(R.string.ks_v_pause),
            ),
            zeilen = saetze.mapIndexed { i, (von, bis, wdh) ->
                val pause = ruhe.getOrNull(i)?.takeIf { it > 0 }
                listOf(
                    Formate.zahl((i + 1).toLong()),
                    Formate.zahl(wdh.toLong()),
                    Formate.menge(bis - von, "s").text,
                    Trainingsanalyse.pulsMittel(d.puls, von, bis)?.let { Formate.zahl(it) } ?: Formate.FEHLT,
                    pause?.let { Formate.menge(it, "s").text } ?: Formate.FEHLT,
                )
            },
            spalten = SATZ_SPALTEN,
            hervor = { i -> if (saetze[i].third == meiste && meiste > 0) 1 to ton else null },
        )
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
    Abschnitt(stringResource(R.string.ks_v_ruhe)) {
        if (d.puls.size >= 2) {
            val ende = d.puls.last().sekunde
            val anfang = Trainingsanalyse.pulsMittel(d.puls, 0, 120)
            val schluss = Trainingsanalyse.pulsMittel(d.puls, ende - 120, ende)
            Kennzahlen {
                anfang?.let { Kennzahl(Formate.zahl(it), "bpm", stringResource(R.string.ks_v_anfang)) }
                schluss?.let { Kennzahl(Formate.zahl(it), "bpm", stringResource(R.string.ks_v_ende)) }
                Kennzahl(Formate.zahl(d.puls.minOf { it.bpm }), "bpm", stringResource(R.string.ks_v_tiefster))
            }
            if (anfang != null && schluss != null) {
                val dif = anfang - schluss
                val n = Formate.zahl(dif)
                Fliesstext(
                    when {
                        dif >= 8 -> stringResource(R.string.ks_v_gefallen, n)
                        dif >= 3 -> stringResource(R.string.ks_v_leicht_gefallen, n)
                        dif > -3 -> stringResource(R.string.ks_v_geblieben)
                        else -> stringResource(R.string.ks_v_gestiegen)
                    },
                )
            }
        }
        when {
            d.hrv != null -> Kennzahlen { Kennzahl(Formate.zahl(d.hrv), "ms", stringResource(R.string.ks_v_hrv)) }
            d.puls.size < 2 -> Unterzeile(stringResource(R.string.ks_v_kein_puls_hrv))
            else -> Unterzeile(stringResource(R.string.ks_v_keine_hrv))
        }
    }
}

// --- Schwimmen -----------------------------------------------------------------

/**
 * Im Becken zaehlt die Bahn: wie gleich sie blieben, und wo die Kraft
 * nachliess. Der Puls laeuft darunter mit, jede zweite Bahn als Band.
 * DIE BAHN BLEIBT IN METERN, auch mit "Imperial Units": das Becken ist an
 * der Uhr in Metern eingestellt, eine Bahn von 82 ft sagte niemandem etwas.
 * Darum fuehrt auch der Kopf die Strecke metrisch ([streckeVon]).
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
        if (d.puls.size < 2) Kasten { Leerzeile(stringResource(R.string.ks_v_keine_bahnen)) }
        return
    }
    val zeiten = bahnen.map { (it.second - it.first).toDouble() }
    val laenge = bahnen.firstNotNullOfOrNull { it.third } ?: 0.0
    val meter = bahnen.sumOf { it.third ?: laenge }
    val schwankung = Trainingsanalyse.schwankung(zeiten)
    val je100 = if (meter > 0) zeiten.sum() / meter * 100 else 0.0

    Abschnitt(stringResource(R.string.ks_v_bahnen_titel)) {
        Kennzahlen {
            Kennzahl(
                Formate.zahl(bahnen.size.toLong()),
                if (laenge > 0) stringResource(R.string.ks_v_a_m, Formate.zahl(laenge)) else null,
                stringResource(R.string.ks_v_bahnen),
            )
            if (meter > 0) Kennzahl(Formate.zahl(meter), "m", stringResource(R.string.ks_t_strecke))
            if (je100 > 0) Kennzahl(Formate.minutenSekunden(je100.roundToLong()), "min", stringResource(R.string.ks_v_je_100))
        }
        Fliesstext(
            stringResource(
                when {
                    schwankung <= 3 -> R.string.ks_v_sehr_gleich
                    schwankung <= 8 -> R.string.ks_v_gleich
                    else -> R.string.ks_v_ungleich
                },
                Formate.zahl(schwankung),
            ),
        )
        if (zeiten.size >= 2) {
            // Jede Bahn ein Punkt, gefaerbt von der schnellsten (rot) zur langsamsten (blau).
            val schnellste = zeiten.min()
            val langsamste = zeiten.max()
            val dunkel = Farben.dunkel()
            Verlauf(
                zeiten.mapIndexed { i, z -> Verlaufspunkt(i + 1.0, z) },
                ton, stringResource(R.string.ks_v_s_je_bahn),
                farbeJeWert = { z -> Farben.tempo(((langsamste - z) / (langsamste - schnellste).coerceAtLeast(1.0)).toFloat()).tonIm(dunkel) },
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
    Abschnitt(stringResource(R.string.ks_v_spo2_titel)) {
        if (d.spo2.isEmpty()) {
            Unterzeile(stringResource(R.string.ks_v_spo2_keine))
            return@Abschnitt
        }
        val werte = d.spo2.map { it.second }
        Kennzahlen {
            Kennzahl(Formate.prozentTeile(werte.average().roundToInt()), stringResource(R.string.ks_v_spo2_schnitt))
            Kennzahl(Formate.prozentTeile(werte.min().roundToInt()), stringResource(R.string.ks_v_spo2_tiefster))
            Kennzahl(Formate.prozentTeile(werte.max().roundToInt()), stringResource(R.string.ks_v_spo2_hoechster))
        }
        if (d.spo2.size >= 2) {
            Verlauf(
                d.spo2.map { Verlaufspunkt(it.first.toDouble(), it.second) },
                ton, "%", flaeche = false, hoehe = 120.dp, zeitachse = true,
            )
        }
    }
}
