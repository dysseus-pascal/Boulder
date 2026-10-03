package coredevices.pebble.kiesel

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import org.koin.mp.KoinPlatform
import java.time.Duration
import java.time.LocalDate

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
