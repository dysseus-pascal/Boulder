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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.Gesundheitsdaten
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.Fenster
import coredevices.pebble.kiesel.naechteSchnitt
import coredevices.pebble.kiesel.phasenAnteile
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
 * Eine Nacht im Einzelnen, nach Konzept F: dieselben Daten wie im Zeitstrahl,
 * aber gross - die Dauer, die Phasen mit Anteil und Zeit, der Verlauf durch
 * die Nacht, der Koerper im Schlaf und die Woche davor. Geblaettert wird hier,
 * nicht ueber die Navigation - sonst stuende jede Nacht einzeln im
 * Zurueck-Stapel.
 */
@Composable
internal fun NachtAnsicht(start: Long, setzeTitel: (String) -> Unit, oeffneTrend: () -> Unit) {
    val context = LocalContext.current
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
    val n = d.nacht
    val zone = ZoneId.systemDefault()
    // Der Tag des Aufwachens wie ueberall, mit Wochentag wie jeder Seitenkopf ("Wed, Sep 30" auf
    // Health, im Trainingskopf) - und wie die Saeulen der Naechte unten, die nach Wochentagen heissen.
    val titel = Formate.datumMitWochentag(n.aufwachen.atZone(zone).toLocalDate())
    LaunchedEffect(titel) { setzeTitel(titel) }

    Seite {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            // Die Pfeile aus Boulders Strichzeichen, nicht als Textglyphen "‹ ›" (Symbolregel im Baukasten).
            Blaetterknopf(stringResource(R.string.ks_h_frueher), zurueck = true, onClick = d.vorige?.let { v -> { beginn = v } })
            // Derselbe Link wie in jedem Kopf ("Trends ›"), kein eigener Knopf.
            Weiterlink(stringResource(R.string.ks_h_trends), oeffneTrend)
            Blaetterknopf(stringResource(R.string.ks_h_spaeter), zurueck = false, onClick = d.naechste?.let { n -> { beginn = n } })
        }

        // DIE DAUER GROSS: wer die Nacht oeffnet, will zuerst wissen, wie lang sie war.
        Kasten {
            Column(Modifier.padding(INNEN), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val urteil = Gesundheitsrechnung.schlafurteil(n.schlafMinuten)
                // Das Urteil als Unterzeile des Hauptwerts in einem Ton, der Schrift traegt -
                // das Pastell der Phase, das es vorher trug, kam hell auf 2,3:1.
                Hauptwert(
                    Formate.stundenMinuten(n.schlafMinuten), "h", groesse = 52, einheitGroesse = 22,
                    unter = stringResource(
                        when (urteil) {
                            Schlafurteil.AUSGERUHT -> R.string.ks_h_urteil_ausgeruht
                            Schlafurteil.KURZ -> R.string.ks_h_urteil_kurz
                            Schlafurteil.SEHR_KURZ -> R.string.ks_h_urteil_sehr_kurz
                        }
                    ),
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

        if (n.mitPhasen) Phasenkarte(n.phasen)

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
 * Die Phasen gross: oben der Balken der Anteile, darunter je Phase Name,
 * Anteil und Dauer. Die Spalten haben eine Mindest-, keine feste Breite -
 * mit grosser Schrift wachsen sie, statt die Zahl abzuschneiden.
 */
@Composable
private fun Phasenkarte(phasen: IntArray) {
    val imBett = phasen.sum()
    // In der Reihenfolge der Bahnen des Hypnogramms darunter, wach oben - Zeilen und Bahnen liest man gleich herum.
    val reihe = PHASEN_VON_OBEN
    val namen = reihe.map { (_, name) -> stringResource(name) }
    // Die getestete Rechnung: alle fuenf Anteile ergeben 100, einzeln gerundet hatten drei gleich lange Phasen
    // 99 %. Schlaf ohne Phase zaehlt zur Zeit im Bett, hat aber keine Zeile - mit ihm bleiben die Zeilen unter 100.
    val prozent = Gesundheitsrechnung.phasenAnteile(phasen)
    val anteile = reihe.map { (p, _) -> Formate.prozent(prozent[p]) }
    Kasten {
        Kopf(stringResource(R.string.ks_n_phasen), stringResource(R.string.ks_n_im_bett, Formate.dauer(imBett.toLong())))
        Column(Modifier.padding(start = INNEN, end = INNEN, bottom = 8.dp)) {
            Phasenbalken(
                reihe.map { (p, _) -> phasen[p] to Farben.phasen[p] },
                namen.zip(anteile).joinToString(", ") { (name, anteil) -> "$name $anteil" },
            )
            Spacer(Modifier.height(8.dp))
            reihe.forEachIndexed { i, (p, _) ->
                if (i > 0) Trenner(0.dp)
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(Farben.phasen[p], CircleShape))
                    Text(namen[i], Modifier.weight(1f).padding(start = 12.dp), fontSize = 16.sp)
                    Text(anteile[i], Modifier.widthIn(min = 48.dp), fontSize = 13.sp, color = Farben.neutral(), textAlign = TextAlign.End, maxLines = 1)
                    // Die Dauer wie ueberall ("28 min", "3:22 h"): "0:28 h" gab es nur hier. Rechtsbuendig bleibt die Spalte ruhig.
                    Text(zahlMitEinheit(Formate.dauerTeile(phasen[p].toLong()), 22, 13), Modifier.widthIn(min = 84.dp), textAlign = TextAlign.End, maxLines = 1)
                }
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

/** Die Phasen als breiter Balken - Anteile, gross genug zum Lesen. [satz]: dieselben Anteile fuer TalkBack. */
@Composable
private fun Phasenbalken(teile: List<Pair<Int, Color>>, satz: String) {
    val summe = teile.sumOf { it.first }.coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(20.dp).bildBeschreibung(satz)) {
        val luecke = 2.dp.toPx()
        val sichtbar = teile.filter { it.first > 0 }
        val platz = size.width - luecke * (sichtbar.size - 1).coerceAtLeast(0)
        var x = 0f
        sichtbar.forEach { (wert, farbe) ->
            val breite = platz * wert / summe
            drawRoundRect(farbe, Offset(x, 0f), Size(breite, size.height), CornerRadius(5.dp.toPx()))
            x += breite + luecke
        }
    }
}
