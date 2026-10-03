package coredevices.pebble.kiesel.ansicht

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Auswertung
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.Reihen
import coredevices.pebble.kiesel.Vergleichsfragen
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * "What makes a difference" auf dem Bildschirm. Welche Fragen es gibt, ihre
 * Zeitrichtung und Schwellen stehen in [Vergleichsfragen] - hier nur, wie sie
 * heissen und aussehen. Ein Kasten mit Kopf fuer die fertigen Vergleiche,
 * einer fuer alles, was noch Tage braucht; die Farbe ist die der Zielgroesse
 * ([farbeDerReihe]), das Urteil traegt nur der Unterschied.
 */

/**
 * Die Worte einer Frage: der Titel und die Namen ihrer Gruppen A und B.
 * [uhrzeit] setzt eine Uhrzeit in die Namen ("Asleep after 11:00 PM") -
 * fest "23:00" stand auch bei 12 Stunden da.
 */
private class Texte(val frage: Int, val nameA: Int, val nameB: Int, val uhrzeit: LocalTime? = null)

/** Spaet ab 23 Uhr - aus der Schwelle der Frage (Minuten ab 18 Uhr), damit Name und Rechnung nicht auseinanderlaufen. */
private val SPAET_UHRZEIT: LocalTime = LocalTime.of(18, 0).plusMinutes(Vergleichsfragen.SPAET_AB.toLong())

private fun texte(art: Vergleichsfragen.Art): Texte = when (art) {
    Vergleichsfragen.Art.HRV_KURZ -> Texte(R.string.ks_trv_hrv_kurz, R.string.ks_v_unter_7, R.string.ks_v_ab_7)
    // "Quieter day" hiess auf Health die Pulsbelastung - hier zaehlen nur Schritt-Aktivminuten, also sagt die Gruppe das.
    Vergleichsfragen.Art.RUHE_AKTIV -> Texte(R.string.ks_v_ruhe_aktiv, R.string.ks_v_aktiver_tag, R.string.ks_v_ruhiger_tag)
    Vergleichsfragen.Art.RUHE_SPAET -> Texte(R.string.ks_v_ruhe_spaet, R.string.ks_trv_nach, R.string.ks_trv_vor, SPAET_UHRZEIT)
    Vergleichsfragen.Art.HRV_SCHRITTE -> Texte(R.string.ks_v_hrv_schritte, R.string.ks_v_viele_schritte, R.string.ks_v_weniger_schritte)
    Vergleichsfragen.Art.RUHE_WOCHENENDE -> Texte(R.string.ks_v_ruhe_wochenende, R.string.ks_v_wochenende, R.string.ks_v_unter_woche)
    Vergleichsfragen.Art.TIEF_KOFFEIN -> Texte(R.string.ks_v_tief_koffein, R.string.ks_v_viel_koffein, R.string.ks_v_wenig_koffein)
    Vergleichsfragen.Art.SCHLAF_AKTIV -> Texte(R.string.ks_v_schlaf_aktiv, R.string.ks_v_aktiver_tag, R.string.ks_v_ruhiger_tag)
    Vergleichsfragen.Art.SCHRITTE_KURZ -> Texte(R.string.ks_trv_schritte_kurz, R.string.ks_v_unter_7, R.string.ks_v_ab_7)
    Vergleichsfragen.Art.KOFFEIN_KURZ -> Texte(R.string.ks_trv_koffein_kurz, R.string.ks_v_unter_7, R.string.ks_v_ab_7)
}

/** Der Name einer Gruppe, mit der Uhrzeit im Format des Telefons, wo die Frage eine hat. */
@Composable
private fun Texte.name(id: Int): String = uhrzeit?.let { stringResource(id, Formate.uhrzeit(LocalContext.current, it)) } ?: stringResource(id)

/** Die Einheit der Zielgroesse neben der Zahl; Schritte haben keine, Dauern bringt [Formate.dauerTeile] mit. */
private fun einheit(ziel: String): String? = when (ziel) {
    "hrv" -> "ms"
    "ruhepuls" -> "bpm"
    "aktiv" -> "min"
    "koffein_mg" -> "mg"
    else -> null
}

private fun dauer(ziel: String) = ziel == "schlaf" || ziel == "tief"

/** Der Wert, wie er auf der Karte steht: "46 ms", "7:12 h", "45 min", "8,200". */
private fun angabe(ziel: String, w: Double): Formate.Angabe =
    if (dauer(ziel)) Formate.dauerTeile(w.roundToLong()) else Formate.Angabe(Formate.zahl(w, 0), einheit(ziel))

/** Die Zahl an der Achse des Punktbilds, ohne Einheit - Dauern durchgehend als "0:45", sonst wechselte die Achse die Form. */
private fun achsZahl(ziel: String, w: Double): String =
    if (dauer(ziel)) Formate.stundenMinuten(w.roundToInt()) else Formate.zahl(w, 0)

/** Der Unterschied mit Vorzeichen: "−9 ms", "+2 bpm", "−23 min", "+1,200". */
private fun unterschied(ziel: String, u: Double): Formate.Angabe =
    if (dauer(ziel)) Formate.dauerUnterschied(u) else Formate.Angabe(Formate.vorzeichen(u), einheit(ziel))

private class Ergebnis(val frage: Vergleichsfragen.Frage, val v: Auswertung.Gruppenvergleich) {
    val texte = texte(frage.art)
}

/**
 * Die Vergleiche einer Trendseite. [ausstehend] sind weitere Karten, die
 * noch Tage brauchen (Name und ab wann) - sie stehen mit den wartenden
 * Vergleichen in einem Kasten.
 */
@Composable
internal fun Vergleiche(d: Reihen, gruppe: String, heute: LocalDate, mitKoffein: Boolean, ausstehend: List<Pair<String, String>> = emptyList()) {
    val ergebnisse = remember(d, gruppe, heute, mitKoffein) {
        Vergleichsfragen.rechne(d, gruppe, heute, mitKoffein).map { (f, v) -> Ergebnis(f, v) }
    }
    val fertig = ergebnisse.filter { it.v.genug }
    // Wartend ist, was schon Tage in beiden Gruppen hat - ganz ohne Daten waere es nur Laerm.
    val wartend = ergebnisse.filter { !it.v.genug && (it.v.a.isNotEmpty() || it.v.b.isNotEmpty()) }
    var offen by remember { mutableStateOf<Ergebnis?>(null) }

    if (fertig.isNotEmpty()) {
        Kasten {
            Kopf(stringResource(R.string.ks_v_ueberschrift), stringResource(R.string.ks_v_zeitraum))
            fertig.forEachIndexed { i, e ->
                if (i > 0) Trenner(INNEN)
                Vergleichseintrag(e) { offen = e }
            }
        }
    }
    val zeilen = wartend.map { e ->
        val n = minOf(e.v.a.size, e.v.b.size)
        stringResource(e.texte.frage) to
            stringResource(if (e.frage.naechte) R.string.ks_v_von_naechte else R.string.ks_v_von_tage, n, Auswertung.VERGLEICH_MINDESTENS)
    } + ausstehend
    if (zeilen.isNotEmpty()) {
        // Die Zaehler ("3 of 5 nights") gelten fuer 30 Tage. Steht kein fertiger Vergleich mit
        // diesem Fenster darueber, nennt der Kopf es selbst - sonst fehlte es ganz.
        val fenster = if (fertig.isEmpty() && wartend.isNotEmpty()) stringResource(R.string.ks_v_zeitraum) else null
        Abschnitt(stringResource(R.string.ks_v_braucht_mehr), zusatz = fenster) {
            // Eine Spalte fuer den Stand, damit "3 of 5 nights" und "from Oct 12" untereinander stehen.
            Column {
                zeilen.forEachIndexed { i, (name, stand) ->
                    if (i > 0) Trenner(0.dp)
                    Tabellenzeile(listOf(name, stand), listOf(null, 100.dp))
                }
            }
        }
    }
    offen?.let { e -> Vergleichsblatt(e) { offen = null } }
}

@Composable
private fun wirkungFarbe(e: Ergebnis): Color {
    if (!e.v.klar) return Farben.neutral()
    val besser = (e.v.unterschied > 0) == e.frage.mehrIstGut
    return if (besser) Farben.gut.ton() else Farben.warnung.ton()
}

/**
 * Ein fertiger Vergleich: die Frage als Zeile mit dem Unterschied rechts im
 * Urteil (ohne klaren Unterschied sagt die Unterzeile es), darunter die zwei
 * Gruppen. Das Chevron, weil das Blatt eine Ebene tiefer fuehrt; tippbar
 * ist der ganze Eintrag.
 */
@Composable
private fun Vergleichseintrag(e: Ergebnis, oeffne: () -> Unit) {
    val f = e.frage
    val w = e.texte
    val v = e.v
    // Die Balken im Ton, nicht in der Flaeche: dunkle Flaechen (Koffein, HRV, Puls) kamen blass auf
    // der dunklen Karte nur auf 1,5:1 und waeren als Gruppe B kaum zu sehen. Der Ton haelt 3:1 und
    // ist derselbe wie die Punkte im Blatt.
    val farbe = farbeDerReihe(f.ziel).ton()
    val wirkung = wirkungFarbe(e)
    Column(Modifier.fillMaxWidth().clickable(onClick = oeffne).padding(bottom = 14.dp)) {
        Zeile(
            stringResource(w.frage),
            unter = if (v.klar) null else stringResource(R.string.ks_v_kein_unterschied),
            minHoehe = 48.dp, chevron = true,
            rechts = if (v.klar) { { Text(zahlMitEinheit(unterschied(f.ziel, v.unterschied)), color = wirkung, maxLines = 1) } } else null,
        )
        // Die Balken beginnen knapp unter dem kleineren Wert - sonst saehen 54 und 56 bpm gleich aus.
        val unten = minOf(v.medianA, v.medianB) * 0.8
        val oben = maxOf(v.medianA, v.medianB).coerceAtLeast(unten + 1)
        Column(Modifier.padding(horizontal = INNEN), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Gruppenbalken(w.name(w.nameA), v.a.size, f.naechte, angabe(f.ziel, v.medianA), ((v.medianA - unten) / (oben - unten)).toFloat(), farbe)
            // Gruppe B blasser, deckend mit der Karte gemischt statt Alpha.
            Gruppenbalken(w.name(w.nameB), v.b.size, f.naechte, angabe(f.ziel, v.medianB), ((v.medianB - unten) / (oben - unten)).toFloat(), farbe.aufKarte(0.55f))
        }
    }
}

@Composable
private fun Gruppenbalken(name: String, n: Int, naechte: Boolean, wert: Formate.Angabe, anteil: Float, farbe: Color) {
    val spur = Farben.spur()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) { Unterzeile(name + " · " + stringResource(if (naechte) R.string.ks_v_n_naechte else R.string.ks_v_n_tage, n)) }
            Text(zahlMitEinheit(wert), maxLines = 1)
        }
        // Ein reiner Balken ohne Schrift - darum eine feste Hoehe.
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(spur)) {
            Box(Modifier.fillMaxWidth(anteil.coerceIn(0.04f, 1f)).height(10.dp).clip(RoundedCornerShape(5.dp)).background(farbe))
        }
    }
}

/** Der Vergleich geoeffnet: jede Nacht als Punkt in ihrer Gruppe, damit man der Zahl traut. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Vergleichsblatt(e: Ergebnis, onDismiss: () -> Unit) {
    val f = e.frage
    val w = e.texte
    val v = e.v
    val locale = Locale.getDefault()
    val nameA = w.name(w.nameA)
    val nameB = w.name(w.nameB)
    val a = angabe(f.ziel, v.medianA)
    val b = angabe(f.ziel, v.medianB)
    val anzahl = if (f.naechte) R.string.ks_v_n_naechte else R.string.ks_v_n_tage
    // Was das Punktbild zeigt, fuer TalkBack: je Gruppe Name, Anzahl und Median.
    val beschreibung = listOf(
        "$nameA · ${stringResource(anzahl, v.a.size)}: ${a.text}",
        "$nameB · ${stringResource(anzahl, v.b.size)}: ${b.text}",
    ).joinToString("; ")
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        // Ohne eigenen Seitenrand: Detailkopf, Kopf und Zeile bringen ihre 14 dp mit - doppelt eingerueckt stand das Blatt schief.
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Detailkopf(stringResource(w.frage))
            Column(Modifier.padding(horizontal = INNEN), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Wie ein Hauptwert, aber die Zahl im Urteil - Hauptwert kennt keine Zahlfarbe.
                Text(
                    if (v.klar) zahlMitEinheit(unterschied(f.ziel, v.unterschied), 40, 18)
                    else zahlMitEinheit(stringResource(R.string.ks_v_kein_unterschied), null, 40),
                    lineHeight = 46.sp, color = wirkungFarbe(e),
                )
                // Die Gruppennamen mitten im Satz klein - nur der erste Buchstabe, "PM" bleibt gross.
                Unterzeile(
                    stringResource(
                        R.string.ks_v_beide, a.text, nameA.replaceFirstChar { it.lowercase(locale) },
                        b.text, nameB.replaceFirstChar { it.lowercase(locale) },
                    )
                )
                Punktbild(v, f, nameA, nameB, farbeDerReihe(f.ziel).ton(), beschreibung)
            }
            Kopf(stringResource(R.string.ks_v_wie_sicher))
            val streuung = angabe(f.ziel, v.streuung)
            Zeile(stringResource(R.string.ks_v_streuung), null, plusMinus(streuung.zahl), streuung.einheit, minHoehe = 48.dp)
            Trenner(INNEN)
            Zeile(
                stringResource(R.string.ks_v_unterschied), null,
                stringResource(if (v.klar) R.string.ks_v_klar else R.string.ks_v_unklar), null, minHoehe = 48.dp,
            )
        }
    }
}

/**
 * Zwei Reihen Punkte, A oben, B unten, der Median gestrichelt wie jeder
 * Mittel- und Bezugswert in den Bildern (durchgezogen ist das Ziel), darunter die
 * Achse. Die Abstaende richten sich nach der gemessenen Schrift: bei grosser
 * Schrift liefen die Namen sonst in die Punkte und die Achsenzahlen aus dem Bild.
 */
@Composable
private fun Punktbild(v: Auswertung.Gruppenvergleich, f: Vergleichsfragen.Frage, nameA: String, nameB: String, farbe: Color, beschreibung: String) {
    val messer = rememberTextMeasurer()
    val stil = MaterialTheme.typography.labelSmall.copy(color = Farben.neutral())
    val strich = MaterialTheme.colorScheme.onSurface
    val linie = Farben.raster()
    // Drei Zeilen Schrift: zwei Namen und die Achse.
    Canvas(Modifier.fillMaxWidth().height(hoeheMitSchrift(152.dp, 3)).bildBeschreibung(beschreibung)) {
        val zeile = messer.measure("0", stil).size.height.toFloat()
        val rechts = size.width
        val alle = v.a + v.b
        val lo = alle.min()
        val hi = alle.max().coerceAtLeast(lo + 1)
        val rand = (hi - lo) * 0.08
        fun x(w: Double) = (rechts * ((w - lo + rand) / (hi - lo + 2 * rand))).toFloat()
        val r = 5.dp.toPx()
        // Der Median ragt 16 dp ueber und unter die Reihe; der Name steht 2 dp darueber.
        val halbeReihe = 16.dp.toPx()
        val yA = zeile + 2.dp.toPx() + halbeReihe
        val yB = yA + halbeReihe + 14.dp.toPx() + zeile + 2.dp.toPx() + halbeReihe
        listOf(Triple(v.a, v.medianA, yA) to nameA, Triple(v.b, v.medianB, yB) to nameB).forEach { (reihe, name) ->
            val (werte, median, y) = reihe
            drawText(messer.measure(name, stil), topLeft = Offset(0f, y - halbeReihe - 2.dp.toPx() - zeile))
            // Leicht gestreut statt uebereinander: gleiche Werte sollen sichtbar mehrere sein.
            werte.forEachIndexed { i, w -> drawCircle(farbe, r, Offset(x(w), y + ((i * 7) % 13 - 6).dp.toPx())) }
            drawLine(
                strich, Offset(x(median), y - halbeReihe), Offset(x(median), y + halbeReihe), 2.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
            )
        }
        val achse = yB + halbeReihe + 12.dp.toPx()
        drawLine(linie, Offset(0f, achse), Offset(rechts, achse), 1.dp.toPx())
        listOf(lo, (lo + hi) / 2, hi).forEach { w ->
            val m = messer.measure(achsZahl(f.ziel, w), stil)
            drawText(m, topLeft = Offset((x(w) - m.size.width / 2f).coerceIn(0f, maxOf(0f, size.width - m.size.width)), achse + 6.dp.toPx()))
        }
    }
}
