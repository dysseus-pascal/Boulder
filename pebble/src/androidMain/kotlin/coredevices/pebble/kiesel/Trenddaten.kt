package coredevices.pebble.kiesel

import android.content.Context
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.time.TimeRangeFilter
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

/** Je Spalte die Tage mit Wert - was der Trend-Schirm braucht. */
internal typealias Reihen = Map<String, List<Pair<LocalDate, Double>>>

/**
 * Was die Trends zeigen, mit Herkunft: [eigen] misst Boulder selbst (aus Uhr
 * und Akte, aeltere Tage aus dem Tagesspeicher), [vorgeschichte] stammt aus
 * einer Sicherung. [reihen] fuegt beides zusammen.
 */
internal class Trendstand(val eigen: Reihen, val vorgeschichte: Reihen) {
    val reihen: Reihen = Trenddaten.mitVorgeschichte(eigen, vorgeschichte)

    /** Der erste eigene Tag je Reihe; davor spricht die Vorgeschichte. */
    val eigenAb: Map<String, LocalDate> = Trenddaten.eigenAb(eigen)
}

internal object Trenddaten {
    private const val TAG = "Trends"

    /** So weit liest Boulder Uhr und Akte; was aelter ist, haelt der Tagesspeicher. */
    const val TAGE = 180L

    /**
     * Die Reihen aus der Datenbank der Uhr - nur sie werden festgeschrieben.
     * Wasser, Koffein und Praeparate bleiben in Akte und Einstellungen, und ein
     * dort geloeschter Eintrag soll nicht aus dem Speicher wiederkehren.
     */
    val UHRREIHEN = Spalte.schluessel(
        Spalte.SCHRITTE, Spalte.AKTIV, Spalte.SCHLAF, Spalte.TIEF, Spalte.SCHLAF_MITTE, Spalte.RUHEPULS, Spalte.HRV, Spalte.EINSCHLAFEN,
    )

    /**
     * Kiesel-Helper mass hier etwas anderes (die Trainingsdauer) - diese Reihen
     * zeigen die Trends nie aus der Vorgeschichte. Gespeichert bleiben sie.
     */
    val OHNE_VORGESCHICHTE = Spalte.schluessel(Spalte.AKTIV)

    /**
     * Die Reihen aus den Minutendaten (health_data). Nur sie loescht das
     * Housekeeping - die Naechte der Firmware (boulder_nacht) bleiben stehen.
     */
    val MINUTENREIHEN = Spalte.schluessel(Spalte.SCHRITTE, Spalte.AKTIV)

    /**
     * So alt darf ein Tag sein, dessen Minutendaten sicher noch ganz da sind.
     * Das Housekeeping loescht alle 6 h, was aelter als 90 x 24 h ist - mitten
     * im Tag, und eine Zeitumstellung verschiebt den Schnitt um eine Stunde.
     * Fuer den Tag heute-90 meldete die Datenbank dann 2'100 statt 11'000 Schritte.
     */
    const val SICHER_TAGE = 88L

    /**
     * Was [Tagesspeicher.halte] festschreibt: abgeschlossene Tage der Uhrreihen,
     * aus den Minutendaten nur die sicher ganzen. Aeltere schrieben einen
     * angeschnittenen Tag ueber den vollen.
     */
    fun festzuschreiben(neu: Reihen, heute: LocalDate): Reihen {
        val ab = heute.minusDays(SICHER_TAGE)
        return neu.filterKeys { it in UHRREIHEN }.mapValues { (k, l) ->
            l.filter { (t, _) -> t < heute && (k !in MINUTENREIHEN || t >= ab) }
        }
    }

    /**
     * Datenbank und Archiv der Uhrreihen zusammen: die Datenbank gewinnt, nur
     * bei den Minutendaten nicht weiter als [SICHER_TAGE] zurueck - davor ist
     * ihr Tag womoeglich schon angeschnitten, und das Archiv spricht allein.
     */
    fun mitArchiv(neu: Reihen, archiv: Reihen, heute: LocalDate): Reihen {
        val ab = heute.minusDays(SICHER_TAGE)
        val frisch = neu.mapValues { (k, l) -> if (k in MINUTENREIHEN) l.filter { it.first >= ab } else l }
        return vereine(frisch, archiv.filterKeys { it in UHRREIHEN })
    }

    /**
     * Wer Kiesel-Helper frueher zurueckgeholt hat, hat in der Vorgeschichte nur
     * dessen "schlaf_von" (Minuten ab 18 Uhr) - "einschlafen" uebersetzt erst
     * [Sicherungsformat.fuerBoulder]. Beim Lesen nachgeholt, sonst fehlte
     * "Resting HR after a late bedtime" die Vorgeschichte bis zum naechsten Zurueckholen.
     */
    fun mitEinschlafen(vorgeschichte: Reihen): Reihen {
        val von = vorgeschichte["schlaf_von"] ?: return vorgeschichte
        val da = vorgeschichte[Spalte.EINSCHLAFEN].orEmpty()
        val tage = da.map { it.first }.toSet()
        val dazu = von.filter { it.first !in tage }
        if (dazu.isEmpty()) return vorgeschichte
        return vorgeschichte + (Spalte.EINSCHLAFEN.schluessel to (da + dazu).sortedBy { it.first })
    }

    /** Neu gerechnet gewinnt; der Speicher fuellt nur Tage, die neu nichts wissen. */
    fun vereine(neu: Reihen, gespeichert: Reihen): Reihen =
        (neu.keys + gespeichert.keys).associateWith { k ->
            val n = neu[k].orEmpty().toMap()
            (gespeichert[k].orEmpty().filter { it.first !in n } + n.toList()).sortedBy { it.first }
        }

    /**
     * Die Vorgeschichte nur VOR dem ersten eigenen Wert einer Reihe, nie
     * tageweise dazwischen. Kiesel-Helper mass unter gleichem Namen anderes
     * (Telefonschritte, Ruhepuls aus einer anderen Rechnung, die HRV des
     * letzten Satzes): eingestreut machte das aus einem Ladetag 4'200 Schritte.
     */
    fun mitVorgeschichte(eigen: Reihen, vorgeschichte: Reihen): Reihen {
        val ab = eigenAb(eigen)
        return (eigen.keys + vorgeschichte.keys).associateWith { k ->
            val davor = if (k in OHNE_VORGESCHICHTE) emptyList() else vorgeschichte[k].orEmpty().filter { (t, _) -> ab[k]?.let { t < it } ?: true }
            (davor + eigen[k].orEmpty()).sortedBy { it.first }
        }.filterValues { it.isNotEmpty() }
    }

    fun eigenAb(eigen: Reihen): Map<String, LocalDate> =
        eigen.mapNotNull { (k, l) -> l.minOfOrNull { it.first }?.let { k to it } }.toMap()

    /**
     * Bis wohin die Tageszaehler der Uhr vollstaendig sind: ein Tag zaehlt erst,
     * wenn Daten ueber sein Ende hinaus da sind. Hat die Uhr seit gestern Mittag
     * nicht synchronisiert, gingen sonst 3'100 Schritte als ganzer Tag ein.
     */
    fun uhrGrenze(heute: LocalDate, letzteDaten: LocalDate?): LocalDate = letzteDaten?.let { minOf(heute, it) } ?: heute

    /**
     * Koffeinfreie Tage als 0 mg: ab dem ersten eigenen Glas ([ab], Drinktervall)
     * an jedem Tag mit Wasser - dann wurde protokolliert, und kein Kaffee heisst
     * keiner. Ohne sie verglich "After less" nur 1 bis 199 mg mit 200+.
     */
    fun koffeinMitNull(
        koffein: List<Pair<LocalDate, Double>>,
        wasser: List<Pair<LocalDate, Double>>,
        ab: LocalDate?,
        grenze: LocalDate,
    ): List<Pair<LocalDate, Double>> {
        if (ab == null) return koffein
        val da = koffein.map { it.first }.toSet()
        val ohneKaffee = wasser.filter { (t, w) -> w > 0 && t >= ab && t < grenze && t !in da }.map { it.first to 0.0 }
        return (koffein + ohneKaffee).sortedBy { it.first }
    }

    /** Die Mitte zwischen Einschlafen und Aufwachen, in Minuten ab 18 Uhr des Vortags. */
    fun schlafMitte(einschlafen: Instant, aufwachen: Instant, zone: ZoneId): Double {
        val morgen = aufwachen.atZone(zone).toLocalDate()
        val bezug = morgen.minusDays(1).atTime(18, 0).atZone(zone).toInstant()
        return ((einschlafen.epochSecond + aufwachen.epochSecond) / 2 - bezug.epochSecond) / 60.0
    }

    /**
     * Die Nachtreihen aus den Naechten der Firmware. [abschnitte] sind die
     * Phasen ALLER dieser Naechte aus einer Abfrage; jede Nacht nimmt sich
     * die mit ihrem Beginn.
     */
    fun nachtreihen(naechte: List<NachtEntity>, abschnitte: List<NachtAbschnittEntity>, zone: ZoneId): Reihen {
        val jeNacht = abschnitte.groupBy { it.beginnUtc }
        val schlaf = mutableListOf<Pair<LocalDate, Double>>()
        val tief = mutableListOf<Pair<LocalDate, Double>>()
        val mitte = mutableListOf<Pair<LocalDate, Double>>()
        val ruhe = mutableListOf<Pair<LocalDate, Double>>()
        val hrv = mutableListOf<Pair<LocalDate, Double>>()
        val ein = mutableListOf<Pair<LocalDate, Double>>()
        naechte.forEach { e ->
            val tag = Gesundheitsrechnung.nachtTag(e.beginnUtc, e.aufwachen, zone)
            if (e.schlafMinuten > 0) schlaf += tag to e.schlafMinuten.toDouble()
            val phasen = Gesundheitsrechnung.phasenMinuten(jeNacht[e.beginnUtc].orEmpty())
            if (Gesundheitsrechnung.mitPhasen(phasen)) tief += tag to phasen[2].toDouble()
            if (e.aufwachen > e.einschlafen) {
                // Das Einschlafen in Minuten ab 18 Uhr des Vortags, wie die Mitte - fuer "spaet ins Bett".
                ein += tag to (Instant.ofEpochSecond(e.beginnUtc + e.einschlafen * 60L).epochSecond -
                    tag.minusDays(1).atTime(18, 0).atZone(zone).toEpochSecond()) / 60.0
                mitte += tag to schlafMitte(
                    Instant.ofEpochSecond(e.beginnUtc + e.einschlafen * 60L), Instant.ofEpochSecond(e.beginnUtc + e.aufwachen * 60L), zone,
                )
            }
            if (e.ruhepuls > 0) ruhe += tag to e.ruhepuls.toDouble()
            if (e.hrv > 0) hrv += tag to e.hrv.toDouble()
        }
        return linkedMapOf(
            Spalte.SCHLAF.schluessel to schlaf, Spalte.TIEF.schluessel to tief, Spalte.SCHLAF_MITTE.schluessel to mitte,
            Spalte.RUHEPULS.schluessel to ruhe, Spalte.HRV.schluessel to hrv, Spalte.EINSCHLAFEN.schluessel to ein,
        )
    }

    /** Schreibt die Uhrreihen fest, ohne die Trends zu rechnen - fuer den taeglichen Lauf ([Tagesarchiv]). */
    suspend fun halteFest(context: Context, api: HealthDataApi) = withContext(Dispatchers.IO) {
        val heute = LocalDate.now()
        Tagesspeicher.halte(context, festzuschreiben(ausDerUhr(api, heute, ZoneId.systemDefault()), heute), merken = false)
    }

    /**
     * Ab wann [ausDerUhr] die Bewegung je Tag liest: [mitArchiv] und [festzuschreiben] werfen
     * die Minutenreihen vor heute-[SICHER_TAGE] ohnehin weg. Weil das Housekeeping nur 90 Tage
     * stehen laesst, spart das nur die 2 Randtage (~2'900 date()-Rechnungen, ~2 %).
     */
    fun bewegungAb(heute: LocalDate): LocalDate = heute.minusDays(SICHER_TAGE)

    /**
     * Die Reihen aus der Datenbank der Uhr: die Bewegung bis zur Sync-Grenze
     * und die Naechte. Eigens, weil [Tagesarchiv] taeglich nur sie festschreibt.
     */
    private suspend fun ausDerUhr(api: HealthDataApi, heute: LocalDate, zone: ZoneId): Map<String, List<Pair<LocalDate, Double>>> {
        val von = heute.minusDays(TAGE)
        fun anfang(t: LocalDate) = t.atStartOfDay(zone).toEpochSecond()
        val neu = mutableMapOf<String, List<Pair<LocalDate, Double>>>()

        val letzte = versuche(TAG, "Letzte Daten") { api.getLatestTimestamp() }?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDate() }
        val voll = uhrGrenze(heute, letzte)
        val bewegung = versuche(TAG, "Bewegung je Tag") { api.getDailyAggregates(anfang(bewegungAb(heute)), anfang(heute.plusDays(1))) }.orEmpty()
            .filter { LocalDate.parse(it.day) < voll }
        neu[Spalte.SCHRITTE] = bewegung.mapNotNull { a -> a.steps?.takeIf { it > 0 }?.let { LocalDate.parse(a.day) to it.toDouble() } }
        // Aktivminuten nur an Tagen mit Schritten: eine Uhr am Ladekabel meldet 0, und das ist kein ruhiger Tag.
        neu[Spalte.AKTIV] = bewegung.filter { (it.steps ?: 0) > 0 }.mapNotNull { a -> a.activeMinutes?.let { LocalDate.parse(a.day) to it.toDouble() } }

        val naechte = Gesundheitsrechnung.jeTag(
            versuche(TAG, "Naechte") { api.getNaechte(anfang(von), anfang(heute.plusDays(1))) }.orEmpty(), zone,
        )
        // Die Phasen aller Naechte in einer Abfrage, ueber dasselbe Fenster wie die
        // Naechte - eine je Nacht waren bis zu 180 Abfragen bei jedem Oeffnen.
        // Scheitert sie, fehlt die Tiefschlafreihe ganz statt nur einzelner Naechte.
        val abschnitte = versuche(TAG, "Abschnitte der Naechte") { api.getNachtAbschnitte(anfang(von), anfang(heute.plusDays(1))) }.orEmpty()
        neu.putAll(nachtreihen(naechte, abschnitte, zone))
        return neu
    }

    /**
     * Der zuletzt gelesene Stand, solange der Prozess lebt: die Trends zeigen
     * ihn bei der Rueckkehr gleich, statt erst einen Ladekreis.
     */
    @Volatile
    var zuletzt: Trendstand? = null
        private set

    /**
     * Wasser und Koffein aus der Akte - ein Uhrschub aendert sie nicht. [heil]: keine der
     * Abfragen schlug fehl; nur dann taugt das Ergebnis fuer die naechsten Schuebe.
     */
    private class Akteteil(val wasser: List<Pair<LocalDate, Double>>, val koffein: List<Pair<LocalDate, Double>>, val heil: Boolean)

    /** Die Akte je Tag, solange eine Trendseite sichtbar ist ([seiteSichtbar]). */
    private val akteteile = Seitencache<LocalDate, Akteteil>()

    private val eintraege = MutableStateFlow(0)

    /**
     * Zaehlt, was Boulder selbst in die Akte schrieb, waehrend eine Trendseite offen sein kann
     * (ein Glas oder Praeparat von der Uhr). Die Seite liest darauf alles: ein Uhrschub nimmt
     * Wasser und Koffein aus [akteteile] und zeigte das Glas sonst erst nach einer Rueckkehr.
     */
    val eigeneEintraege: StateFlow<Int> = eintraege

    fun eintragGeschrieben() = eintraege.update { it + 1 }

    /**
     * Boulder: eine Trendseite wird sichtbar (ON_START) bzw. geht (ON_STOP oder Abbau). Nur
     * dazwischen halten Akte und [Tagesspeicher] Zwischenstaende - danach lagen sonst 0,5-1 MB
     * bis zum Prozessende im Speicher, und die Rueckkehr liest ohnehin alles neu.
     */
    fun seiteSichtbar() {
        Tagesspeicher.gelesen.oeffne()
        akteteile.oeffne()
    }

    fun seiteWeg() {
        Tagesspeicher.gelesen.schliesse()
        akteteile.schliesse()
    }

    /**
     * Die Akte fuer [lies]: nach einem Uhrschub ([nurUhr]) die gemerkte desselben Tages, sonst
     * neu gelesen. Gemerkt wird nur ein Lesen ohne Fehler ([heil]): ein leeres Ergebnis nach
     * einem Rate-Limit hielte Wasser und Koffein sonst bis zur Rueckkehr leer, statt beim
     * naechsten Schub (~15 min) wieder zu fragen.
     */
    internal suspend fun <T : Any> akteFuer(
        cache: Seitencache<LocalDate, T>, heute: LocalDate, nurUhr: Boolean, merken: Boolean, heil: (T) -> Boolean, lies: suspend () -> T,
    ): T {
        if (nurUhr) cache[heute]?.let { return it }
        return lies().also {
            if (!merken) return@also
            if (heil(it)) cache[heute] = it else cache.vergiss(heute)
        }
    }

    /**
     * [merken] haelt das Ergebnis fuer die Trends fest; die Sicherung liest ohne, sonst blieben
     * nach jeder naechtlichen Sicherung alle Reihen im Speicher, auch wenn niemand die Trends
     * oeffnet. [nurUhr] liest nach einem Uhrschub nur die Datenbank der Uhr neu: Wasser und
     * Koffein kommen aus dem letzten Lesen desselben Tages (2-3 Abfragen an die Akte weniger).
     */
    suspend fun lies(context: Context, api: HealthDataApi, merken: Boolean = true, nurUhr: Boolean = false): Trendstand = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val heute = LocalDate.now()
        val neu = ausDerUhr(api, heute, zone).toMutableMap()
        val akte = akteFuer(akteteile, heute, nurUhr, merken, { it.heil }) { ausDerAkte(context, heute, zone) }
        neu[Spalte.WASSER] = akte.wasser
        neu[Spalte.KOFFEIN] = akte.koffein

        val quoten = SupCycle.quoten(context)
        neu[Spalte.PRAEPARATE_FAELLIG] = quoten.map { (t, q) -> t to q.first.toDouble() }.sortedBy { it.first }
        neu[Spalte.PRAEPARATE_GENOMMEN] = quoten.map { (t, q) -> t to q.second.toDouble() }.sortedBy { it.first }

        // Abgeschlossene Tage der Uhr festschreiben, bevor das Housekeeping sie loescht.
        Tagesspeicher.halte(context, festzuschreiben(neu, heute), merken)
        val eigen = mitArchiv(neu, Tagesspeicher.eigene(context, merken), heute)
        Trendstand(eigen, mitEinschlafen(Tagesspeicher.alle(context, merken))).also { if (merken) zuletzt = it }
    }

    private suspend fun ausDerAkte(context: Context, heute: LocalDate, zone: ZoneId): Akteteil {
        val von = heute.minusDays(TAGE)
        val klient = Akte(context).bereit()
        // Scheitert eine Abfrage (versuche gibt null), steht sie leer da - das darf kein Schub uebernehmen.
        var heil = true
        suspend fun jeTag(metrik: AggregateMetric<*>, wert: (Any) -> Double): List<Pair<LocalDate, Double>> = versuche(TAG, "Akte je Tag") {
            klient?.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    setOf(metrik),
                    TimeRangeFilter.between(von.atStartOfDay(), heute.plusDays(1).atStartOfDay()),
                    Period.ofDays(1),
                )
            ).orEmpty().mapNotNull { g -> g.result[metrik]?.let { g.startTime.toLocalDate() to wert(it) } }.filter { it.second > 0 }
        } ?: emptyList<Pair<LocalDate, Double>>().also { heil = false }
        val wasser = jeTag(HydrationRecord.VOLUME_TOTAL) { (it as androidx.health.connect.client.units.Volume).inMilliliters }
        // Seit wann Boulder selbst Wasser eintraegt (Drinktervall): erst ab da ist ein Tag ohne Kaffee einer mit 0 mg.
        // Als Liste, weil null hier zweierlei hiesse: eine leere Liste ist "kein Glas", null ein Fehler.
        val glasAb = versuche(TAG, "Erstes Glas") {
            listOfNotNull(
                klient?.liesEinen(
                    context, HydrationRecord::class, TimeRangeFilter.between(von.atStartOfDay(), heute.plusDays(1).atStartOfDay()),
                    neuester = false, herkunft = Herkunft.EIGENE,
                )?.startTime?.atZone(zone)?.toLocalDate()
            )
        }.also { if (it == null) heil = false }?.firstOrNull()
        val koffein = koffeinMitNull(
            jeTag(NutritionRecord.CAFFEINE_TOTAL) { (it as androidx.health.connect.client.units.Mass).inMilligrams }, wasser, glasAb, heute,
        )
        return Akteteil(wasser, koffein, heil)
    }
}

/**
 * Boulder: ob das naechste Laden der Trendseite alles liest oder nach einem Uhrschub nur die Uhr.
 * Der Aufbau, jede Rueckkehr ([rueckkehr], ON_RESUME) und jeder eigene Eintrag ([eintraege], etwa
 * ein Glas aus Drinktervall, das nur ueber die Akte kommt) lesen alles. Erst ein fertiges volles
 * Laden gibt die Schuebe frei: bricht ein Schub es ab (neuer Schluessel im LaunchedEffect), liest
 * der naechste wieder alles.
 */
internal class Trendladen(private val eintraege: () -> Int = { Trenddaten.eigeneEintraege.value }) {
    @Volatile
    private var allesFaellig = true

    /** Der Zaehler der eigenen Eintraege beim Beginn des letzten vollen Ladens. */
    @Volatile
    private var gesehen = 0

    fun rueckkehr() {
        allesFaellig = true
    }

    suspend fun <T> lade(lies: suspend (nurUhr: Boolean) -> T): T {
        val stand = eintraege()
        val alles = allesFaellig || stand != gesehen
        return lies(!alles).also {
            if (alles) {
                allesFaellig = false
                gesehen = stand
            }
        }
    }
}
