package coredevices.pebble.kiesel.ansicht

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.Gesundheitsdaten
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.Gesundheitsziele
import coredevices.pebble.kiesel.Fenster
import coredevices.pebble.kiesel.naechteSchnitt
import coredevices.pebble.kiesel.Schlafurteil
import coredevices.pebble.kiesel.schlafurteil
import io.rebble.libpebblecommon.connection.HealthDataApi
import org.koin.compose.koinInject
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Eine geladene Nacht; [detail] null, wenn es sie nicht (mehr) gibt. */
private class Nachtstand(val detail: Gesundheitsdaten.NachtDetail?)

/**
 * Eine Nacht im Einzelnen, nach Konzept F: dieselben Daten wie im Tagesband,
 * aber gross - die Dauer, der Verlauf durch die Nacht mit der Zeit jeder
 * Phase darunter, der Koerper im Schlaf und die Woche davor. Geblaettert wird
 * hier, nicht ueber die Navigation - sonst stuende jede Nacht einzeln im
 * Zurueck-Stapel. Geladen wird hier, gezeigt in [NachtInhalt].
 */
@Composable
internal fun NachtAnsicht(start: Long, setzeTitel: (String) -> Unit, oeffneTrend: () -> Unit) {
    val api = koinInject<HealthDataApi>()
    // Saveable: zurueck aus den Trends stand sonst wieder die Nacht vom Einstieg da, nicht die geblaetterte.
    var beginn by rememberSaveable { mutableLongStateOf(start) }
    // Beim Blaettern bleibt die alte Nacht stehen, bis die neue da ist.
    val geladen = rememberStand(Gesundheitsdaten.zuletztNacht?.takeIf { it.nacht.beginnUtc == beginn }?.let { Nachtstand(it) }, beginn) {
        Nachtstand(Gesundheitsdaten.nacht(api, beginn))
    }
    if (geladen == null) {
        Ladekreis()
        return
    }
    val d = geladen.detail
    if (d == null) {
        Leerseite(stringResource(R.string.ks_h_keine_nacht))
        return
    }
    // Der Tag des Aufwachens wie ueberall, mit Wochentag wie jeder Seitenkopf ("Wed, Sep 30" auf
    // Health, im Trainingskopf) - und wie die Saeulen der Naechte unten, die nach Wochentagen heissen.
    val titel = Formate.datumMitWochentag(d.nacht.aufwachen.atZone(ZoneId.systemDefault()).toLocalDate())
    LaunchedEffect(titel) { setzeTitel(titel) }
    NachtInhalt(d, { beginn = it }, oeffneTrend)
}

/**
 * Die geladene Nacht [d], ohne eigenes Laden - so laesst sie sich mit
 * Beispieldaten zeichnen. [blaettern] bekommt den Beginn der Nacht davor
 * oder danach.
 */
@Composable
internal fun NachtInhalt(d: Gesundheitsdaten.NachtDetail, blaettern: (Long) -> Unit, oeffneTrend: () -> Unit) {
    val context = LocalContext.current
    val n = d.nacht
    val zone = ZoneId.systemDefault()

    Seite {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            // Die Pfeile aus Boulders Strichzeichen, nicht als Textglyphen "‹ ›" (Symbolregel im Baukasten).
            Blaetterknopf(stringResource(R.string.ks_h_frueher), zurueck = true, onClick = d.vorige?.let { v -> { blaettern(v) } })
            // Derselbe Link wie in jedem Kopf ("Trends ›"), kein eigener Knopf.
            Weiterlink(stringResource(R.string.ks_h_trends), oeffneTrend)
            Blaetterknopf(stringResource(R.string.ks_h_spaeter), zurueck = false, onClick = d.naechste?.let { v -> { blaettern(v) } })
        }

        // DIE DAUER GROSS: wer die Nacht oeffnet, will zuerst wissen, wie lang sie war.
        Kasten {
            Column(Modifier.padding(INNEN), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val urteil = Gesundheitsrechnung.schlafurteil(n.schlafMinuten, Gesundheitsziele.ziele(LocalContext.current).schlafMin)
                val urteilText = stringResource(
                    when (urteil) {
                        Schlafurteil.AUSGERUHT -> R.string.ks_h_urteil_ausgeruht
                        Schlafurteil.KURZ -> R.string.ks_h_urteil_kurz
                        Schlafurteil.SEHR_KURZ -> R.string.ks_h_urteil_sehr_kurz
                    }
                )
                // Das Urteil als Unterzeile des Hauptwerts in einem Ton, der Schrift traegt -
                // das Pastell der Phase, das es vorher trug, kam hell auf 2,3:1.
                Hauptwert(
                    Formate.stundenMinuten(n.schlafMinuten), "h", groesse = 52, einheitGroesse = 22,
                    unter = listOfNotNull(urteilText, if (n.manuell) stringResource(R.string.ks_n_manuell) else null).joinToString(" · "),
                    unterFarbe = if (urteil == Schlafurteil.AUSGERUHT) Farben.schlaf.ton() else Farben.warnung.ton(),
                )
                Box(Modifier.padding(top = 6.dp)) {
                    Kennzahlen {
                        Kennzahl(Formate.uhrzeit(context, n.einschlafen), null, stringResource(R.string.ks_d_eingeschlafen))
                        Kennzahl(Formate.uhrzeit(context, n.aufwachen), null, stringResource(R.string.ks_d_aufgewacht))
                    }
                }
            }
        }

        // Geladen wird eine halbe Stunde vor und nach dem Schlaf; gezeigt nur die
        // Nacht selbst - Punkte davor zeichnete die Kurve sonst in die Achsenspalte.
        val von = n.spanne.first
        val bis = n.spanne.last
        val puls = d.puls.filter { it.first in von..bis }
        val spo2Punkte = d.spo2.filter { it.first in von..bis }
        if (n.abschnitte.isNotEmpty() || puls.isNotEmpty()) {
            Abschnitt(stringResource(R.string.ks_n_durch_die_nacht)) {
                Column {
                    // Mit derselben Achsspalte rechts wie die Kurven darunter: eine Uhrzeit steht in allen drei Bildern senkrecht uebereinander.
                    if (n.abschnitte.isNotEmpty()) Hypnogramm(n, hoehe = 150.dp, mitAchse = true)
                    if (puls.isNotEmpty()) {
                        val name = stringResource(R.string.ks_n_puls)
                        Spacer(Modifier.height(10.dp))
                        Unterzeile(name)
                        Tageskurve(puls, von, bis, Farben.puls.ton(), hoehe = 110.dp, beschreibung = kurvenSatz(name, puls, "bpm"))
                    }
                    if (spo2Punkte.isNotEmpty()) {
                        val name = stringResource(R.string.ks_h_spo2_n)
                        Spacer(Modifier.height(10.dp))
                        Unterzeile(name)
                        Tageskurve(spo2Punkte, von, bis, Farben.spo2.ton(), hoehe = 90.dp, trendFenster = 1800, beschreibung = kurvenSatz(name, spo2Punkte, "%"))
                    }
                    // Die Zeit jeder Phase unter den Bildern, abgesetzt durch eine Linie: wer die
                    // Bahnen sieht, fragt als Naechstes, wie lang jede war. Ohne Phasen gibt es nur
                    // die eine Bahn "leicht" - vier Zeilen voller Nullen sagten dann nichts.
                    if (n.mitPhasen) {
                        Spacer(Modifier.height(12.dp))
                        Trenner(0.dp)
                        Spacer(Modifier.height(12.dp))
                        Phasenzeile(n.phasen)
                    }
                }
            }
        } else if (!n.mitPhasen) {
            Kasten { Leerzeile(stringResource(R.string.ks_h_ohne_phasen)) }
        }

        // Der Koerper im Schlaf, je gegen den Schnitt der Naechte davor - wie auf der
        // Herzseite erst ab Fenster.LERNNAECHTE: ein Wert aus einer Nacht ist kein "avg".
        fun vergleich(werte: List<Int>) = Gesundheitsrechnung.naechteSchnitt(werte).let { (naechte, wert) -> wert?.takeIf { naechte >= Fenster.LERNNAECHTE }?.roundToInt() }
        val ruheSchnitt = vergleich(d.davor.map { it.ruhepuls })
        val hrvSchnitt = vergleich(d.davor.map { it.hrv })
        // Dieselben Punkte wie die Kurve, damit "lowest" einer ist, den man darin sieht.
        val spo2 = spo2Punkte.map { it.second }
        if (n.ruhepuls != null || n.hrv != null || spo2.isNotEmpty()) {
            Abschnitt(stringResource(R.string.ks_n_koerper)) {
                Kennzahlen {
                    n.ruhepuls?.let {
                        Koerperwert(Formate.zahl(it.toLong()), "bpm", stringResource(R.string.ks_h_ruhepuls), ruheSchnitt?.let { s -> stringResource(R.string.ks_n_schnitt, Formate.zahl(s.toLong())) })
                    }
                    n.hrv?.let {
                        Koerperwert(Formate.zahl(it.toLong()), "ms", stringResource(R.string.ks_h_hrv), hrvSchnitt?.let { s -> stringResource(R.string.ks_n_schnitt, Formate.zahl(s.toLong())) })
                    }
                    if (spo2.isNotEmpty()) {
                        val schnitt = Formate.prozentTeile(spo2.average().roundToInt())
                        Koerperwert(schnitt.zahl, schnitt.einheit, stringResource(R.string.ks_d_spo2), stringResource(R.string.ks_n_tiefster, Formate.prozent(spo2.min())))
                    }
                }
            }
        }

        // Die Woche davor als Saeulen, diese Nacht hervorgehoben. Jede Nacht gehoert zum
        // Tag, an dem man aufwacht (nachtTag) - wie im Zeitstrahl und in den Trends.
        val reihe = d.davor.map { it.schlafMinuten to Gesundheitsrechnung.nachtTag(it.beginnUtc, it.aufwachen, zone) } +
            (n.schlafMinuten to n.aufwachen.atZone(zone).toLocalDate())
        if (reihe.size >= 2) {
            val schnitt = stringResource(R.string.ks_n_schnitt_h, Formate.dauer(reihe.map { it.first }.average().roundToLong()))
            val titelWoche = stringResource(R.string.ks_n_sieben)
            val grau = Farben.neutral()
            val voll = Farben.schlaf.flaeche()
            val blass = voll.aufKarte(0.4f)
            val stil = MaterialTheme.typography.labelSmall
            // Fuer TalkBack die Woche in einem Satz; die Zahlen ueber den Saeulen hoerte man sonst ohne ihren Tag.
            val satz = listOf(titelWoche, schnitt).joinToString(", ") + ": " +
                reihe.joinToString(", ") { (minuten, tag) -> Formate.wochentagLang(tag.dayOfWeek) + " " + Formate.dauer(minuten.toLong()) }
            Abschnitt(titelWoche, schnitt) {
                Row(
                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = satz },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val spitze = maxOf(9 * 60, reihe.maxOf { it.first }).toFloat()
                    reihe.forEachIndexed { i, (minuten, tag) ->
                        val diese = i == reihe.lastIndex
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            // Darf ueber die Saeule in die Luecke ragen: "10:23" ist bei Schrift 1,3 so breit wie die Spalte und wurde beschnitten.
                            Text(
                                Formate.stundenMinuten(minuten), Modifier.wrapContentWidth(unbounded = true), style = stil,
                                fontWeight = FontWeight.Normal, color = grau, maxLines = 1, softWrap = false,
                            )
                            val farbe = if (diese) voll else blass
                            Canvas(Modifier.fillMaxWidth().height(70.dp)) {
                                val h = size.height * minuten / spitze
                                drawRoundRect(farbe, Offset(0f, size.height - h), Size(size.width, h), CornerRadius(4.dp.toPx()))
                            }
                            Text(
                                Formate.wochentagKurz(tag.dayOfWeek), style = stil,
                                // Normal statt des Medium von labelSmall: sonst hebt sich der hervorgehobene Tag kaum ab.
                                fontWeight = if (diese) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (diese) MaterialTheme.colorScheme.onSurface else grau,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Fuer TalkBack der Satz einer Kurve: ihr Name und die Spanne der Werte ("Heart rate, 47–62 bpm"). */
private fun kurvenSatz(name: String, punkte: List<Pair<Long, Int>>, einheit: String): String {
    val tief = Formate.zahl(punkte.minOf { it.second }.toLong())
    val hoch = Formate.Angabe(Formate.zahl(punkte.maxOf { it.second }.toLong()), einheit).text
    return "$name, $tief–$hoch"
}

/**
 * Die Phasen als eine Zeile mit vier gleich breiten Spalten - Farbpunkt,
 * Name, darunter die Dauer - in der Reihenfolge der Bahnen des Hypnogramms
 * darueber, wach links wie wach oben: so liest man Spalte und Bahn gleich
 * herum. Die Dauer wie ueberall ("15 min", "1:13 h"). Schlaf ohne Phase hat
 * keine Spalte, wie er auch keine eigene Bahn hat. Mit grosser Schrift
 * brechen Name und Dauer um, statt abgeschnitten zu werden.
 */
@Composable
private fun Phasenzeile(phasen: IntArray) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PHASEN_VON_OBEN.forEach { (p, name) ->
            // Eine Spalte ist fuer TalkBack ein Halt: "Awake, 15 min" statt Name und Zahl einzeln.
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).background(Farben.phasen[p], CircleShape))
                    Text(stringResource(name), fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(zahlMitEinheit(Formate.dauerTeile(phasen[p].toLong()), 18, 12), lineHeight = 24.sp)
            }
        }
    }
}

/** Ein Wert des Koerpers im Schlaf: die Kennzahl, darunter grau der Schnitt der Naechte davor. */
@Composable
private fun Koerperwert(zahl: String, einheit: String?, name: String, unter: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Kennzahl(zahl, einheit, name)
        if (unter != null) Unterzeile(unter)
    }
}
