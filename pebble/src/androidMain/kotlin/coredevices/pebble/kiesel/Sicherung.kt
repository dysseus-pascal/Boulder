package coredevices.pebble.kiesel

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import co.touchlab.kermit.Logger
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
 *
 * AB FASSUNG 2 stehen in Boulders `tage` nur eigene Werte; die zurueckgeholte
 * Vorgeschichte (Kiesel-Helper) steht getrennt in `vorgeschichte`. Bis
 * Fassung 1 war beides vermischt - eine solche Datei gilt beim Zurueckholen
 * ganz als Vorgeschichte.
 */
internal object Sicherungsformat {
    const val FASSUNG = 2
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
        // Boulders eigene Spalte fuer "spaet ins Bett"; sie wurde gesichert, aber nie zurueckgeholt.
        "einschlafen",
    )

    data class Stand(
        val fassung: Int,
        val erzeugt: String,
        val tage: List<Pair<LocalDate, Map<String, Double>>>,
        val spuren: List<Long>,
        val vorgeschichte: List<Pair<LocalDate, Map<String, Double>>> = emptyList(),
    )

    fun alsJson(
        tage: List<Pair<LocalDate, Map<String, Double>>>,
        spuren: List<Long>,
        erzeugt: String,
        vorgeschichte: List<Pair<LocalDate, Map<String, Double>>> = emptyList(),
    ): String = buildJsonObject {
        fun tageJson(l: List<Pair<LocalDate, Map<String, Double>>>) = buildJsonArray {
            l.sortedBy { it.first }.forEach { (tag, werte) ->
                add(buildJsonObject {
                    put("datum", tag.toString())
                    werte.toSortedMap().forEach { (k, v) -> put(k, v) }
                })
            }
        }
        put("fassung", FASSUNG)
        put("erzeugt", erzeugt)
        put("app", "Boulder")
        put("tage", tageJson(tage))
        put("vorgeschichte", tageJson(vorgeschichte))
        put("einstellungen", buildJsonObject {})
        put("spuren", buildJsonArray { spuren.sorted().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
    }.toString()

    fun ausJson(text: String?): Stand? {
        if (text.isNullOrBlank()) return null
        return try {
            val w = Json.parseToJsonElement(text).jsonObject
            val fassung = w["fassung"]?.jsonPrimitive?.int ?: return null
            if (fassung <= 0) return null
            fun tage(feld: String) = w[feld]?.jsonArray.orEmpty().mapNotNull { e ->
                val z = e as? JsonObject ?: return@mapNotNull null
                val tag = tagOderNull(z["datum"]?.jsonPrimitive?.content, "Sicherung") ?: return@mapNotNull null
                val werte = SPALTEN.mapNotNull { s -> z[s]?.jsonPrimitive?.doubleOrNull?.takeIf { !it.isNaN() }?.let { s to it } }.toMap()
                if (werte.isEmpty()) null else tag to werte
            }
            val spuren = w["spuren"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.longOrNull?.takeIf { v -> v > 0 } }
            Stand(fassung, w["erzeugt"]?.jsonPrimitive?.content.orEmpty(), tage("tage"), spuren, tage("vorgeschichte"))
        } catch (e: Exception) {
            Logger.withTag("Sicherung").w { "Sicherung nicht lesbar: " + e.message }
            null
        }
    }

    /**
     * Kiesel-Helpers Spalten, wie Boulder sie liest: der geschaetzte Ruhepuls
     * (puls_min) gilt nur, wo kein gemessener dasteht - wie dort in den Trends.
     * Der Beginn der Nacht (schlaf_von, Minuten ab 18 Uhr) ist Boulders
     * "einschlafen". "aktiv" bleibt gespeichert, die Trends blenden es aus
     * ([Trenddaten.OHNE_VORGESCHICHTE]): in Kiesel-Helper war es die
     * Trainingsdauer, aber eine Boulder-Sicherung der Fassung 1 traegt darunter
     * auch Boulders eigene Aktivminuten - gestrichen waeren sie verloren.
     */
    fun fuerBoulder(werte: Map<String, Double>): Map<String, Double> {
        val aus = werte.toMutableMap()
        if ("ruhepuls" !in aus) werte["puls_min"]?.let { aus["ruhepuls"] = it }
        if ("einschlafen" !in aus) werte["schlaf_von"]?.let { aus["einschlafen"] = it }
        return aus
    }
}

/** Wohin die Sicherung schreibt: der freigegebene [Ordner], im Test ein Nachbau. */
internal interface Ablage {
    /** Die Datei [pfad] mit [inhalt] ueberschreiben oder anlegen; false, wenn es nicht ging. */
    fun lege(pfad: String, inhalt: String, typ: String = "application/json"): Boolean

    /** Der Inhalt von [pfad], null ohne Datei. */
    fun hole(pfad: String): String?

    /** [pfad] loeschen; true auch, wenn es ihn nicht gab. */
    fun weg(pfad: String): Boolean

    /** Name und Groesse in Bytes jeder Datei in [ordner], mit einer Abfrage; null, wenn es nicht ging. */
    fun groessen(ordner: String): Map<String, Long>?
}

/** Ein Ordner, den man Boulder einmal freigegeben hat (Storage Access Framework). */
internal class Ordner(private val context: Context, private val baum: Uri) : Ablage {
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

    override fun lege(pfad: String, inhalt: String, typ: String): Boolean = try {
        val (d, datei) = zerlege(pfad, anlegen = true) ?: error("folder not reachable")
        val ziel = d.findFile(datei)?.takeIf { it.isFile } ?: d.createFile(typ, datei) ?: error("cannot create $datei")
        context.contentResolver.openOutputStream(ziel.uri, "wt")?.use { it.write(inhalt.toByteArray()) } ?: error("cannot write $datei")
        true
    } catch (e: Exception) {
        Logger.withTag("Sicherung").w { "Schreiben: " + e.message }
        false
    }

    override fun hole(pfad: String): String? = try {
        val (d, datei) = zerlege(pfad, anlegen = false) ?: error("missing")
        val quelle = d.findFile(datei)?.takeIf { it.isFile } ?: error("missing")
        context.contentResolver.openInputStream(quelle.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        // Meist gibt es die Datei (noch) nicht - kein Fehler.
        Logger.withTag("Sicherung").i { "Nicht gelesen ($pfad): " + e.message }
        null
    }

    override fun weg(pfad: String): Boolean = try {
        val (d, datei) = zerlege(pfad, anlegen = false) ?: error("folder not reachable")
        d.findFile(datei)?.delete() ?: true
    } catch (e: Exception) {
        Logger.withTag("Sicherung").w { "Loeschen ($pfad): " + e.message }
        false
    }

    // EINE ABFRAGE FUER DEN GANZEN ORDNER: findFile listet ihn je Datei neu, und name/length
    // fragten den Anbieter je Datei zweimal - bei Hunderten Spuren in der Cloud Minuten.
    override fun groessen(ordner: String): Map<String, Long>? = try {
        val d = wurzel()?.findFile(ordner)?.takeIf { it.isDirectory }
        if (d == null) {
            emptyMap()
        } else {
            val kinder = DocumentsContract.buildChildDocumentsUriUsingTree(baum, DocumentsContract.getDocumentId(d.uri))
            val spalten = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE)
            buildMap {
                context.contentResolver.query(kinder, spalten, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        if (!c.isNull(0) && !c.isNull(1)) put(c.getString(0), c.getLong(1))
                    }
                } ?: error("no answer from the provider")
            }
        }
    } catch (e: Exception) {
        Logger.withTag("Sicherung").w { "Groessen ($ordner): " + e.message }
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
        val stand = Trenddaten.lies(context, api, merken = false)
        val tage = Tagesspeicher.jeTag(stand.eigen).toList()
        val spuren = Spur.alle(context)
        // Die Vorgeschichte getrennt: beim Zurueckholen soll sie wieder nur die Zeit davor fuellen.
        if (!legeSicher(o, Sicherungsformat.BOULDER, Sicherungsformat.alsJson(tage, spuren, Instant.now().toString(), Tagesspeicher.jeTag(stand.vorgeschichte).toList()), "application/json")) {
            return@withContext context.getString(R.string.ks_b_fehlgeschlagen)
        }
        // JE ORDNER: nach einem Ordnerwechsel gehoeren die alten Spuren auch in den neuen.
        val neu = sichereSpuren(context, o, "spuren@" + ordnerUri(context), spuren)
        context.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit().putLong("zuletzt", System.currentTimeMillis()).apply()
        context.getString(R.string.ks_b_gesichert, tage.size, neu)
    }

    /**
     * Die Spuren, die im Ordner fehlen oder dort kuerzer sind als hier - mit
     * Zahl der Bytes gemerkt ("beginn:bytes"): die laufende Aufnahme haengt
     * weiter an, und eine einmal halb gesicherte Spur bliebe sonst halb. Ein
     * Eintrag ohne Bytes (vor dieser Fassung) wird einmal mit der Groesse der
     * Datei im Ordner abgeglichen: gleich gross gilt als ganz, sonst wird neu
     * gesichert. Rueckgabe: wie viele geschrieben wurden.
     */
    internal fun sichereSpuren(context: Context, o: Ablage, schluessel: String, spuren: List<Long>): Int {
        val p = context.getSharedPreferences(DATEI, Context.MODE_PRIVATE)
        // Vor 0.25.0 galt eine Liste fuer jeden Ordner: sie zaehlt einmal fuer den jetzigen.
        val oben = (p.getStringSet(schluessel, null) ?: p.getStringSet("spuren", emptySet())).orEmpty().toMutableSet()
        // NUR WENN ES ALTEINTRAEGE GIBT, und dann einmal fuer alle: jede Spur blind neu zu
        // sichern hiess beim ersten Lauf fuenf Ordneraufrufe je Spur.
        val imOrdner by lazy { o.groessen(Sicherungsformat.SPURORDNER).orEmpty() }
        // JE SPUR GEMERKT, nicht erst am Ende: endet der Lauf vorher (Zeitlimit des Workers,
        // Prozessende), gilt beim naechsten, was schon im Ordner steht.
        fun merke() = p.edit().putStringSet(schluessel, HashSet(oben)).apply()
        var neu = 0
        spuren.forEach { b ->
            // Erst die Groesse: den Inhalt jeder Spur bei jedem Lauf zu lesen kostete bei Hunderten Trainings Sekunden.
            val hier = Spur.groesse(context, b)
            if ("$b:$hier" in oben) return@forEach
            if ("$b" in oben && hier >= 0 && imOrdner[Sicherungsformat.spurname(b)] == hier) {
                oben.remove("$b")
                oben += "$b:$hier"
                merke()
                return@forEach
            }
            val inhalt = Spur.roh(context, b) ?: return@forEach
            if (legeSicher(o, spurpfad(b), inhalt, "application/x-ndjson")) {
                oben.removeAll { it == "$b" || it.startsWith("$b:") }
                oben += "$b:${inhalt.toByteArray().size}"
                merke()
                neu++
            }
        }
        // Auch ohne Aenderung: die Liste von vor 0.25.0 gilt danach unter dem Schluessel dieses Ordners.
        merke()
        return neu
    }

    private fun spurpfad(b: Long) = Sicherungsformat.SPURORDNER + "/" + Sicherungsformat.spurname(b)

    /**
     * Neben [pfad] die Zweitdatei: "boulder.json" wird "boulder-neu.json".
     * DIESELBE ENDUNG: sonst haengt der Anbieter des Ordners eine passende an,
     * und die Datei waere beim naechsten Mal nicht mehr zu finden.
     */
    internal fun zweitname(pfad: String): String {
        val punkt = pfad.lastIndexOf('.')
        return if (punkt <= pfad.lastIndexOf('/')) "$pfad-neu" else pfad.substring(0, punkt) + "-neu" + pfad.substring(punkt)
    }

    /**
     * [inhalt] nach [pfad], ohne die letzte gute Fassung aufs Spiel zu setzen:
     * erst in die Zweitdatei, zurueckgelesen, dann in die Datei selbst, wieder
     * zurueckgelesen, dann die Zweitdatei weg. Bricht es irgendwo ab, ist eine
     * der beiden ganz ([holeStand], [holeSpur]). Umbenennen ginge nicht: manche
     * Anbieter (Cloud) legen bei gleichem Namen eine zweite Datei an.
     */
    internal fun legeSicher(o: Ablage, pfad: String, inhalt: String, typ: String): Boolean {
        val zweit = zweitname(pfad)
        if (!o.lege(zweit, inhalt, typ) || o.hole(zweit) != inhalt) {
            Logger.withTag("Sicherung").w { "Zweitdatei $zweit nicht vollstaendig, $pfad bleibt wie sie war" }
            return false
        }
        if (!o.lege(pfad, inhalt, typ) || o.hole(pfad) != inhalt) {
            Logger.withTag("Sicherung").w { "$pfad nicht vollstaendig, es gilt $zweit" }
            return false
        }
        if (!o.weg(zweit)) Logger.withTag("Sicherung").i { "$zweit bleibt liegen" }
        return true
    }

    /** Boulders Sicherung: die juengere lesbare aus Datei und Zweitdatei (siehe [legeSicher]). */
    internal fun holeStand(o: Ablage, pfad: String = Sicherungsformat.BOULDER): Sicherungsformat.Stand? =
        listOfNotNull(Sicherungsformat.ausJson(o.hole(pfad)), Sicherungsformat.ausJson(o.hole(zweitname(pfad))))
            .maxByOrNull { erzeugtUm(it.erzeugt) }

    private fun erzeugtUm(text: String): Instant = try {
        Instant.parse(text)
    } catch (e: java.time.format.DateTimeParseException) {
        Logger.withTag("Sicherung").i { "Zeit der Sicherung nicht lesbar: " + e.message }
        Instant.EPOCH
    }

    /** Eine Spur: die laengere aus Datei und Zweitdatei - eine Spur waechst nur, die laengere ist die vollstaendigere. */
    internal fun holeSpur(o: Ablage, b: Long): String? =
        listOfNotNull(o.hole(spurpfad(b)), o.hole(zweitname(spurpfad(b)))).maxByOrNull { it.length }

    /**
     * Holt aus dem Ordner, was hier fehlt: aus Boulders eigener Sicherung und
     * aus der von Kiesel-Helper. Tage nur, wo Boulder den Wert nicht kennt;
     * Spuren nur, wo keine liegt.
     *
     * Boulders eigene Uhrwerte gehen ins eigene Fach des Tagesspeichers, alles
     * andere in die Vorgeschichte - die fuellt nur die Zeit vor dem ersten
     * eigenen Wert (Wasser, Koffein und Praeparate kennt Boulder sonst aus
     * Akte und Einstellungen).
     */
    suspend fun zurueck(context: Context): String = withContext(Dispatchers.IO) {
        val o = ordner(context) ?: return@withContext context.getString(R.string.ks_b_kein_ordner)
        val staende = listOfNotNull(
            holeStand(o)?.let { Sicherungsformat.BOULDER to it },
            Sicherungsformat.ausJson(o.hole(Sicherungsformat.KIESEL_HELPER))?.let { Sicherungsformat.KIESEL_HELPER to it },
        )
        if (staende.isEmpty()) return@withContext context.getString(R.string.ks_b_keine_sicherung)
        val tage = mutableSetOf<LocalDate>()
        var spuren = 0
        staende.forEach { (datei, s) ->
            val eigen = datei == Sicherungsformat.BOULDER && s.fassung >= 2
            s.tage.forEach { (tag, werte) ->
                if (eigen) {
                    val (uhr, rest) = werte.entries.partition { it.key in Trenddaten.UHRREIHEN }
                    Tagesspeicher.ergaenzeEigene(context, tag, uhr.associate { it.key to it.value })
                    Tagesspeicher.ergaenze(context, tag, rest.associate { it.key to it.value })
                } else {
                    Tagesspeicher.ergaenze(context, tag, Sicherungsformat.fuerBoulder(werte))
                }
                tage += tag
            }
            s.vorgeschichte.forEach { (tag, werte) ->
                Tagesspeicher.ergaenze(context, tag, Sicherungsformat.fuerBoulder(werte))
                tage += tag
            }
            s.spuren.forEach { b ->
                if (Spur.vorhanden(context, b)) return@forEach
                val inhalt = holeSpur(o, b) ?: return@forEach
                if (Spur.schreibeRoh(context, b, inhalt)) spuren++
            }
        }
        context.getString(R.string.ks_b_zurueck, tage.size, spuren)
    }

    /**
     * Wie oft ein fehlgeschlagener Tageslauf wiederholt wird (WorkManager-Backoff 30 s, 1, 2 min),
     * bevor er bis zum naechsten Tag wartet. Ohne Grenze weckte ein dauerhafter Fehler bis zum
     * naechsten Lauf ~5-10 Mal am Tag.
     */
    internal const val VERSUCHE = 3

    /** Wiederholen, solange [VERSUCHE] nicht erreicht ist; danach versucht es erst der naechste Tageslauf. */
    internal fun nochmal(bisher: Int): Boolean = bisher < VERSUCHE

    /** Was der Tageslauf WorkManager meldet; [OHNE_ORDNER] ist ein Erfolg mit Zeile im Protokoll. */
    internal enum class Ausgang { FERTIG, OHNE_ORDNER, NOCHMAL, AUFGEBEN }

    /** Nach einem Schreibfehler oder einer Ausnahme im Versuch [versuch] (runAttemptCount, ab 0). */
    internal fun nachFehler(versuch: Int): Ausgang = if (nochmal(versuch)) Ausgang.NOCHMAL else Ausgang.AUFGEBEN

    /**
     * Der Ausgang aus der [meldung] von [jetzt] - null, wenn kein Ordner gewaehlt ist.
     * Ordner geloescht oder Freigabe entzogen ([keinOrdner]): ein Wiederholen aendert daran
     * nichts, der naechste Tageslauf versucht es wieder. Ein Schreibfehler ([fehlgeschlagen])
     * kann voruebergehend sein: noch einmal, aber nicht endlos.
     */
    internal fun ausgang(meldung: String?, keinOrdner: String, fehlgeschlagen: String, versuch: Int): Ausgang = when (meldung) {
        keinOrdner -> Ausgang.OHNE_ORDNER
        fehlgeschlagen -> nachFehler(versuch)
        else -> Ausgang.FERTIG
    }

    /** Nie zweimal hintereinander: ein Ordner, der wochenlang fehlt, fuellte sonst die 100 Zeilen des Protokolls. */
    internal fun neueZeile(letzte: String?, zeile: String): Boolean = letzte != zeile

    class Arbeit(context: Context, gaben: WorkerParameters) : CoroutineWorker(context, gaben) {
        override suspend fun doWork(): Result {
            val c = applicationContext
            val kein = c.getString(R.string.ks_b_kein_ordner)
            val aus = try {
                val meldung = if (ordner(c) != null) jetzt(c, KoinPlatform.getKoin().get()) else null
                ausgang(meldung, kein, c.getString(R.string.ks_b_fehlgeschlagen), runAttemptCount)
            } catch (e: Exception) {
                Logger.withTag("Sicherung").w { "Taeglich: " + e.message }
                nachFehler(runAttemptCount)
            }
            return when (aus) {
                Ausgang.OHNE_ORDNER -> {
                    val zeile = c.getString(R.string.ks_b_titel) + ": " + kein
                    if (neueZeile(KieselVerlauf.alle(c).firstOrNull()?.second, zeile)) KieselVerlauf.merke(c, zeile)
                    Result.success()
                }
                Ausgang.NOCHMAL -> Result.retry()
                Ausgang.AUFGEBEN -> Result.failure()
                Ausgang.FERTIG -> Result.success()
            }
        }
    }
}
