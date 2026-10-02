package coredevices.pebble.kiesel.ansicht

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Auswertung
import coredevices.pebble.kiesel.Drinktervall
import coredevices.pebble.kiesel.Reihen
import coredevices.pebble.kiesel.SupCycle
import coredevices.pebble.kiesel.UhrApps
import coredevices.pebble.kiesel.Trenddaten
import io.rebble.libpebblecommon.connection.HealthDataApi
import org.koin.compose.koinInject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Die Trends einer Gruppe. Je Messgroesse die Bausteine, die schon tragen:
 * die letzten vierzehn Tage vom ersten Tag an, die typische Woche ab drei
 * Wochentagen, die Wochen ab drei Kalenderwochen, die Vergleiche ab fuenf
 * Tagen je Gruppe. Was noch nicht traegt, kuendigt sich als leise
 * Zeile mit Datum an - eine Seite, die nur "zu wenig" sagt, schaut niemand
 * ein zweites Mal an.
 */
@Composable
internal fun TrendAnsicht(gruppe: String, setzeTitel: (String) -> Unit) {
    val context = LocalContext.current
    val api = koinInject<HealthDataApi>()
    var reihen by remember { mutableStateOf<Reihen?>(null) }
    LaunchedEffect(Unit) { reihen = Trenddaten.lies(context, api) }
    // Praeparate nur mit SupCycle auf der Uhr (UhrApps), wie auf der Startseite.
    var mitSupCycle by remember { mutableStateOf(true) }
    var mitDrinktervall by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        val apps = UhrApps.installiert()
        mitSupCycle = UhrApps.hat(apps, SupCycle.UUID)
        mitDrinktervall = UhrApps.hat(apps, Drinktervall.UUID)
    }
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
    val groessen = remember(gruppe) { groessen(gruppe, context) }
    val nacht = groessen.first().spalte in NACHTREIHEN
    val grenze = grenzeVon(groessen.first().spalte, heute)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val getragen = groessen.filter { g -> d[g.spalte].orEmpty().any { it.first < grenze } }
        if (getragen.isEmpty()) {
            Karte { Satz(stringResource(if (nacht) R.string.ks_trn_start_nacht else R.string.ks_trn_start_tag)) }
            return@Column
        }
        getragen.forEach { LetzteTage(d, it, grenze) }
        TagFuerTag(d, getragen, grenze)

        val bilder = getragen.associate { it.spalte to Auswertung.bild(d[it.spalte].orEmpty(), grenze) }
        val erster = getragen.mapNotNull { g -> d[g.spalte].orEmpty().firstOrNull { it.first < grenze }?.first }.min()

        val mitWoche = getragen.filter { bilder.getValue(it.spalte).profil.count { p -> p.mittel != null } >= 3 }
        mitWoche.forEach { TypischeWoche(it, bilder.getValue(it.spalte), d[it.spalte].orEmpty(), grenze, heute) }
        if (mitWoche.isEmpty()) {
            val ab = Auswertung.typischeWocheAb(erster)
            Leise(if (ab > heute) stringResource(R.string.ks_trn_woche_ab, datum(ab)) else stringResource(R.string.ks_trn_woche_spaeter))
        }
        if (gruppe == "ernaehrung" && mitSupCycle) Praeparattrend(d, grenze, heute)

        val mitWochen = getragen.filter { bilder.getValue(it.spalte).wochen.size >= 3 }
        mitWochen.forEach { Wochen(it, bilder.getValue(it.spalte), heute) }
        if (mitWochen.isEmpty()) {
            val ab = Auswertung.wochenAb(erster, grenze, heute)
            Leise(if (ab > heute) stringResource(R.string.ks_trn_wochen_ab, datum(ab)) else stringResource(R.string.ks_trn_wochen_spaeter))
        }

        if (gruppe == "schlaf") Schlafmitte(d, grenze)
        Vergleiche(d, gruppe, heute, mitDrinktervall)

        val tage = getragen.flatMap { g -> d[g.spalte].orEmpty().map { it.first } }.filter { it < grenze }.distinct()
        Leise(pluralStringResource(if (nacht) R.plurals.ks_trn_fuss_naechte else R.plurals.ks_trn_fuss_tage, tage.size, tage.size, datum(tage.min())))
        Spacer(Modifier.height(12.dp))
    }
}

private val FARBE_BEWEGUNG_T = Color(0xFF4F8A3A)
private val FARBE_SCHLAF_T = Color(0xFF2F5597)
private val FARBE_HERZ_T = Color(0xFFC0392B)
private val FARBE_WASSER_T = Color(0xFF3A86C8)
private val FARBE_KOFFEIN_T = Color(0xFF8A5A2B)

/**
 * Die Reihen, die eine Nacht beschreiben. Eine Nacht wird auf den Tag des
 * Aufwachens gebucht und ist am Morgen fertig - der Tag selbst erst um
 * Mitternacht. Darum zaehlen Nachtreihen bis einschliesslich heute, alle
 * anderen nur bis gestern.
 */
private val NACHTREIHEN = setOf("schlaf", "tief", "schlaf_mitte", "ruhepuls", "hrv")

private fun grenzeVon(spalte: String, heute: LocalDate) = if (spalte in NACHTREIHEN) heute.plusDays(1) else heute

/**
 * Eine Messgroesse mit allem, was ihre Karten brauchen: `kurz` fuer Zahlen im
 * Bild ("4.9k", "8:38"), `lang` fuer den Text ("4,986", "8:38 h"), das Ziel
 * als durchgezogene Linie, `pegel` fuer Groessen ohne Nullpunkt (Puls, HRV),
 * `praefix` fuer die Tagesliste ("deep 1:56 h").
 */
private class Groesse(
    val spalte: String,
    val name: Int,
    val farbe: Color,
    val ziel: Double? = null,
    val pegel: Boolean = false,
    val praefix: Int? = null,
    val kurz: (Double) -> String,
    val lang: (Double) -> String,
)

/** "8:38" fuer Stunden, sonst nur die Minuten - so eng, dass es ueber eine Saeule passt. */
private fun dauerKurz(minuten: Double) = dauerText(minuten.roundToInt().toLong()).removeSuffix(" h").removeSuffix(" min")

private fun groessen(gruppe: String, context: Context): List<Groesse> {
    val l = Locale.getDefault()
    val zahl: (Double) -> String = { "${it.roundToInt()}" }
    val dauer: (Double) -> String = { dauerText(it.roundToInt().toLong()) }
    return when (gruppe) {
        "schlaf" -> listOf(
            Groesse("schlaf", R.string.ks_h_schlaf_n, FARBE_SCHLAF_T, ziel = 8 * 60.0, kurz = ::dauerKurz, lang = dauer),
            Groesse("tief", R.string.ks_tr_n_tief, FARBE_SCHLAF_T, praefix = R.string.ks_trn_tief_kurz, kurz = ::dauerKurz, lang = dauer),
        )
        "herz" -> listOf(
            Groesse("ruhepuls", R.string.ks_h_ruhepuls, FARBE_HERZ_T, pegel = true, kurz = zahl, lang = { "${it.roundToInt()} bpm" }),
            Groesse("hrv", R.string.ks_tr_n_hrv, FARBE_HERZ_T, pegel = true, kurz = zahl, lang = { "${it.roundToInt()} ms" }),
        )
        "ernaehrung" -> listOf(
            Groesse(
                "wasser", R.string.ks_h_wasser_n, FARBE_WASSER_T, ziel = Drinktervall.soll(context)?.ml?.toDouble(),
                kurz = { String.format(l, "%.1f", it / 1000) }, lang = { String.format(l, "%.1f l", it / 1000) },
            ),
            Groesse("koffein_mg", R.string.ks_h_koffein_n, FARBE_KOFFEIN_T, kurz = zahl, lang = { "${it.roundToInt()} mg" }),
        )
        else -> listOf(
            Groesse("schritte", R.string.ks_h_schritte, FARBE_BEWEGUNG_T, ziel = 10_000.0, kurz = { Auswertung.tausender(it, l) }, lang = { String.format(l, "%,.0f", it) }),
            Groesse("aktiv", R.string.ks_h_aktiv, FARBE_BEWEGUNG_T, kurz = ::dauerKurz, lang = dauer),
        )
    }
}

private fun kurz(t: DayOfWeek) = t.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2)
private fun datum(t: LocalDate): String = t.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
private fun montag(t: LocalDate): String = t.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))

@Composable
private fun Satz(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium)

@Composable
private fun Leise(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Die letzten vierzehn Tage: Mittel der Tage mit Wert, die letzte Woche daraus, und das Bild Tag fuer Tag. */
@Composable
private fun LetzteTage(d: Reihen, g: Groesse, grenze: LocalDate) {
    val fenster = Auswertung.letzteTage(d[g.spalte].orEmpty(), grenze)
    val werte = fenster.mapNotNull { it.second }
    if (werte.isEmpty()) return
    Ueberschrift(stringResource(R.string.ks_trn_letzte14, stringResource(g.name)))
    Karte {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Wert(g.lang(werte.average()), pluralStringResource(R.plurals.ks_trn_schnitt_tage, werte.size, werte.size))
            // Die letzte Woche nur, wenn sie mehr ist als ein oder zwei Tage.
            val sieben = fenster.takeLast(7).mapNotNull { it.second }
            if (sieben.size >= 3) Wert(g.lang(sieben.average()), stringResource(R.string.ks_trn_letzte7))
        }
        Tagesbild(fenster, g.farbe, g.kurz, werte.average(), g.ziel, g.pegel)
    }
}

/** Die Tage als Liste, solange es wenige sind - Zahlen sagen mehr als drei Saeulen. */
@Composable
private fun TagFuerTag(d: Reihen, groessen: List<Groesse>, grenze: LocalDate) {
    val tage = groessen.flatMap { g -> d[g.spalte].orEmpty().map { it.first } }.filter { it < grenze }.distinct().sortedDescending()
    if (tage.isEmpty() || tage.size > 14) return
    val werte = groessen.associate { g -> g.spalte to d[g.spalte].orEmpty().toMap() }
    Ueberschrift(stringResource(R.string.ks_trn_tag_fuer_tag))
    Karte {
        tage.forEach { tag ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(datum(tag), fontWeight = FontWeight.Bold)
                    Text(tag.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val teile = groessen.map { g ->
                    val w = werte.getValue(g.spalte)[tag] ?: return@map "–"
                    g.praefix?.let { stringResource(it, g.lang(w)) } ?: g.lang(w)
                }
                Text(teile.joinToString(" · "), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Durchschnitt, staerkster und schwaechster Wochentag, Wochenende - die Saetze unter dem Wochenbild. */
@Composable
private fun Wochensaetze(bild: Auswertung.Bild, reihe: List<Pair<LocalDate, Double>>, grenze: LocalDate, form: (Double) -> String) {
    bild.gesamt?.let { Satz(stringResource(R.string.ks_tr_schnitt, form(it))) }
    val s = bild.staerkster
    val w = bild.schwaechster
    if (s != null && w != null && s != w) {
        Leise(stringResource(R.string.ks_tr_extreme, s.tag.getDisplayName(TextStyle.FULL, Locale.getDefault()), form(s.mittel!!),
            w.tag.getDisplayName(TextStyle.FULL, Locale.getDefault()), form(w.mittel!!)))
    }
    Auswertung.wochenende(reihe, grenze)?.let { Leise(stringResource(R.string.ks_tr_wochenende, form(it.wochenende), form(it.werktag))) }
}

@Composable
private fun TypischeWoche(g: Groesse, bild: Auswertung.Bild, reihe: List<Pair<LocalDate, Double>>, grenze: LocalDate, heute: LocalDate) {
    Ueberschrift(stringResource(R.string.ks_tr_typische_woche, stringResource(g.name)))
    Karte {
        Trendsaeulen(
            bild.profil.map { TSaeule(kurz(it.tag), it.mittel, hervor = it.tag == heute.dayOfWeek, kleinster = it.kleinster, groesster = it.groesster) },
            g.farbe, bild.gesamt, g.ziel, kurz = g.kurz,
        )
        if (g.spalte == "schlaf") {
            val naechte = reihe.filter { it.first < grenze }
            Satz(stringResource(R.string.ks_tr_erreicht, naechte.count { it.second >= g.ziel!! }, naechte.size))
        }
        Wochensaetze(bild, reihe, grenze, g.lang)
    }
}

/** Die Kalenderwochen, benannt nach ihrem Montag; die laufende blass und "so far". */
@Composable
private fun Wochen(g: Groesse, bild: Auswertung.Bild, heute: LocalDate) {
    Ueberschrift(stringResource(R.string.ks_tr_verlauf, stringResource(g.name)))
    val laufend = heute.with(DayOfWeek.MONDAY)
    val bisher = stringResource(R.string.ks_trn_so_far)
    Karte {
        Trendsaeulen(
            bild.wochen.map { w ->
                // Die laufende Woche nicht zugleich blass und fett: blass und "so far" reichen als Kennzeichnung.
                val jetzt = w.montag == laufend
                TSaeule(montag(w.montag), w.mittel, blass = jetzt, hinweis = if (jetzt) bisher else null)
            },
            g.farbe, bild.gesamt, g.ziel, kurz = g.kurz,
        )
        bild.veraenderung?.let { Leise(stringResource(R.string.ks_tr_richtung, String.format(Locale.getDefault(), "%+.0f", it))) }
    }
}

@Composable
private fun Schlafmitte(d: Reihen, grenze: LocalDate) {
    val mitten = d["schlaf_mitte"].orEmpty().filter { it.first < grenze }.map { it.second }
    val streuung = Auswertung.streuung(mitten) ?: return
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

@Composable
private fun Praeparattrend(d: Reihen, grenze: LocalDate, heute: LocalDate) {
    val faellig = Auswertung.bild(d["supp_faellig"].orEmpty(), grenze)
    if (faellig.profil.count { it.mittel != null } < 3) return
    val genommen = Auswertung.bild(d["supp_genommen"].orEmpty(), grenze)
    val genommenTag = genommen.profil.associate { it.tag to it.mittel }
    Ueberschrift(stringResource(R.string.ks_h_praeparate))
    Karte {
        Trendsaeulen(
            faellig.profil.map { TSaeule(kurz(it.tag), it.mittel, genommenTag[it.tag], it.tag == heute.dayOfWeek) },
            FARBE_BEWEGUNG_T, kurz = { "${it.roundToInt()}" },
        )
        Leise(stringResource(R.string.ks_tr_supp_legende))
        val f = faellig.gesamt
        val g = genommen.gesamt
        if (f != null && g != null && f > 0) Satz(stringResource(R.string.ks_tr_supp_schnitt, (g / f * 100).roundToInt()))
    }
}

