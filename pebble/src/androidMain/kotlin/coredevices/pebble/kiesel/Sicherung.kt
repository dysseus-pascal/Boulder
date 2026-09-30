package coredevices.pebble.kiesel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import coredevices.pebble.R
import io.rebble.libpebblecommon.connection.HealthDataApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.koin.mp.KoinPlatform
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Das Sicherungsformat - dasselbe wie in Kiesel-Helper (Sicherung), damit
 * Boulder dessen Sicherung lesen kann und eine eigene dieselbe Form hat:
 * `{fassung, erzeugt, app, tage:[{datum, spalte: wert}], einstellungen, spuren:[beginn]}`.
 */
internal object Sicherungsformat {
    const val FASSUNG = 1
    const val KIESEL_HELPER = "kiesel-helper.json"
    const val BOULDER = "boulder.json"
    const val SPURORDNER = "spuren"
    fun spurname(beginn: Long) = "spur-$beginn.jsonl"

    /** Die Spalten aus Kiesel-Helpers Tagesspeicher - nur sie werden gelesen. */
    val SPALTEN = setOf(
        "schritte", "distanz", "kalorien", "aktiv", "wasser",
        "schlaf", "tief", "rem", "leicht", "wach",
        "ruhepuls", "puls_min", "puls_hoch", "puls_tief", "hrv",
        "supp_faellig", "supp_genommen",
        "schlaf_von", "schlaf_bis", "schlaf_mitte",
        "koffein_mg", "koffein_letzt",
        "puls_nacht_sd",
    )

    data class Stand(val fassung: Int, val erzeugt: String, val tage: List<Pair<LocalDate, Map<String, Double>>>, val spuren: List<Long>)

    fun alsJson(tage: List<Pair<LocalDate, Map<String, Double>>>, spuren: List<Long>, erzeugt: String): String = buildJsonObject {
        put("fassung", FASSUNG)
        put("erzeugt", erzeugt)
        put("app", "Boulder")
        put("tage", buildJsonArray {
            tage.sortedBy { it.first }.forEach { (tag, werte) ->
                add(buildJsonObject {
                    put("datum", tag.toString())
                    werte.toSortedMap().forEach { (k, v) -> put(k, v) }
                })
            }
        })
        put("einstellungen", buildJsonObject {})
        put("spuren", buildJsonArray { spuren.sorted().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
    }.toString()

    fun ausJson(text: String?): Stand? {
        if (text.isNullOrBlank()) return null
        return try {
            val w = Json.parseToJsonElement(text).jsonObject
            val fassung = w["fassung"]?.jsonPrimitive?.int ?: return null
            if (fassung <= 0) return null
            val tage = w["tage"]?.jsonArray.orEmpty().mapNotNull { e ->
                val z = e as? JsonObject ?: return@mapNotNull null
                val tag = runCatching { LocalDate.parse(z["datum"]?.jsonPrimitive?.content) }.getOrNull() ?: return@mapNotNull null
                val werte = SPALTEN.mapNotNull { s -> z[s]?.jsonPrimitive?.doubleOrNull?.takeIf { !it.isNaN() }?.let { s to it } }.toMap()
                if (werte.isEmpty()) null else tag to werte
            }
            val spuren = w["spuren"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.longOrNull?.takeIf { v -> v > 0 } }
            Stand(fassung, w["erzeugt"]?.jsonPrimitive?.content.orEmpty(), tage, spuren)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Kiesel-Helpers Spalten, wie Boulder sie liest: der geschaetzte Ruhepuls
     * (puls_min) gilt nur, wo kein gemessener dasteht - wie dort in den Trends.
     */
    fun fuerBoulder(werte: Map<String, Double>): Map<String, Double> {
        val aus = werte.toMutableMap()
        if ("ruhepuls" !in aus) werte["puls_min"]?.let { aus["ruhepuls"] = it }
        return aus
    }
}

/** Ein Ordner, den man Boulder einmal freigegeben hat (Storage Access Framework). */
internal class Ordner(private val context: Context, private val baum: Uri) {
    val name: String get() = DocumentFile.fromTreeUri(context, baum)?.name ?: baum.lastPathSegment.orEmpty()

    private fun wurzel(): DocumentFile? = DocumentFile.fromTreeUri(context, baum)?.takeIf { it.exists() && it.isDirectory }

    private fun zerlege(pfad: String, anlegen: Boolean): Pair<DocumentFile, String>? {
        var d = wurzel() ?: return null
        val teile = pfad.trim('/').split('/')
        for (t in teile.dropLast(1)) {
            d = d.findFile(t)?.takeIf { it.isDirectory } ?: (if (anlegen) d.createDirectory(t) else null) ?: return null
        }
        return d to teile.last()
    }

    fun lege(pfad: String, inhalt: String, typ: String = "application/json"): Boolean = try {
        val (d, datei) = zerlege(pfad, anlegen = true) ?: error("folder not reachable")
        val ziel = d.findFile(datei)?.takeIf { it.isFile } ?: d.createFile(typ, datei) ?: error("cannot create $datei")
        context.contentResolver.openOutputStream(ziel.uri, "wt")?.use { it.write(inhalt.toByteArray()) } ?: error("cannot write $datei")
        true
    } catch (e: Exception) {
        Log.w("Sicherung", "Schreiben: " + e.message)
        false
    }

    fun hole(pfad: String): String? = try {
        val (d, datei) = zerlege(pfad, anlegen = false) ?: error("missing")
        val quelle = d.findFile(datei)?.takeIf { it.isFile } ?: error("missing")
        context.contentResolver.openInputStream(quelle.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        null
    }

    val erreichbar: Boolean get() = wurzel()?.canWrite() == true
}

/**
 * Sichern und Zurueckholen - wie Sichern in Kiesel-Helper.
 *
 * GESICHERT WIRD, WAS SICH NICHT NEU RECHNEN LAESST: die Werte je Tag (aus
 * Uhr, Akte und Boulder selbst - so ueberleben sie auch einen Verlust der
 * Datenbank) und die GPS-Spuren. Zurueckgeholt wird nur, was fehlt.
 */
internal object Sichern {
    private const val DATEI = "kiesel-sicherung"
    private const val ARBEIT = "boulder-sicherung"

    fun ordnerUri(context: Context): Uri? =
        context.getSharedPreferences(DATEI, Context.MODE_PRIVATE).getString("ordner", null)?.let(Uri::parse)

    fun setzeOrdner(context: Context, uri: Uri) {
        context.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit().putString("ordner", uri.toString()).apply()
    }

    fun ordner(context: Context): Ordner? = ordnerUri(context)?.let { Ordner(context, it) }

    fun zuletzt(context: Context): Long = context.getSharedPreferences(DATEI, Context.MODE_PRIVATE).getLong("zuletzt", 0)

    fun taeglich(context: Context): Boolean = context.getSharedPreferences(DATEI, Context.MODE_PRIVATE).getBoolean("taeglich", false)

    fun setzeTaeglich(context: Context, an: Boolean) {
        context.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit().putBoolean("taeglich", an).apply()
        val wm = WorkManager.getInstance(context)
        if (an) {
            wm.enqueueUniquePeriodicWork(
                ARBEIT, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<Arbeit>(Duration.ofDays(1)).setInitialDelay(Duration.ofHours(12)).build(),
            )
        } else {
            wm.cancelUniqueWork(ARBEIT)
        }
    }

    suspend fun jetzt(context: Context, api: HealthDataApi): String = withContext(Dispatchers.IO) {
        val o = ordner(context)?.takeIf { it.erreichbar } ?: return@withContext context.getString(R.string.ks_b_kein_ordner)
        val reihen = Trenddaten.lies(context, api)
        val tage = reihen.flatMap { (spalte, l) -> l.map { (tag, w) -> Triple(tag, spalte, w) } }
            .groupBy { it.first }.map { (tag, l) -> tag to l.associate { it.second to it.third } }
        val spuren = Spur.alle(context)
        if (!o.lege(Sicherungsformat.BOULDER, Sicherungsformat.alsJson(tage, spuren, Instant.now().toString()))) {
            return@withContext context.getString(R.string.ks_b_fehlgeschlagen)
        }
        val p = context.getSharedPreferences(DATEI, Context.MODE_PRIVATE)
        // JE ORDNER: nach einem Ordnerwechsel gehoeren die alten Spuren auch in den neuen.
        val schluessel = "spuren@" + ordnerUri(context)
        // Vor 0.25.0 galt eine Liste fuer jeden Ordner: sie zaehlt einmal fuer den jetzigen.
        val oben = (p.getStringSet(schluessel, null) ?: p.getStringSet("spuren", emptySet())).orEmpty().toMutableSet()
        var neu = 0
        spuren.filter { it.toString() !in oben }.forEach { b ->
            val inhalt = Spur.roh(context, b) ?: return@forEach
            if (o.lege(Sicherungsformat.SPURORDNER + "/" + Sicherungsformat.spurname(b), inhalt, "application/x-ndjson")) {
                oben += b.toString()
                neu++
            }
        }
        p.edit().putStringSet(schluessel, oben).putLong("zuletzt", System.currentTimeMillis()).apply()
        context.getString(R.string.ks_b_gesichert, tage.size, neu)
    }

    /**
     * Holt aus dem Ordner, was hier fehlt: aus Boulders eigener Sicherung und
     * aus der von Kiesel-Helper. Tage nur, wo Boulder den Wert nicht kennt;
     * Spuren nur, wo keine liegt.
     */
    suspend fun zurueck(context: Context): String = withContext(Dispatchers.IO) {
        val o = ordner(context) ?: return@withContext context.getString(R.string.ks_b_kein_ordner)
        val staende = listOf(Sicherungsformat.BOULDER, Sicherungsformat.KIESEL_HELPER).mapNotNull { Sicherungsformat.ausJson(o.hole(it)) }
        if (staende.isEmpty()) return@withContext context.getString(R.string.ks_b_keine_sicherung)
        var tage = 0
        var spuren = 0
        staende.forEach { s ->
            s.tage.forEach { (tag, werte) ->
                Tagesspeicher.ergaenze(context, tag, Sicherungsformat.fuerBoulder(werte))
                tage++
            }
            s.spuren.forEach { b ->
                if (Spur.vorhanden(context, b)) return@forEach
                val inhalt = o.hole(Sicherungsformat.SPURORDNER + "/" + Sicherungsformat.spurname(b)) ?: return@forEach
                if (Spur.schreibeRoh(context, b, inhalt)) spuren++
            }
        }
        context.getString(R.string.ks_b_zurueck, tage, spuren)
    }

    class Arbeit(context: Context, gaben: WorkerParameters) : CoroutineWorker(context, gaben) {
        override suspend fun doWork(): Result = try {
            val meldung = if (ordner(applicationContext) != null) jetzt(applicationContext, KoinPlatform.getKoin().get()) else null
            // Ein Fehlschlag (Ordner weg, Schreiben verweigert) ist keiner Erfolg: spaeter noch einmal.
            val fehl = setOf(R.string.ks_b_kein_ordner, R.string.ks_b_fehlgeschlagen).map { applicationContext.getString(it) }
            if (meldung in fehl) Result.retry() else Result.success()
        } catch (e: Exception) {
            Log.w("Sicherung", "Taeglich: " + e.message)
            Result.retry()
        }
    }
}
