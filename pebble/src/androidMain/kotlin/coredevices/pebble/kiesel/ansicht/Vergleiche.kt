package coredevices.pebble.kiesel.ansicht

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Auswertung
import coredevices.pebble.kiesel.Reihen
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * "What makes a difference": zwei Gruppen von Tagen gegeneinander, statt der
 * frueheren Korrelationen ("Links"). Jede Frage hat ihre Zeitrichtung - der
 * Ruhepuls einer Nacht kann nur vom Tag DAVOR beeinflusst sein - und keine
 * vergleicht eine Groesse mit sich selbst (Tiefschlaf und HRV entstehen aus
 * derselben Rechnung).
 */

/** Eine Frage: was verglichen wird ([ziel]) und wie die Tage in zwei Gruppen fallen. */
private class Frage(
    val gruppen: Set<String>,
    val frage: Int,
    val nameA: Int,
    val nameB: Int,
    val ziel: String,
    /** Ist mehr von [ziel] gut? Entscheidet die Farbe der Wirkung. */
    val mehrIstGut: Boolean,
    /** Naechte statt Tage im Text. */
    val naechte: Boolean,
    val mitKoffein: Boolean = false,
    /** Fuer den Tag des Zielwerts: Gruppe A (true), B (false) oder keine (null). */
    val zuordnung: (Reihen) -> (LocalDate) -> Boolean?,
)

private fun wert(d: Reihen, spalte: String): Map<LocalDate, Double> = d[spalte].orEmpty().toMap()

/** Kurz ist unter sieben Stunden; spaet ist nach 23 Uhr (300 min ab 18 Uhr). */
private const val KURZ_MIN = 7 * 60
private const val SPAET_AB = 300.0
private const val AKTIV_MIN = 30.0
private const val SCHRITTE_VIEL = 10_000.0
private const val KOFFEIN_VIEL = 200.0

private fun wochenende(t: LocalDate) = t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY

private val FRAGEN = listOf(
    // Dieselbe Nacht: kurz geschlafen, und die HRV dieser Nacht.
    Frage(setOf("herz", "schlaf"), R.string.ks_v_hrv_kurz, R.string.ks_v_unter_7, R.string.ks_v_ab_7, "hrv", true, true) { d ->
        val s = wert(d, "schlaf"); { t -> s[t]?.let { it < KURZ_MIN } }
    },
    // Der Tag davor bewegt, und der Ruhepuls der Nacht danach.
    Frage(setOf("herz", "bewegung"), R.string.ks_v_ruhe_aktiv, R.string.ks_v_aktiver_tag, R.string.ks_v_ruhiger_tag, "ruhepuls", false, true) { d ->
        val a = wert(d, "aktiv"); { t -> a[t.minusDays(1)]?.let { it >= AKTIV_MIN } }
    },
    Frage(setOf("herz", "schlaf"), R.string.ks_v_ruhe_spaet, R.string.ks_v_nach_23, R.string.ks_v_vor_23, "ruhepuls", false, true) { d ->
        val e = wert(d, "einschlafen"); { t -> e[t]?.let { it > SPAET_AB } }
    },
    Frage(setOf("herz", "bewegung"), R.string.ks_v_hrv_schritte, R.string.ks_v_viele_schritte, R.string.ks_v_weniger_schritte, "hrv", true, true) { d ->
        val s = wert(d, "schritte"); { t -> s[t.minusDays(1)]?.let { it >= SCHRITTE_VIEL } }
    },
    Frage(setOf("herz"), R.string.ks_v_ruhe_wochenende, R.string.ks_v_wochenende, R.string.ks_v_unter_woche, "ruhepuls", false, true) { _ ->
        { t -> wochenende(t) }
    },
    Frage(setOf("schlaf", "ernaehrung"), R.string.ks_v_tief_koffein, R.string.ks_v_viel_koffein, R.string.ks_v_wenig_koffein, "tief", true, true, mitKoffein = true) { d ->
        val k = wert(d, "koffein_mg"); { t -> k[t.minusDays(1)]?.let { it >= KOFFEIN_VIEL } }
    },
    Frage(setOf("schlaf", "bewegung"), R.string.ks_v_schlaf_aktiv, R.string.ks_v_aktiver_tag, R.string.ks_v_ruhiger_tag, "schlaf", true, true) { d ->
        val a = wert(d, "aktiv"); { t -> a[t.minusDays(1)]?.let { it >= AKTIV_MIN } }
    },
    // Ein Tag nach kurzer Nacht: wie viel man sich dann bewegt.
    Frage(setOf("bewegung"), R.string.ks_v_schritte_kurz, R.string.ks_v_unter_7, R.string.ks_v_ab_7, "schritte", true, false) { d ->
        val s = wert(d, "schlaf"); { t -> s[t]?.let { it < KURZ_MIN } }
    },
    Frage(setOf("ernaehrung"), R.string.ks_v_koffein_kurz, R.string.ks_v_unter_7, R.string.ks_v_ab_7, "koffein_mg", false, false, mitKoffein = true) { d ->
        val s = wert(d, "schlaf"); { t -> s[t]?.let { it < KURZ_MIN } }
    },
)

private val NACHTZIELE = setOf("schlaf", "tief", "ruhepuls", "hrv")

/** Bis wohin gezaehlt wird: Nachtwerte bis heute, Tageswerte nur bis gestern. */
private fun grenze(ziel: String, heute: LocalDate) = if (ziel in NACHTZIELE) heute.plusDays(1) else heute

private fun einheit(ziel: String) = when (ziel) {
    "hrv" -> "ms"
    "ruhepuls" -> "bpm"
    "schlaf", "tief" -> "h"
    "aktiv" -> "min"
    "koffein_mg" -> "mg"
    else -> ""
}

/** Der Wert, wie er auf der Karte steht: "46", "7:12", "8,200". */
private fun zahl(ziel: String, w: Double): String = when (ziel) {
    "schlaf", "tief" -> stundenMinuten(w.roundToInt())
    "schritte" -> String.format(Locale.getDefault(), "%,.0f", w)
    else -> "${w.roundToInt()}"
}

/** Der Unterschied mit Vorzeichen: "−9 ms", "+2 bpm", "−23 min". */
private fun unterschied(ziel: String, u: Double): String {
    val zeichen = if (u < 0) "−" else "+"
    val b = abs(u)
    return when (ziel) {
        "schlaf", "tief" -> if (b < 60) "$zeichen${b.roundToInt()} min" else "$zeichen${stundenMinuten(b.roundToInt())} h"
        "schritte" -> zeichen + String.format(Locale.getDefault(), "%,.0f", b)
        else -> "$zeichen${b.roundToInt()} ${einheit(ziel)}"
    }
}

/** Die Farbe der Groesse, wie in den Trends. */
private fun ton(ziel: String): Color = when (ziel) {
    "hrv" -> Color(0xFFC98BD6)
    "ruhepuls" -> Color(0xFFE0604F)
    "schlaf", "tief" -> Color(0xFF7FA7D9)
    "koffein_mg" -> Color(0xFFD09A62)
    else -> Color(0xFF7DBB8A)
}

private class Ergebnis(val frage: Frage, val v: Auswertung.Gruppenvergleich)

@Composable
internal fun Vergleiche(d: Reihen, gruppe: String, heute: LocalDate, mitKoffein: Boolean) {
    val fragen = FRAGEN.filter { gruppe in it.gruppen && (mitKoffein || !it.mitKoffein) }
    if (fragen.isEmpty()) return
    val ergebnisse = remember(d, gruppe, heute, mitKoffein) {
        fragen.map { f -> Ergebnis(f, Auswertung.vergleich(d[f.ziel].orEmpty(), grenze(f.ziel, heute), f.zuordnung(d))) }
    }
    val fertig = ergebnisse.filter { it.v.genug }
    // Wartend ist, was schon Tage in beiden Gruppen hat - ganz ohne Daten waere es nur Laerm.
    val wartend = ergebnisse.filter { !it.v.genug && (it.v.a.isNotEmpty() || it.v.b.isNotEmpty()) }
    if (fertig.isEmpty() && wartend.isEmpty()) return
    var offen by remember { mutableStateOf<Ergebnis?>(null) }

    Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(stringResource(R.string.ks_v_ueberschrift), fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
        Text(" · " + stringResource(R.string.ks_v_zeitraum), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    fertig.forEach { e -> Vergleichskarte(e) { offen = e } }
    if (wartend.isNotEmpty()) {
        Kasten {
            Kopf(stringResource(R.string.ks_v_braucht_mehr))
            wartend.forEachIndexed { i, e ->
                if (i > 0) Trenner(14.dp)
                val n = minOf(e.v.a.size, e.v.b.size)
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(e.frage.frage), Modifier.weight(1f), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        stringResource(if (e.frage.naechte) R.string.ks_v_von_naechte else R.string.ks_v_von_tage, n, Auswertung.VERGLEICH_MINDESTENS),
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
    offen?.let { e -> Vergleichsblatt(e) { offen = null } }
}

@Composable
private fun wirkungFarbe(e: Ergebnis): Color {
    if (!e.v.klar) return MaterialTheme.colorScheme.onSurfaceVariant
    val besser = (e.v.unterschied > 0) == e.frage.mehrIstGut
    return if (besser) MaterialTheme.colorScheme.primary else toene().warnung
}

@Composable
private fun Vergleichskarte(e: Ergebnis, oeffne: () -> Unit) {
    val f = e.frage
    val v = e.v
    Kasten {
        Column(Modifier.fillMaxWidth().clickable(onClick = oeffne).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(stringResource(f.frage), Modifier.weight(1f), fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (v.klar) unterschied(f.ziel, v.unterschied) else stringResource(R.string.ks_v_kein_unterschied),
                    Modifier.padding(start = 12.dp), fontSize = if (v.klar) 20.sp else 15.sp, fontWeight = FontWeight.SemiBold, color = wirkungFarbe(e),
                )
            }
            // Die Balken beginnen knapp unter dem kleineren Wert - sonst saehen 54 und 56 bpm gleich aus.
            val unten = minOf(v.medianA, v.medianB) * 0.8
            val oben = maxOf(v.medianA, v.medianB).coerceAtLeast(unten + 1)
            Gruppenbalken(stringResource(f.nameA), v.a.size, f.naechte, zahl(f.ziel, v.medianA), einheit(f.ziel), ((v.medianA - unten) / (oben - unten)).toFloat(), ton(f.ziel))
            Gruppenbalken(stringResource(f.nameB), v.b.size, f.naechte, zahl(f.ziel, v.medianB), einheit(f.ziel), ((v.medianB - unten) / (oben - unten)).toFloat(), ton(f.ziel).copy(alpha = 0.55f))
        }
    }
}

@Composable
private fun Gruppenbalken(name: String, n: Int, naechte: Boolean, zahl: String, einheit: String, anteil: Float, farbe: Color) {
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                name + " · " + stringResource(if (naechte) R.string.ks_v_n_naechte else R.string.ks_v_n_tage, n),
                Modifier.weight(1f), fontSize = 13.sp, color = grau,
            )
            Text(zahlMitEinheit(zahl, einheit.ifEmpty { null }, 18, 12), maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(anteil.coerceIn(0.04f, 1f)).height(10.dp).clip(RoundedCornerShape(5.dp)).background(farbe))
        }
    }
}

/** Der Vergleich geoeffnet: jede Nacht als Punkt in ihrer Gruppe, damit man der Zahl traut. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Vergleichsblatt(e: Ergebnis, onDismiss: () -> Unit) {
    val f = e.frage
    val v = e.v
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(f.frage), fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text(
                if (v.klar) unterschied(f.ziel, v.unterschied) else stringResource(R.string.ks_v_kein_unterschied),
                fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.SemiBold, color = wirkungFarbe(e),
            )
            Text(
                stringResource(R.string.ks_v_beide, zahl(f.ziel, v.medianA) + " " + einheit(f.ziel), stringResource(f.nameA).lowercase(Locale.getDefault()),
                    zahl(f.ziel, v.medianB) + " " + einheit(f.ziel), stringResource(f.nameB).lowercase(Locale.getDefault())),
                fontSize = 14.sp, color = grau,
            )
            Spacer(Modifier.height(8.dp))
            Punktbild(v, f, ton(f.ziel))
            Spacer(Modifier.height(4.dp))
            Kopf(stringResource(R.string.ks_v_wie_sicher))
            Zeile(stringResource(R.string.ks_v_streuung), null, "± " + zahl(f.ziel, v.streuung), einheit(f.ziel).ifEmpty { null }, minHoehe = 48.dp)
            Trenner(14.dp)
            Zeile(
                stringResource(R.string.ks_v_unterschied), null,
                stringResource(if (v.klar) R.string.ks_v_klar else R.string.ks_v_unklar), null, minHoehe = 48.dp,
            )
        }
    }
}

/** Zwei Reihen Punkte, A oben, B unten, der Median als Strich, darunter die Achse. */
@Composable
private fun Punktbild(v: Auswertung.Gruppenvergleich, f: Frage, farbe: Color) {
    val messer = rememberTextMeasurer()
    val stil = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val strich = MaterialTheme.colorScheme.onSurface
    val linie = MaterialTheme.colorScheme.outlineVariant
    val nameA = stringResource(f.nameA)
    val nameB = stringResource(f.nameB)
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        val links = 0f
        val rechts = size.width
        val alle = v.a + v.b
        val lo = alle.min()
        val hi = alle.max().coerceAtLeast(lo + 1)
        val rand = (hi - lo) * 0.08
        fun x(w: Double) = (links + (rechts - links) * ((w - lo + rand) / (hi - lo + 2 * rand))).toFloat()
        val r = 5.dp.toPx()
        listOf(Triple(v.a, v.medianA, 34.dp.toPx()) to nameA, Triple(v.b, v.medianB, 96.dp.toPx()) to nameB).forEach { (reihe, name) ->
            val (werte, median, y) = reihe
            drawText(messer.measure(name, stil), topLeft = Offset(0f, y - 30.dp.toPx()))
            // Leicht gestreut statt uebereinander: gleiche Werte sollen sichtbar mehrere sein.
            werte.forEachIndexed { i, w -> drawCircle(farbe.copy(alpha = 0.8f), r, Offset(x(w), y + ((i * 7) % 13 - 6).dp.toPx())) }
            drawLine(strich, Offset(x(median), y - 16.dp.toPx()), Offset(x(median), y + 16.dp.toPx()), 2.5.dp.toPx())
        }
        val achse = 140.dp.toPx()
        drawLine(linie, Offset(links, achse), Offset(rechts, achse), 1.dp.toPx())
        listOf(lo, (lo + hi) / 2, hi).forEach { w ->
            val m = messer.measure(zahl(f.ziel, w), stil)
            drawText(m, topLeft = Offset((x(w) - m.size.width / 2f).coerceIn(0f, size.width - m.size.width), achse + 6.dp.toPx()))
        }
    }
}
