package coredevices.pebble.kiesel.ansicht

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import coredevices.pebble.R
import coredevices.pebble.kiesel.Auswertung
import coredevices.pebble.kiesel.Drinktervall
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.Reihen
import coredevices.pebble.kiesel.Fenster
import coredevices.pebble.kiesel.Gesundheitsziele
import coredevices.pebble.kiesel.Spalte
import coredevices.pebble.kiesel.SupCycle
import coredevices.pebble.kiesel.get
import coredevices.pebble.kiesel.Trenddaten
import coredevices.pebble.kiesel.Trendladen
import coredevices.pebble.kiesel.UhrApps
import coredevices.pebble.kiesel.Vergleichsfragen
import io.rebble.libpebblecommon.connection.HealthDataApi
import org.koin.compose.koinInject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Die Trends einer Gruppe. Je Messgroesse die Bausteine, die schon tragen:
 * die letzten vierzehn Tage vom ersten Tag an, gleich danach die Vergleiche
 * (ab fuenf Tagen je Gruppe - sie sind die eigentliche Aussage der Seite),
 * die typische Woche ab drei Wochentagen, die Wochen ab drei Kalenderwochen.
 * Was noch nicht traegt, steht mit seinem Datum ("from Oct 12") bei den
 * wartenden Vergleichen unter "Needs more days" - ein Ort fuer alles, was
 * noch kommt, statt verstreuter Saetze zwischen den Karten.
 *
 * Jeder Abschnitt ist ein Kasten mit Kopf (Baukasten): vorher standen auf
 * dieser Seite drei Kopfstile untereinander.
 */
@Composable
internal fun TrendAnsicht(gruppe: String, setzeTitel: (String) -> Unit) {
    val context = LocalContext.current
    val api = koinInject<HealthDataApi>()
    // Neu auch nach jedem Schub der Uhr: eine Nacht, die gerade ankommt, gehoert gleich in die Reihen.
    // Ein Schub liest nur die Uhr neu, die Akte (2-3 Abfragen, 50-150 ms) bleibt; Aufbau, Rueckkehr
    // und ein eigener Eintrag lesen alles - etwa ein Glas aus Drinktervall, das nur ueber die Akte kommt.
    // Die Zwischenstaende dafuer halten nur, solange die Seite sichtbar ist (0,5-1 MB).
    LifecycleStartEffect(Unit) {
        Trenddaten.seiteSichtbar()
        onStopOrDispose { Trenddaten.seiteWeg() }
    }
    val laden = remember { Trendladen() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { laden.rueckkehr() }
    val eintraege by Trenddaten.eigeneEintraege.collectAsState()
    val stand = rememberStand(Trenddaten.zuletzt, rememberUhrschub(), eintraege) {
        laden.lade { nurUhr -> Trenddaten.lies(context, api, nurUhr = nurUhr) }
    }
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
            // Der Einstieg heisst "Water" und "Caffeine" - die Seite nennt beides.
            "ernaehrung" -> R.string.ks_trn_ernaehrung
            else -> R.string.ks_tr_bewegung
        }
    )
    LaunchedEffect(titel) { setzeTitel(titel) }
    val geladen = stand
    if (geladen == null) {
        Ladekreis()
        return
    }
    val d = geladen.reihen
    val heute = LocalDate.now()
    val groessen = remember(gruppe) { groessen(gruppe, context) }
    val nacht = groessen.first().spalte.nacht
    val grenze = groessen.first().spalte.grenze(heute)
    val getragen = groessen.filter { g -> d[g.spalte].orEmpty().any { it.first < grenze } }
    if (getragen.isEmpty()) {
        // Noch kein Tag: die ganze Seite ist leer, nicht eine Karte auf ihr.
        Leerseite(stringResource(if (nacht) R.string.ks_trn_start_nacht else R.string.ks_trn_start_tag))
        return
    }
    // Gemerkt: jede Rekomposition (Titel, Uhr-Apps) rechnete sonst alle Bilder neu, je 5-20 ms.
    val bilder = remember(geladen, gruppe, grenze) {
        getragen.associate { it.spalte to Auswertung.bild(d[it.spalte].orEmpty(), grenze, geladen.eigenAb[it.spalte]) }
    }
    val erster = getragen.mapNotNull { g -> d[g.spalte].orEmpty().firstOrNull { it.first < grenze }?.first }.min()
    val mitWoche = getragen.filter { bilder.getValue(it.spalte).profil.count { p -> p.mittel != null } >= 3 }
    val mitWochen = getragen.filter { bilder.getValue(it.spalte).wochen.size >= 3 }
    // Nur ein Datum ankuendigen, das noch kommt; verzoegern Luecken es, schweigt die Seite.
    val ab = listOfNotNull(
        Auswertung.typischeWocheAb(erster, grenze, heute).takeIf { mitWoche.isEmpty() && it > heute }
            ?.let { stringResource(R.string.ks_trn_typische_woche_name) to stringResource(R.string.ks_trn_ab, Formate.datumKurz(it)) },
        Auswertung.wochenAb(erster, grenze, heute).takeIf { mitWochen.isEmpty() && it > heute }
            ?.let { stringResource(R.string.ks_trn_wochen_name) to stringResource(R.string.ks_trn_ab, Formate.datumKurz(it)) },
    )
    Seite {
        // ZUERST WAS EINEN UNTERSCHIED MACHT (festgehaltenes Design), dann die
        // letzten 14 Tage; die wartenden Vergleiche erst nach ihnen.
        Vergleiche(d, gruppe, heute, mitDrinktervall, ausstehend = ab) {
            getragen.forEach { LetzteTage(d, it, grenze) }
        }
        TagFuerTag(d, getragen, grenze)
        mitWoche.forEach { TypischeWoche(it, bilder.getValue(it.spalte), d[it.spalte].orEmpty(), grenze, heute) }
        if (gruppe == "ernaehrung" && mitSupCycle) Praeparattrend(d, grenze, heute)
        mitWochen.forEach { Wochen(it, bilder.getValue(it.spalte), heute) }
        if (gruppe == "schlaf") Schlafmitte(d, grenze)
    }
}

/**
 * Die Farbe einer Reihe - in Trends und Vergleichen dieselbe, aus [Farben].
 * Vorher hatten die Trends eigene Hexwerte und die Vergleiche andere: die
 * HRV war hier rot, dort violett.
 */
internal fun farbeDerReihe(spalte: Spalte): Farbe = when (spalte) {
    Spalte.SCHLAF, Spalte.TIEF, Spalte.SCHLAF_MITTE, Spalte.EINSCHLAFEN -> Farben.schlaf
    Spalte.RUHEPULS -> Farben.puls
    Spalte.HRV -> Farben.hrv
    Spalte.WASSER -> Farben.wasser
    Spalte.KOFFEIN -> Farben.koffein
    Spalte.PRAEPARATE_FAELLIG, Spalte.PRAEPARATE_GENOMMEN -> Farben.praeparat
    Spalte.SCHRITTE, Spalte.AKTIV -> Farben.bewegung
}

/**
 * Eine Streuung: "±45 min". Ohne Abstand wie [Formate.vorzeichen] ("±0") und
 * in Trends und Vergleichen gleich - vorher stand hier "± 45 min", dort
 * "±45 min". Formate kennt keine Streuung (Luecke der Grundlage).
 */
internal fun plusMinus(text: String): String = "±$text"

/**
 * Eine Messgroesse mit allem, was ihre Karten brauchen: [angabe] fuer Zahl
 * und Einheit getrennt ("4,986", "8:38 h", "52 bpm"), [kurz] nur die Zahl,
 * so eng, dass sie ueber eine Saeule passt ("4.9k", "8:38"), das Ziel als
 * durchgezogene Linie, `pegel` fuer Groessen ohne Nullpunkt (Puls, HRV).
 * Beide Formen kommen aus [Formate].
 */
private class Groesse(
    val spalte: Spalte,
    val name: Int,
    val ziel: Double? = null,
    val pegel: Boolean = false,
    val angabe: (Double) -> Formate.Angabe,
    val kurz: (Double) -> String,
) {
    val farbe: Farbe get() = farbeDerReihe(spalte)

    /** Zahl und Einheit als ein Text, fuer Saetze und die Tagesliste. */
    fun lang(w: Double): String = angabe(w).text
}

private fun groessen(gruppe: String, context: Context): List<Groesse> {
    val dauer: (Double) -> Formate.Angabe = { Formate.dauerTeile(it.roundToLong()) }
    val dauerKurz: (Double) -> String = { dauer(it).zahl }
    val ganz: (Double) -> String = { Formate.zahl(it, 0) }
    fun mit(einheit: String?): (Double) -> Formate.Angabe = { Formate.menge(it, einheit) }
    return when (gruppe) {
        "schlaf" -> listOf(
            Groesse(Spalte.SCHLAF, R.string.ks_h_schlaf_n, ziel = Gesundheitsziele.SCHLAF_MIN.toDouble(), angabe = dauer, kurz = dauerKurz),
            Groesse(Spalte.TIEF, R.string.ks_tr_n_tief, angabe = dauer, kurz = dauerKurz),
        )
        "herz" -> listOf(
            Groesse(Spalte.RUHEPULS, R.string.ks_h_ruhepuls, pegel = true, angabe = mit("bpm"), kurz = ganz),
            Groesse(Spalte.HRV, R.string.ks_tr_n_hrv, pegel = true, angabe = mit("ms"), kurz = ganz),
        )
        "ernaehrung" -> listOf(
            Groesse(
                Spalte.WASSER, R.string.ks_h_wasser_n, ziel = Drinktervall.soll(context)?.ml?.toDouble(),
                angabe = { Formate.Angabe(Formate.liter(it.roundToInt()), "l") }, kurz = { Formate.liter(it.roundToInt()) },
            ),
            Groesse(Spalte.KOFFEIN, R.string.ks_h_koffein_n, angabe = mit("mg"), kurz = ganz),
        )
        else -> listOf(
            Groesse(Spalte.SCHRITTE, R.string.ks_h_schritte, ziel = Gesundheitsziele.SCHRITTE.toDouble(), angabe = mit(null), kurz = { Formate.schritteKurz(it) }),
            Groesse(Spalte.AKTIV, R.string.ks_h_aktiv, angabe = dauer, kurz = dauerKurz),
        )
    }
}

/** Der Zusatz einer Karte mit festem Fenster: "last 90 days" - der Kopf setzt das "·" davor. */
@Composable
private fun fenster() = stringResource(R.string.ks_trn_zeitraum, Fenster.TREND_TAGE.toInt())

/** Die letzten vierzehn Tage: Mittel der Tage mit Wert, die letzte Woche daraus, und das Bild Tag fuer Tag. */
@Composable
private fun LetzteTage(d: Reihen, g: Groesse, grenze: LocalDate) {
    val fenster = Auswertung.letzteTage(d[g.spalte].orEmpty(), grenze)
    val werte = fenster.mapNotNull { it.second }
    if (werte.isEmpty()) return
    Abschnitt(stringResource(R.string.ks_trn_letzte14, stringResource(g.name))) {
        Kennzahlen {
            val schnitt = g.angabe(werte.average())
            Kennzahl(schnitt.zahl, schnitt.einheit, pluralStringResource(R.plurals.ks_trn_schnitt_tage, werte.size, werte.size))
            // Die letzte Woche nur, wenn sie mehr ist als ein oder zwei Tage.
            val sieben = fenster.takeLast(Fenster.WOCHE_TAGE).mapNotNull { it.second }
            if (sieben.size >= 3) {
                val woche = g.angabe(sieben.average())
                Kennzahl(woche.zahl, woche.einheit, stringResource(R.string.ks_trn_letzte7))
            }
        }
        Tagesbild(fenster, g.farbe, g.kurz, werte.average(), g.ziel, g.pegel, sprich = g::lang, name = stringResource(g.name))
    }
}

/**
 * Die Tage als Tabelle, solange es wenige sind - Zahlen sagen mehr als drei
 * Saeulen. Eine Spalte je Groesse, ihr Name im Tabellenkopf: so braucht der
 * Tiefschlaf kein "deep" mehr vor jeder Zahl.
 */
@Composable
private fun TagFuerTag(d: Reihen, groessen: List<Groesse>, grenze: LocalDate) {
    val tage = groessen.flatMap { g -> d[g.spalte].orEmpty().map { it.first } }.filter { it < grenze }.distinct().sortedDescending()
    // Hoechstens so viele Zeilen wie die Karte der letzten Tage hat; danach ist das Bild lesbarer.
    if (tage.isEmpty() || tage.size > Fenster.LETZTE_TAGE) return
    val werte = groessen.associate { g -> g.spalte to d[g.spalte].orEmpty().toMap() }
    val locale = Locale.getDefault()
    val kopf = listOf("") + groessen.map { g -> stringResource(g.name).replaceFirstChar { it.titlecase(locale) } }
    val zeilen = tage.map { tag ->
        listOf(Formate.datumMitWochentag(tag)) + groessen.map { g -> werte.getValue(g.spalte)[tag]?.let { g.lang(it) } ?: Formate.FEHLT }
    }
    Abschnitt(stringResource(R.string.ks_trn_tag_fuer_tag)) {
        // Das Datum nimmt den Rest, die Werte feste Spalten - so stehen sie untereinander. 72 dp tragen
        // "12,345" und "Resting HR" und lassen bei Schrift 1,3 auf 360 dp dem Datum noch 121 dp: "Mon, Sep 28" bricht nicht um.
        Tabelle(kopf, zeilen, listOf<Dp?>(null) + groessen.map { 72.dp })
    }
}

/** Durchschnitt, staerkster und schwaechster Wochentag, Wochenende - die Zeilen unter dem Wochenbild. */
@Composable
private fun Wochensaetze(bild: Auswertung.Bild, reihe: List<Pair<LocalDate, Double>>, grenze: LocalDate, g: Groesse) {
    bild.gesamt?.let { Fliesstext(stringResource(R.string.ks_trn_schnitt, g.lang(it))) }
    val s = bild.staerkster
    val w = bild.schwaechster
    if (s != null && w != null && s != w) {
        // Im Satz der kurze Wochentag ("Mon") wie ueberall im Text - die lange Form nur fuer TalkBack.
        Unterzeile(stringResource(R.string.ks_trn_extreme, Formate.wochentag(s.tag), g.lang(s.mittel!!), Formate.wochentag(w.tag), g.lang(w.mittel!!)))
    }
    // Wo ein Vergleich Wochenende gegen Werktag schon prueft, sagt die Wochenkarte es nicht noch einmal anders.
    if (!Vergleichsfragen.vergleichtWochenende(g.spalte)) {
        Auswertung.wochenende(reihe, grenze)?.let { Unterzeile(stringResource(R.string.ks_trn_wochenende, g.lang(it.wochenende), g.lang(it.werktag))) }
    }
}

@Composable
private fun TypischeWoche(g: Groesse, bild: Auswertung.Bild, reihe: List<Pair<LocalDate, Double>>, grenze: LocalDate, heute: LocalDate) {
    Abschnitt(stringResource(R.string.ks_trn_typische_woche, stringResource(g.name)), zusatz = fenster()) {
        Trendsaeulen(
            bild.profil.map {
                TSaeule(
                    Formate.wochentagKurz(it.tag), it.mittel, hervor = it.tag == heute.dayOfWeek,
                    kleinster = it.kleinster, groesster = it.groesster, gesprochen = Formate.wochentagLang(it.tag),
                )
            },
            g.farbe, bild.gesamt, g.ziel, kurz = g.kurz, pegel = g.pegel, sprich = g::lang, name = stringResource(g.name),
        )
        if (g.spalte == Spalte.SCHLAF) {
            val naechte = Auswertung.imFenster(reihe, grenze)
            Fliesstext(stringResource(R.string.ks_trn_erreicht, naechte.count { it.second >= g.ziel!! }, naechte.size, g.lang(g.ziel!!)))
        }
        Wochensaetze(bild, reihe, grenze, g)
    }
}

/** Die Kalenderwochen, benannt nach ihrem Montag; die laufende und duenne blass, die laufende mit "so far". */
@Composable
private fun Wochen(g: Groesse, bild: Auswertung.Bild, heute: LocalDate) {
    val laufend = heute.with(DayOfWeek.MONDAY)
    val bisher = stringResource(R.string.ks_trn_so_far)
    Abschnitt(stringResource(R.string.ks_trn_verlauf, stringResource(g.name))) {
        Trendsaeulen(
            bild.wochen.map { w ->
                // Die laufende Woche nicht zugleich blass und fett: blass und "so far" reichen als Kennzeichnung.
                val jetzt = w.montag == laufend
                TSaeule(Formate.datumKurz(w.montag), w.mittel, blass = jetzt || w.anzahl < Auswertung.WOCHE_VOLL_AB, hinweis = if (jetzt) bisher else null)
            },
            g.farbe, bild.gesamt, g.ziel, kurz = g.kurz, pegel = g.pegel, sprich = g::lang, name = stringResource(g.name),
        )
        // Die Richtung in Prozent mit Vorzeichen und ohne Abstand vor "%", wie ueberall.
        bild.veraenderung?.let { Unterzeile(stringResource(R.string.ks_trn_richtung, Formate.Angabe(Formate.vorzeichen(it), "%").text)) }
    }
}

@Composable
private fun Schlafmitte(d: Reihen, grenze: LocalDate) {
    val context = LocalContext.current
    val mitten = Auswertung.imFenster(d[Spalte.SCHLAF_MITTE].orEmpty(), grenze).map { it.second }
    val streuung = Auswertung.streuung(mitten) ?: return
    // Die Reihe zaehlt Minuten ab 18 Uhr des Vortags. Die Mitte als Uhrzeit des Telefons - fest "3:12" galt auch bei 12 Stunden.
    val min = ((18 * 60 + mitten.average()).roundToInt() % 1440 + 1440) % 1440
    Abschnitt(stringResource(R.string.ks_tr_mitte), zusatz = fenster()) {
        Hauptwert(
            Formate.uhrzeit(context, LocalTime.of(min / 60, min % 60)), plusMinus(Formate.dauer(streuung.roundToLong())),
            unter = stringResource(
                when {
                    streuung < 30 -> R.string.ks_tr_sehr_regelmaessig
                    streuung < 60 -> R.string.ks_tr_regelmaessig
                    streuung < 90 -> R.string.ks_tr_schwankend
                    else -> R.string.ks_tr_stark_schwankend
                }
            ),
        )
    }
}

@Composable
private fun Praeparattrend(d: Reihen, grenze: LocalDate, heute: LocalDate) {
    val faellig = remember(d, grenze) { Auswertung.bild(d[Spalte.PRAEPARATE_FAELLIG].orEmpty(), grenze) }
    if (faellig.profil.count { it.mittel != null } < 3) return
    val genommen = remember(d, grenze) { Auswertung.bild(d[Spalte.PRAEPARATE_GENOMMEN].orEmpty(), grenze) }
    val genommenTag = genommen.profil.associate { it.tag to it.mittel }
    Abschnitt(stringResource(R.string.ks_h_praeparate), zusatz = fenster()) {
        Trendsaeulen(
            faellig.profil.map { TSaeule(Formate.wochentagKurz(it.tag), it.mittel, genommenTag[it.tag], it.tag == heute.dayOfWeek, gesprochen = Formate.wochentagLang(it.tag)) },
            farbeDerReihe(Spalte.PRAEPARATE_FAELLIG), kurz = { Formate.zahl(it, 0) }, name = stringResource(R.string.ks_h_praeparate),
        )
        val f = faellig.gesamt
        val g = genommen.gesamt
        if (f != null && g != null && f > 0) Fliesstext(stringResource(R.string.ks_trn_supp_schnitt, Formate.prozent((g / f * 100).roundToInt())))
    }
}
