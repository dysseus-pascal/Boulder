package coredevices.pebble.kiesel

import android.content.Context
import android.util.Log
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.time.TimeRangeFilter
import io.rebble.libpebblecommon.connection.HealthDataApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

/** Je Spalte die Tage mit Wert - was der Trend-Schirm braucht. */
internal typealias Reihen = Map<String, List<Pair<LocalDate, Double>>>

/**
 * Werte je Tag, die Boulder selbst verwahrt - vor allem die aus Kiesel-Helper
 * uebernommene Vorgeschichte. Was sich aus Uhr und Akte neu rechnen laesst,
 * geht diesen Werten vor; sie fuellen nur die Luecken davor.
 */
internal object Tagesspeicher {
    private const val DATEI = "kiesel-tagesspeicher"

    /** Traegt nur ein, was fuer den Tag noch fehlt - vorhandene Werte bleiben. */
    fun ergaenze(context: Context, tag: LocalDate, werte: Map<String, Double>) {
        val p = context.getSharedPreferences(DATEI, Context.MODE_PRIVATE)
        val alt = p.getString(tag.toString(), null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        werte.forEach { (k, v) -> if (!alt.has(k)) alt.put(k, v) }
        p.edit().putString(tag.toString(), alt.toString()).apply()
    }

    fun alle(context: Context): Reihen {
        val aus = mutableMapOf<String, MutableList<Pair<LocalDate, Double>>>()
        context.getSharedPreferences(DATEI, Context.MODE_PRIVATE).all.forEach { (k, v) ->
            val tag = runCatching { LocalDate.parse(k) }.getOrNull() ?: return@forEach
            val o = runCatching { JSONObject(v as String) }.getOrNull() ?: return@forEach
            o.keys().forEach { spalte -> aus.getOrPut(spalte) { mutableListOf() } += tag to o.getDouble(spalte) }
        }
        return aus.mapValues { (_, l) -> l.sortedBy { it.first } }
    }
}

internal object Trenddaten {
    private const val TAG = "Trends"
    const val TAGE = 180L

    /** Neu gerechnet gewinnt; der Speicher fuellt nur Tage, die neu nichts wissen. */
    fun vereine(neu: Reihen, gespeichert: Reihen): Reihen =
        (neu.keys + gespeichert.keys).associateWith { k ->
            val n = neu[k].orEmpty().toMap()
            (gespeichert[k].orEmpty().filter { it.first !in n } + n.toList()).sortedBy { it.first }
        }

    /** Die Mitte zwischen Einschlafen und Aufwachen, in Minuten ab 18 Uhr des Vortags. */
    fun schlafMitte(einschlafen: Instant, aufwachen: Instant, zone: ZoneId): Double {
        val morgen = aufwachen.atZone(zone).toLocalDate()
        val bezug = morgen.minusDays(1).atTime(18, 0).atZone(zone).toInstant()
        return ((einschlafen.epochSecond + aufwachen.epochSecond) / 2 - bezug.epochSecond) / 60.0
    }

    suspend fun lies(context: Context, api: HealthDataApi): Reihen = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val heute = LocalDate.now()
        val von = heute.minusDays(TAGE)
        fun anfang(t: LocalDate) = t.atStartOfDay(zone).toEpochSecond()
        val neu = mutableMapOf<String, List<Pair<LocalDate, Double>>>()

        val bewegung = runCatching { api.getDailyAggregates(anfang(von), anfang(heute.plusDays(1))) }.getOrDefault(emptyList())
        neu["schritte"] = bewegung.mapNotNull { a -> a.steps?.takeIf { it > 0 }?.let { LocalDate.parse(a.day) to it.toDouble() } }
        neu["aktiv"] = bewegung.mapNotNull { a -> a.activeMinutes?.let { LocalDate.parse(a.day) to it.toDouble() } }

        val naechte = runCatching { api.getNaechte(anfang(von), anfang(heute.plusDays(1))) }.getOrDefault(emptyList())
        val schlaf = mutableListOf<Pair<LocalDate, Double>>()
        val tief = mutableListOf<Pair<LocalDate, Double>>()
        val mitte = mutableListOf<Pair<LocalDate, Double>>()
        val ruhe = mutableListOf<Pair<LocalDate, Double>>()
        val hrv = mutableListOf<Pair<LocalDate, Double>>()
        naechte.forEach { e ->
            val tag = Gesundheitsrechnung.nachtTag(e.beginnUtc, zone)
            if (e.schlafMinuten > 0) schlaf += tag to e.schlafMinuten.toDouble()
            val phasen = Gesundheitsrechnung.phasenMinuten(runCatching { api.getNachtAbschnitte(e.beginnUtc) }.getOrDefault(emptyList()))
            if (phasen[1] + phasen[2] + phasen[3] > 0) tief += tag to phasen[2].toDouble()
            if (e.aufwachen > e.einschlafen) {
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
        neu["wasser"] = jeTag(HydrationRecord.VOLUME_TOTAL) { (it as androidx.health.connect.client.units.Volume).inMilliliters }
        neu["koffein_mg"] = jeTag(NutritionRecord.CAFFEINE_TOTAL) { (it as androidx.health.connect.client.units.Mass).inMilligrams }

        val quoten = SupCycle.quoten(context)
        neu["supp_faellig"] = quoten.map { (t, q) -> t to q.first.toDouble() }.sortedBy { it.first }
        neu["supp_genommen"] = quoten.map { (t, q) -> t to q.second.toDouble() }.sortedBy { it.first }
        neu["energie"] = (0..TAGE.toInt()).mapNotNull { d ->
            val t = heute.minusDays(d.toLong())
            Gesundheitsdaten.energie(context, t)?.let { t to it.toDouble() }
        }.sortedBy { it.first }

        vereine(neu, Tagesspeicher.alle(context))
    }
}
