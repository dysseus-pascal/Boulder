package coredevices.pebble.kiesel.ansicht

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Auswertung
import coredevices.pebble.kiesel.Reihen
import coredevices.pebble.kiesel.Trenddaten
import io.rebble.libpebblecommon.connection.HealthDataApi
import org.koin.compose.koinInject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Die Trends einer Gruppe - wie TrendTab in Kiesel-Helper: der typische
 * Wochentag, der Verlauf ueber acht Wochen, und die Zusammenhaenge, nach
 * denen man wirklich fragt. Eine Seite je Gruppe; wer auf den Schlaf tippt,
 * will den Schlaf sehen.
 */
@Composable
internal fun TrendAnsicht(gruppe: String, setzeTitel: (String) -> Unit) {
    val context = LocalContext.current
    val api = koinInject<HealthDataApi>()
    var reihen by remember { mutableStateOf<Reihen?>(null) }
    LaunchedEffect(Unit) { reihen = Trenddaten.lies(context, api) }
    val titel = stringResource(
        when (gruppe) {
            "schlaf" -> R.string.ks_tr_schlaf
            "herz" -> R.string.ks_tr_herz
            "ernaehrung" -> R.string.ks_tr_ernaehrung
            else -> R.string.ks_tr_bewegung
        }
    )
    LaunchedEffect(titel) { setzeTitel(titel) }
    val d = reihen
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val heute = LocalDate.now()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val spalten = when (gruppe) {
            "schlaf" -> listOf("schlaf", "tief")
            "herz" -> listOf("ruhepuls", "hrv")
            "ernaehrung" -> listOf("wasser", "supp_faellig", "koffein_mg")
            else -> listOf("schritte", "aktiv")
        }
        if (spalten.none { (d[it]?.count { t -> t.first < heute } ?: 0) >= 2 }) {
            Karte { Text(stringResource(R.string.ks_tr_zu_wenig), style = MaterialTheme.typography.bodyMedium) }
            return@Column
        }
        when (gruppe) {
            "schlaf" -> Schlaftrend(d, heute)
            "herz" -> {
                Einfach(d, "ruhepuls", stringResource(R.string.ks_h_ruhepuls), FARBE_HERZ_T, heute) { "${it.roundToInt()} bpm" }
                Einfach(d, "hrv", stringResource(R.string.ks_h_hrv), FARBE_HERZ_T, heute) { "${it.roundToInt()} ms" }
            }
            "ernaehrung" -> {
                Einfach(d, "wasser", stringResource(R.string.ks_h_wasser_n), FARBE_WASSER_T, heute) { "${it.roundToInt()} ml" }
                Praeparattrend(d, heute)
                Einfach(d, "koffein_mg", stringResource(R.string.ks_h_koffein_n), Color(0xFF8A5A2B), heute) { "${it.roundToInt()} mg" }
            }
            else -> {
                Einfach(d, "schritte", stringResource(R.string.ks_h_schritte), FARBE_BEWEGUNG_T, heute) { String.format(Locale.getDefault(), "%,.0f", it) }
                Einfach(d, "aktiv", stringResource(R.string.ks_h_aktiv), FARBE_BEWEGUNG_T, heute) { dauerText(it.roundToInt().toLong()) }
            }
        }
        Zusammenhaenge(d, gruppe, heute)
        val tage = d.values.flatten().map { it.first }.distinct()
        tage.minOrNull()?.let {
            Text(
                stringResource(R.string.ks_tr_fuss, tage.size, it.toString(), Auswertung.MINDESTENS),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

private val FARBE_BEWEGUNG_T = Color(0xFF4F8A3A)
private val FARBE_SCHLAF_T = Color(0xFF2F5597)
private val FARBE_HERZ_T = Color(0xFFC0392B)
private val FARBE_WASSER_T = Color(0xFF3A86C8)

private fun kurz(t: DayOfWeek) = t.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2)
private fun kw(montag: LocalDate) = montag.get(WeekFields.ISO.weekOfWeekBasedYear()).toString()

@Composable
private fun Satz(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium)

@Composable
private fun Leise(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Durchschnitt, staerkster und schwaechster Wochentag, Wochenende - die Saetze unter dem Wochenbild. */
@Composable
private fun Wochensaetze(bild: Auswertung.Bild, reihe: List<Pair<LocalDate, Double>>, heute: LocalDate, form: (Double) -> String) {
    bild.gesamt?.let { Satz(stringResource(R.string.ks_tr_schnitt, form(it))) }
    val s = bild.staerkster
    val w = bild.schwaechster
    if (s != null && w != null && s != w) {
        Leise(stringResource(R.string.ks_tr_extreme, s.tag.getDisplayName(TextStyle.FULL, Locale.getDefault()), form(s.mittel!!),
            w.tag.getDisplayName(TextStyle.FULL, Locale.getDefault()), form(w.mittel!!)))
    }
    Auswertung.wochenende(reihe, heute)?.let { Leise(stringResource(R.string.ks_tr_wochenende, form(it.wochenende), form(it.werktag))) }
}

@Composable
private fun Richtung(bild: Auswertung.Bild) {
    val v = bild.veraenderung
    Leise(if (v == null) stringResource(R.string.ks_tr_keine_richtung) else stringResource(R.string.ks_tr_richtung, String.format(Locale.getDefault(), "%+.0f", v)))
}

@Composable
private fun Einfach(d: Reihen, spalte: String, name: String, farbe: Color, heute: LocalDate, form: (Double) -> String) {
    val reihe = d[spalte].orEmpty()
    if (reihe.count { it.first < heute } < 2) return
    val bild = Auswertung.bild(reihe, heute)
    Ueberschrift(stringResource(R.string.ks_tr_typische_woche, name))
    Karte {
        Trendsaeulen(bild.profil.map { TSaeule(kurz(it.tag), it.mittel, hervor = it.tag == heute.dayOfWeek) }, farbe, bild.gesamt)
        Wochensaetze(bild, reihe, heute, form)
    }
    Ueberschrift(stringResource(R.string.ks_tr_verlauf, name))
    Karte {
        Trendsaeulen(bild.wochen.map { TSaeule(kw(it.montag), it.mittel, hervor = it.montag == heute.with(DayOfWeek.MONDAY)) }, farbe, bild.gesamt)
        Leise(stringResource(R.string.ks_tr_kw))
        Richtung(bild)
    }
}

@Composable
private fun Schlaftrend(d: Reihen, heute: LocalDate) {
    val schlaf = d["schlaf"].orEmpty()
    val gesamt = Auswertung.bild(schlaf, heute)
    val tief = Auswertung.bild(d["tief"].orEmpty(), heute)
    val tiefTag = tief.profil.associate { it.tag to it.mittel }
    val ideal = 8 * 60.0
    val form: (Double) -> String = { dauerText(it.roundToInt().toLong()) }
    val name = stringResource(R.string.ks_h_schlaf_n)
    Ueberschrift(stringResource(R.string.ks_tr_typische_woche, name))
    Karte {
        Trendsaeulen(gesamt.profil.map { TSaeule(kurz(it.tag), it.mittel, tiefTag[it.tag], it.tag == heute.dayOfWeek) }, FARBE_SCHLAF_T, gesamt.gesamt, ideal)
        Leise(stringResource(R.string.ks_tr_schlaf_legende))
        val naechte = schlaf.filter { it.first < heute }
        if (naechte.isNotEmpty()) {
            Satz(stringResource(R.string.ks_tr_erreicht, naechte.count { it.second >= ideal }, naechte.size))
        }
        tief.gesamt?.let { Leise(stringResource(R.string.ks_tr_davon_tief, form(it))) }
        Wochensaetze(gesamt, schlaf, heute, form)
    }
    val mitten = d["schlaf_mitte"].orEmpty().filter { it.first < heute }.map { it.second }
    Auswertung.streuung(mitten)?.let { streuung ->
        Ueberschrift(stringResource(R.string.ks_tr_mitte))
        Karte {
            val min = ((18 * 60 + mitten.average()).roundToInt() % 1440 + 1440) % 1440
            Text(String.format(Locale.getDefault(), "%d:%02d ± %s", min / 60, min % 60, dauerText(streuung.roundToInt().toLong())), fontWeight = FontWeight.Bold)
            Satz(stringResource(R.string.ks_tr_mitte_naechte, mitten.size) + " " + stringResource(
                when {
                    streuung < 30 -> R.string.ks_tr_sehr_regelmaessig
                    streuung < 60 -> R.string.ks_tr_regelmaessig
                    streuung < 90 -> R.string.ks_tr_schwankend
                    else -> R.string.ks_tr_stark_schwankend
                }
            ))
        }
    }
    Ueberschrift(stringResource(R.string.ks_tr_verlauf, name))
    Karte {
        val tiefW = tief.wochen.associate { it.montag to it.mittel }
        Trendsaeulen(gesamt.wochen.map { TSaeule(kw(it.montag), it.mittel, tiefW[it.montag], it.montag == heute.with(DayOfWeek.MONDAY)) }, FARBE_SCHLAF_T, gesamt.gesamt, ideal)
        Leise(stringResource(R.string.ks_tr_kw))
        Richtung(gesamt)
    }
}

@Composable
private fun Praeparattrend(d: Reihen, heute: LocalDate) {
    val faellig = Auswertung.bild(d["supp_faellig"].orEmpty(), heute)
    if (faellig.anzahl == 0) return
    val genommen = Auswertung.bild(d["supp_genommen"].orEmpty(), heute)
    val genommenTag = genommen.profil.associate { it.tag to it.mittel }
    Ueberschrift(stringResource(R.string.ks_h_praeparate))
    Karte {
        Trendsaeulen(faellig.profil.map { TSaeule(kurz(it.tag), it.mittel, genommenTag[it.tag], it.tag == heute.dayOfWeek) }, FARBE_BEWEGUNG_T)
        Leise(stringResource(R.string.ks_tr_supp_legende))
        val f = faellig.gesamt
        val g = genommen.gesamt
        if (f != null && g != null && f > 0) Satz(stringResource(R.string.ks_tr_supp_schnitt, (g / f * 100).roundToInt()))
    }
}

/** Ein Paar, nach dem man wirklich fragt - Namen als Ressourcen fuer die Satzmitte. */
private data class Paar(val x: String, val nameX: Int, val y: String, val nameY: Int)

private val PAARE = listOf(
    Paar("schlaf", R.string.ks_tr_n_schlaf, "ruhepuls", R.string.ks_tr_n_ruhepuls),
    Paar("schlaf", R.string.ks_tr_n_schlaf, "hrv", R.string.ks_tr_n_hrv),
    Paar("tief", R.string.ks_tr_n_tief, "hrv", R.string.ks_tr_n_hrv),
    Paar("schritte", R.string.ks_tr_n_schritte, "schlaf", R.string.ks_tr_n_schlaf),
    Paar("aktiv", R.string.ks_tr_n_aktiv, "ruhepuls", R.string.ks_tr_n_ruhepuls),
    Paar("schlaf", R.string.ks_tr_n_schlaf, "energie", R.string.ks_tr_n_energie),
    Paar("koffein_mg", R.string.ks_tr_n_koffein, "tief", R.string.ks_tr_n_tief),
)

private val BEZUG = mapOf(
    "bewegung" to setOf("schritte", "aktiv"),
    "schlaf" to setOf("schlaf", "tief"),
    "herz" to setOf("ruhepuls", "hrv"),
    "ernaehrung" to setOf("koffein_mg"),
)

/** Wie eine Groesse an der Achse steht: Minuten als Dauer, Schritte mit Tausendertrennung. */
private fun achsform(spalte: String): (Double) -> String = when (spalte) {
    "schlaf", "tief", "aktiv" -> { w -> dauerText(w.roundToInt().toLong()) }
    "schritte" -> { w -> String.format(Locale.getDefault(), "%,.0f", w) }
    "koffein_mg" -> { w -> "${w.roundToInt()} mg" }
    else -> { w -> "${w.roundToInt()}" }
}

@Composable
private fun Zusammenhaenge(d: Reihen, gruppe: String, heute: LocalDate) {
    val bezug = BEZUG[gruppe].orEmpty()
    val paare = PAARE.filter { it.x in bezug || it.y in bezug }
    if (paare.isEmpty()) return
    Ueberschrift(stringResource(R.string.ks_tr_zusammenhaenge))
    val bilder = paare.map { it to Auswertung.zusammenhang(d[it.x].orEmpty(), d[it.y].orEmpty(), heute) }.filter { it.second.belastbar }
    if (bilder.isEmpty()) {
        Karte { Leise(stringResource(R.string.ks_tr_paare_spaeter, Auswertung.PAARE_MINDESTENS)) }
        return
    }
    bilder.forEach { (p, z) ->
        val nx = stringResource(p.nameX)
        val ny = stringResource(p.nameY)
        Karte {
            Text(stringResource(R.string.ks_tr_x_und_y, nx, ny).replaceFirstChar { it.titlecase(Locale.getDefault()) }, fontWeight = FontWeight.Bold)
            Streubild(z, FARBE_SCHLAF_T, achsform(p.x), achsform(p.y))
            val r = z.r ?: 0.0
            val staerke = stringResource(
                when (z.stufe) {
                    0 -> R.string.ks_tr_staerke_0
                    1 -> R.string.ks_tr_staerke_1
                    2 -> R.string.ks_tr_staerke_2
                    else -> R.string.ks_tr_staerke_3
                }
            )
            Satz(stringResource(R.string.ks_tr_r_satz, String.format(Locale.getDefault(), "%.2f", r), z.n, staerke) +
                if (abs(r) >= 0.2) " " + stringResource(if (r > 0) R.string.ks_tr_mehr_mehr else R.string.ks_tr_mehr_weniger, nx, ny) else "")
        }
    }
    Leise(stringResource(R.string.ks_tr_keine_ursache))
}
