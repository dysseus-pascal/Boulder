package coredevices.pebble.kiesel

import android.content.Context
import android.util.Log
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.rebble.libpebblecommon.connection.HealthDataApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koin.mp.KoinPlatform
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

/** Je Spalte die Tage mit Wert - was der Trend-Schirm braucht. */
internal typealias Reihen = Map<String, List<Pair<LocalDate, Double>>>

/**
 * Werte je Tag, die Boulder selbst verwahrt - in zwei Faechern.
 *
 * DIE VORGESCHICHTE: was aus einer Sicherung zurueckgeholt wurde, vor allem
 * aus Kiesel-Helper. Sie fuellt nur die Zeit vor Boulders eigenen Werten
 * (siehe [Trenddaten.mitVorgeschichte]).
 *
 * DAS EIGENE: Boulders Tageswerte aus der Uhr, festgeschrieben, solange die
 * Datenbank sie noch kennt. Das Housekeeping loescht Minutendaten nach 90
 * Tagen; ohne dieses Fach verschwaenden die Schritte vom Juli im Oktober aus
 * Trends und Sicherung - endgueltig, weil die Sicherung jeden Tag neu
 * geschrieben wird.
 */
internal object Tagesspeicher {
    private const val DATEI = "kiesel-tagesspeicher"
    private const val EIGENES = "kiesel-tagesarchiv"

    /** Traegt in die Vorgeschichte nur ein, was fuer den Tag noch fehlt - vorhandene Werte bleiben. */
    fun ergaenze(context: Context, tag: LocalDate, werte: Map<String, Double>) = ergaenze(context, DATEI, tag, werte)

    /** Dasselbe fuer eigene Werte aus Boulders Sicherung: sie fuellen, was das Fach nicht kennt. */
    fun ergaenzeEigene(context: Context, tag: LocalDate, werte: Map<String, Double>) = ergaenze(context, EIGENES, tag, werte)

    private fun ergaenze(context: Context, datei: String, tag: LocalDate, werte: Map<String, Double>) {
        if (werte.isEmpty()) return
        val p = context.getSharedPreferences(datei, Context.MODE_PRIVATE)
        val alt = p.getString(tag.toString(), null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        werte.forEach { (k, v) -> if (!alt.has(k)) alt.put(k, v) }
        p.edit().putString(tag.toString(), alt.toString()).apply()
    }

    /**
     * Schreibt eigene Werte fest. HIER GEWINNT DAS NEUE: solange die Datenbank
     * einen Tag sicher ganz kennt, ist ihr Wert der richtige - etwa nach einem
     * spaeten Sync. Welche Tage das sind, entscheidet [Trenddaten.festzuschreiben].
     * Geschrieben wird nur, was sich geaendert hat; das laeuft bei jedem Lesen.
     */
    fun halte(context: Context, reihen: Reihen) {
        val p = context.getSharedPreferences(EIGENES, Context.MODE_PRIVATE)
        val e = p.edit()
        var geaendert = false
        reihen.flatMap { (k, l) -> l.map { (t, w) -> Triple(t, k, w) } }.groupBy { it.first }.forEach { (tag, l) ->
            val alt = p.getString(tag.toString(), null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
            val neu = l.filter { (_, k, w) -> !alt.has(k) || alt.optDouble(k) != w }
            if (neu.isEmpty()) return@forEach
            neu.forEach { (_, k, w) -> alt.put(k, w) }
            e.putString(tag.toString(), alt.toString())
            geaendert = true
        }
        if (geaendert) e.apply()
    }

    fun alle(context: Context): Reihen = lies(context, DATEI)

    fun eigene(context: Context): Reihen = lies(context, EIGENES)

    private fun lies(context: Context, datei: String): Reihen {
        val aus = mutableMapOf<String, MutableList<Pair<LocalDate, Double>>>()
        context.getSharedPreferences(datei, Context.MODE_PRIVATE).all.forEach { (k, v) ->
            val tag = runCatching { LocalDate.parse(k) }.getOrNull() ?: return@forEach
            val o = runCatching { JSONObject(v as String) }.getOrNull() ?: return@forEach
            o.keys().forEach { spalte -> aus.getOrPut(spalte) { mutableListOf() } += tag to o.getDouble(spalte) }
        }
        return aus.mapValues { (_, l) -> l.sortedBy { it.first } }
    }
}

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
    val UHRREIHEN = setOf("schritte", "aktiv", "schlaf", "tief", "schlaf_mitte", "ruhepuls", "hrv", "einschlafen")

    /**
     * Kiesel-Helper mass hier etwas anderes (die Trainingsdauer) - diese Reihen
     * zeigen die Trends nie aus der Vorgeschichte. Gespeichert bleiben sie.
     */
    val OHNE_VORGESCHICHTE = setOf("aktiv")

    /**
     * Die Reihen aus den Minutendaten (health_data). Nur sie loescht das
     * Housekeeping - die Naechte der Firmware (boulder_nacht) bleiben stehen.
     */
    val MINUTENREIHEN = setOf("schritte", "aktiv")

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
        val da = vorgeschichte["einschlafen"].orEmpty()
        val tage = da.map { it.first }.toSet()
        val dazu = von.filter { it.first !in tage }
        if (dazu.isEmpty()) return vorgeschichte
        return vorgeschichte + ("einschlafen" to (da + dazu).sortedBy { it.first })
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

    /** Schreibt die Uhrreihen fest, ohne die Trends zu rechnen - fuer den taeglichen Lauf ([Tagesarchiv]). */
    suspend fun halteFest(context: Context, api: HealthDataApi) = withContext(Dispatchers.IO) {
        val heute = LocalDate.now()
        Tagesspeicher.halte(context, festzuschreiben(ausDerUhr(api, heute, ZoneId.systemDefault()), heute))
    }

    /**
     * Die Reihen aus der Datenbank der Uhr: die Bewegung bis zur Sync-Grenze
     * und die Naechte. Eigens, weil [Tagesarchiv] taeglich nur sie festschreibt.
     */
    private suspend fun ausDerUhr(api: HealthDataApi, heute: LocalDate, zone: ZoneId): Map<String, List<Pair<LocalDate, Double>>> {
        val von = heute.minusDays(TAGE)
        fun anfang(t: LocalDate) = t.atStartOfDay(zone).toEpochSecond()
        val neu = mutableMapOf<String, List<Pair<LocalDate, Double>>>()

        val letzte = runCatching { api.getLatestTimestamp() }.getOrNull()?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDate() }
        val voll = uhrGrenze(heute, letzte)
        val bewegung = runCatching { api.getDailyAggregates(anfang(von), anfang(heute.plusDays(1))) }.getOrDefault(emptyList())
            .filter { LocalDate.parse(it.day) < voll }
        neu["schritte"] = bewegung.mapNotNull { a -> a.steps?.takeIf { it > 0 }?.let { LocalDate.parse(a.day) to it.toDouble() } }
        // Aktivminuten nur an Tagen mit Schritten: eine Uhr am Ladekabel meldet 0, und das ist kein ruhiger Tag.
        neu["aktiv"] = bewegung.filter { (it.steps ?: 0) > 0 }.mapNotNull { a -> a.activeMinutes?.let { LocalDate.parse(a.day) to it.toDouble() } }

        val naechte = Gesundheitsrechnung.jeTag(
            runCatching { api.getNaechte(anfang(von), anfang(heute.plusDays(1))) }.getOrDefault(emptyList()), zone,
        )
        val schlaf = mutableListOf<Pair<LocalDate, Double>>()
        val tief = mutableListOf<Pair<LocalDate, Double>>()
        val mitte = mutableListOf<Pair<LocalDate, Double>>()
        val ruhe = mutableListOf<Pair<LocalDate, Double>>()
        val hrv = mutableListOf<Pair<LocalDate, Double>>()
        val ein = mutableListOf<Pair<LocalDate, Double>>()
        naechte.forEach { e ->
            val tag = Gesundheitsrechnung.nachtTag(e.beginnUtc, e.aufwachen, zone)
            if (e.schlafMinuten > 0) schlaf += tag to e.schlafMinuten.toDouble()
            val phasen = Gesundheitsrechnung.phasenMinuten(runCatching { api.getNachtAbschnitte(e.beginnUtc) }.getOrDefault(emptyList()))
            if (phasen[1] + phasen[2] + phasen[3] > 0) tief += tag to phasen[2].toDouble()
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
        neu["schlaf"] = schlaf
        neu["tief"] = tief
        neu["schlaf_mitte"] = mitte
        neu["ruhepuls"] = ruhe
        neu["hrv"] = hrv
        neu["einschlafen"] = ein
        return neu
    }

    suspend fun lies(context: Context, api: HealthDataApi): Trendstand = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val heute = LocalDate.now()
        val von = heute.minusDays(TAGE)
        val neu = ausDerUhr(api, heute, zone).toMutableMap()

        val klient = Akte(context).bereit()
        suspend fun jeTag(metrik: AggregateMetric<*>, wert: (Any) -> Double): List<Pair<LocalDate, Double>> = try {
            klient?.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    setOf(metrik),
                    TimeRangeFilter.between(von.atStartOfDay(), heute.plusDays(1).atStartOfDay()),
                    Period.ofDays(1),
                )
            ).orEmpty().mapNotNull { g -> g.result[metrik]?.let { g.startTime.toLocalDate() to wert(it) } }.filter { it.second > 0 }
        } catch (e: Exception) {
            Log.w(TAG, "Akte je Tag: " + e.message)
            emptyList()
        }
        val wasser = jeTag(HydrationRecord.VOLUME_TOTAL) { (it as androidx.health.connect.client.units.Volume).inMilliliters }
        neu["wasser"] = wasser
        // Seit wann Boulder selbst Wasser eintraegt (Drinktervall): erst ab da ist ein Tag ohne Kaffee einer mit 0 mg.
        val glasAb = try {
            klient?.readRecords(
                ReadRecordsRequest(
                    HydrationRecord::class,
                    TimeRangeFilter.between(von.atStartOfDay(), heute.plusDays(1).atStartOfDay()),
                    dataOriginFilter = setOf(DataOrigin(context.packageName)),
                    ascendingOrder = true,
                    pageSize = 1,
                )
            )?.records?.firstOrNull()?.startTime?.atZone(zone)?.toLocalDate()
        } catch (e: Exception) {
            Log.w(TAG, "Erstes Glas: " + e.message)
            null
        }
        neu["koffein_mg"] = koffeinMitNull(
            jeTag(NutritionRecord.CAFFEINE_TOTAL) { (it as androidx.health.connect.client.units.Mass).inMilligrams }, wasser, glasAb, heute,
        )

        val quoten = SupCycle.quoten(context)
        neu["supp_faellig"] = quoten.map { (t, q) -> t to q.first.toDouble() }.sortedBy { it.first }
        neu["supp_genommen"] = quoten.map { (t, q) -> t to q.second.toDouble() }.sortedBy { it.first }

        // Abgeschlossene Tage der Uhr festschreiben, bevor das Housekeeping sie loescht.
        Tagesspeicher.halte(context, festzuschreiben(neu, heute))
        val eigen = mitArchiv(neu, Tagesspeicher.eigene(context), heute)
        Trendstand(eigen, mitEinschlafen(Tagesspeicher.alle(context)))
    }
}

/**
 * Schreibt die Uhrreihen einmal am Tag fest - auch ohne Sicherungsordner und
 * ohne dass jemand die Trends oeffnet. Sonst verlor, wer beides 90 Tage
 * liess, seine Schritte wie zuvor.
 */
internal object Tagesarchiv {
    private const val ARBEIT = "boulder-tagesarchiv"

    /** Bei jedem Start; KEEP laesst einen schon geplanten Lauf stehen. */
    fun plane(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ARBEIT, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<Arbeit>(Duration.ofDays(1)).build(),
        )
    }

    class Arbeit(context: Context, gaben: WorkerParameters) : CoroutineWorker(context, gaben) {
        override suspend fun doWork(): Result = try {
            Trenddaten.halteFest(applicationContext, KoinPlatform.getKoin().get())
            Result.success()
        } catch (e: Exception) {
            Log.w("Trends", "Tagesarchiv: " + e.message)
            Result.retry()
        }
    }
}
