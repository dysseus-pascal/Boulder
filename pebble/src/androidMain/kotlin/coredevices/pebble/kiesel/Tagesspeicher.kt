package coredevices.pebble.kiesel

import android.content.Context
import co.touchlab.kermit.Logger
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

    /**
     * Die gelesenen Faecher, solange eine Trendseite sichtbar ist ([Seitencache]): sonst parste
     * jeder Uhrschub (~alle 15 min) beide Faecher ganz und [halte] jeden Tag der Uhr noch einmal
     * (2 Jahre Vorgeschichte: ~1'100 JSON-Objekte, 20-60 ms). [ergaenze] macht ein Fach
     * ungueltig, [halte] schreibt seine Aenderung mit hinein.
     */
    internal val gelesen = Seitencache<String, Map<LocalDate, Map<String, Double>>>()

    /** Traegt in die Vorgeschichte nur ein, was fuer den Tag noch fehlt - vorhandene Werte bleiben. */
    fun ergaenze(context: Context, tag: LocalDate, werte: Map<String, Double>) = ergaenze(Prefsfach(context, DATEI), tag, werte)

    /** Dasselbe fuer eigene Werte aus Boulders Sicherung: sie fuellen, was das Fach nicht kennt. */
    fun ergaenzeEigene(context: Context, tag: LocalDate, werte: Map<String, Double>) = ergaenze(Prefsfach(context, EIGENES), tag, werte)

    @Synchronized
    internal fun ergaenze(fach: Fach, tag: LocalDate, werte: Map<String, Double>) {
        if (werte.isEmpty()) return
        fach.ergaenze(tag, werte)
        gelesen.vergiss(fach.name)
    }

    /**
     * Schreibt eigene Werte fest. HIER GEWINNT DAS NEUE: solange die Datenbank
     * einen Tag sicher ganz kennt, ist ihr Wert der richtige - etwa nach einem
     * spaeten Sync. Welche Tage das sind, entscheidet [Trenddaten.festzuschreiben].
     * Geschrieben wird nur, was sich geaendert hat; das laeuft bei jedem Lesen.
     * Ohne [merken] (Hintergrund) bleibt ein noch nicht gelesenes Fach ungelesen.
     */
    fun halte(context: Context, reihen: Reihen, merken: Boolean = true) = halte(Prefsfach(context, EIGENES), reihen, merken)

    @Synchronized
    internal fun halte(fach: Fach, reihen: Reihen, merken: Boolean) {
        val bekannt = if (merken) tage(fach, merken = true) else gelesen[fach.name]
        val neu = zuSchreiben(reihen) { tag -> if (bekannt != null) bekannt[tag] else fach.tag(tag) }
        if (neu.isEmpty()) return
        fach.schreibe(neu)
        // Gleich im Cache nachgetragen: sonst laese das naechste eigene() ein Fach ohne diese Tage.
        if (bekannt != null) gelesen[fach.name] = bekannt + neu
    }

    fun alle(context: Context, merken: Boolean = true): Reihen = alsReihen(tage(Prefsfach(context, DATEI), merken))

    fun eigene(context: Context, merken: Boolean = true): Reihen = alsReihen(tage(Prefsfach(context, EIGENES), merken))

    @Synchronized
    internal fun tage(fach: Fach, merken: Boolean): Map<LocalDate, Map<String, Double>> =
        gelesen[fach.name] ?: fach.alle().also { if (merken) gelesen[fach.name] = it }

    /**
     * Die Tage, die [halte] neu schreibt - je Tag ALLE Spalten, die alten mit den neuen
     * Werten: ein Tag zaehlt, sobald eine Spalte fehlt oder einen anderen Wert hat.
     * [alt] liefert, was fuer einen Tag schon steht.
     */
    internal fun zuSchreiben(reihen: Reihen, alt: (LocalDate) -> Map<String, Double>?): Map<LocalDate, Map<String, Double>> {
        val aus = mutableMapOf<LocalDate, Map<String, Double>>()
        reihen.flatMap { (k, l) -> l.map { (t, w) -> Triple(t, k, w) } }.groupBy { it.first }.forEach { (tag, l) ->
            val vorher = alt(tag).orEmpty()
            val neu = l.filter { (_, k, w) -> vorher[k] != w }
            if (neu.isNotEmpty()) aus[tag] = vorher + neu.associate { it.second to it.third }
        }
        return aus
    }

    /** Reihen je Tag, die Umkehrung von [alsReihen]: so schreibt sie die Sicherung, so sendet sie der Spiegel. */
    internal fun jeTag(reihen: Reihen): Map<LocalDate, Map<String, Double>> =
        reihen.flatMap { (spalte, l) -> l.map { (tag, w) -> Triple(tag, spalte, w) } }
            .groupBy { it.first }.mapValues { (_, l) -> l.associate { it.second to it.third } }

    /** Ein Fach als Reihen: je Spalte die Tage mit Wert, aufsteigend. */
    internal fun alsReihen(tage: Map<LocalDate, Map<String, Double>>): Reihen {
        val aus = mutableMapOf<String, MutableList<Pair<LocalDate, Double>>>()
        tage.forEach { (tag, werte) -> werte.forEach { (spalte, w) -> aus.getOrPut(spalte) { mutableListOf() } += tag to w } }
        return aus.mapValues { (_, l) -> l.sortedBy { it.first } }
    }
}

/**
 * Ein Fach des [Tagesspeicher]s: Tage mit ihren Werten je Spalte. Eigens, damit die Regeln
 * des Caches ohne Android pruefbar sind - in den Hosttests ist JSONObject nur ein Stummel.
 */
internal interface Fach {
    val name: String

    /** Alle Tage, ganz geparst - das Teure, das [Tagesspeicher.gelesen] spart. */
    fun alle(): Map<LocalDate, Map<String, Double>>

    fun tag(tag: LocalDate): Map<String, Double>?

    /** Ersetzt je Tag alle Werte. */
    fun schreibe(tage: Map<LocalDate, Map<String, Double>>)

    /** Traegt fuer [tag] nur ein, was noch fehlt. */
    fun ergaenze(tag: LocalDate, werte: Map<String, Double>)
}

/** Das Fach als SharedPreferences: je Tag ("2026-10-03") ein JSON-Objekt mit den Spalten. */
private class Prefsfach(context: Context, override val name: String) : Fach {
    private val p = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun alle(): Map<LocalDate, Map<String, Double>> = buildMap {
        p.all.forEach { (k, v) ->
            val tag = tagOderNull(k, "Trends") ?: return@forEach
            ausJson(v as? String ?: return@forEach)?.let { put(tag, it) }
        }
    }

    override fun tag(tag: LocalDate): Map<String, Double>? = p.getString(tag.toString(), null)?.let(::ausJson)

    override fun schreibe(tage: Map<LocalDate, Map<String, Double>>) {
        val e = p.edit()
        tage.forEach { (tag, werte) -> e.putString(tag.toString(), JSONObject().apply { werte.forEach { (k, v) -> put(k, v) } }.toString()) }
        e.apply()
    }

    override fun ergaenze(tag: LocalDate, werte: Map<String, Double>) {
        val alt = p.getString(tag.toString(), null)?.let { versuche("Trends", "Tag $tag unlesbar") { JSONObject(it) } } ?: JSONObject()
        werte.forEach { (k, v) -> if (!alt.has(k)) alt.put(k, v) }
        p.edit().putString(tag.toString(), alt.toString()).apply()
    }

    private fun ausJson(text: String): Map<String, Double>? = versuche("Trends", "Tag unlesbar") {
        val o = JSONObject(text)
        o.keys().asSequence().associateWith { o.getDouble(it) }
    }
}

/**
 * Boulder: ein Zwischenspeicher, der nur haelt, solange eine Trendseite sichtbar ist - von
 * [oeffne] (ON_START) bis [schliesse] (ON_STOP oder Abbau). Der Prozess lebt mit dem
 * Vordergrunddienst tagelang, und ein Fach mit 2 Jahren Vorgeschichte haelt geboxt 0,5-1 MB;
 * die Rueckkehr liest ohnehin alles neu (ON_RESUME). Ein Lesen, das erst nach dem Schliessen
 * endet, bleibt deshalb nicht liegen. Gezaehlt, weil sich beim Seitenwechsel zwei ueberlappen.
 */
internal class Seitencache<K : Any, V : Any> {
    private val werte = HashMap<K, V>()
    private var offen = 0

    @Synchronized
    fun oeffne() {
        offen++
    }

    /** Geht die letzte sichtbare Seite, ist alles vergessen. */
    @Synchronized
    fun schliesse() {
        offen = maxOf(0, offen - 1)
        if (offen == 0) werte.clear()
    }

    @Synchronized
    operator fun get(k: K): V? = werte[k]

    /** Haelt [v] nur bei sichtbarer Seite. */
    @Synchronized
    operator fun set(k: K, v: V) {
        if (offen > 0) werte[k] = v
    }

    @Synchronized
    fun vergiss(k: K) {
        werte.remove(k)
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
            Logger.withTag("Trends").w { "Tagesarchiv: " + e.message }
            Result.retry()
        }
    }
}
