package boulder.server

import boulder.austausch.Nacht
import boulder.austausch.Stand
import boulder.austausch.austauschJson
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Was Claude ueber MCP lesen kann. Die Antworten sind Text in Tabellenform, mit Einheiten und in
 * Ortszeit: die Feldnamen stehen einmal im Kopf statt in jeder Zeile wie bei JSON, und Claudes
 * Kontext fasst so mehr Tage.
 *
 * NUR LESEN: kein Werkzeug aendert den Spiegel; geschrieben wird allein ueber /sync von Boulder.
 * DIE GRENZEN (366 Tage, 24 h Puls, 6 h Minuten) halten eine Antwort klein genug fuer Claudes
 * Kontext; mehr fragt Claude in Teilen.
 */
class Werkzeuge(private val speicher: Speicher) {

    class Fehler(text: String) : Exception(text)

    private val zone: ZoneId
        get() = speicher.stand("zone")?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC

    private val uhr = DateTimeFormatter.ofPattern("HH:mm")
    private val zeitpunkt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun stand(): String {
        val u = speicher.umfang()
        val st = speicher.stand("stand")?.let { austauschJson.decodeFromString(Stand.serializer(), it) }
        val zuletzt = speicher.stand("letzte_lieferung")?.toLongOrNull()
        return buildString {
            appendLine("Boulder health data of one person (Pebble watch with Boulder firmware), mirrored by the Boulder app.")
            appendLine("timezone: $zone")
            appendLine("last upload: " + (zuletzt?.let { zeitpunkt.format(Instant.ofEpochSecond(it).atZone(zone)) } ?: "never") + " (app ${speicher.stand("app") ?: "-"})")
            st?.firmware?.let { appendLine("watch firmware: $it") }
            if (st != null && st.ziele.isNotEmpty()) appendLine("goals: " + st.ziele.entries.joinToString(", ") { "${it.key} ${it.value}" })
            st?.maxPuls?.let { appendLine("max heart rate used for zones: $it bpm") }
            appendLine("days: ${u.ersterTag ?: "-"} .. ${u.letzterTag ?: "-"}")
            appendLine("nights: ${u.naechte}, workouts: ${u.trainings}, minute records: ${u.minuten}" +
                (u.ersteMinute?.let { " (since ${zeitpunkt.format(Instant.ofEpochSecond(it).atZone(zone))})" } ?: ""))
        }
    }

    fun tage(von: LocalDate, bis: LocalDate): String {
        pruefe(!bis.isBefore(von), "bis liegt vor von")
        pruefe(ChronoUnit.DAYS.between(von, bis) < 366, "hoechstens 366 Tage je Abfrage")
        val tage = speicher.tage(von.toString(), bis.toString())
        if (tage.isEmpty()) return "no days between $von and $bis"
        val spalten = SPALTEN.keys.filter { k -> tage.any { k in it.werte } } +
            tage.flatMap { it.werte.keys }.distinct().filter { it !in SPALTEN }.sorted()
        return buildString {
            for (k in spalten) appendLine("# $k: ${SPALTEN[k] ?: "value as stored by Boulder"}")
            appendLine((listOf("date") + spalten).joinToString(","))
            for (t in tage) appendLine((listOf(t.datum) + spalten.map { k -> t.werte[k]?.let(::zahl) ?: "" }).joinToString(","))
        }
    }

    /** Die Nacht, aus der man an [datum] aufwachte (so bucht Boulder Nachtwerte). */
    fun nacht(datum: LocalDate): String {
        val start = datum.minusDays(2).atStartOfDay(zone).toEpochSecond()
        val ende = datum.plusDays(1).atStartOfDay(zone).toEpochSecond()
        val n = speicher.naechte(start, ende).lastOrNull { aufwachTag(it) == datum }
            ?: return "no night ending on $datum"
        fun ort(minute: Int) = Instant.ofEpochSecond(n.beginn + minute * 60L).atOffset(ZoneOffset.ofTotalSeconds(n.utcZuLokal))
        return buildString {
            appendLine("night ending $datum")
            appendLine("fell asleep: ${uhr.format(ort(n.einschlafen))}, woke up: ${uhr.format(ort(n.aufwachen))}, asleep: ${n.schlafMinuten} min")
            appendLine("resting heart rate: ${n.ruhepuls.takeIf { it > 0 }?.let { "$it bpm" } ?: "-"}, HRV (RMSSD): ${n.hrv.takeIf { it > 0 }?.let { "$it ms" } ?: "-"}")
            n.messstufe?.let { appendLine("measuring level: $it of 5") }
            if (n.akkuBeginn != null || n.akkuEnde != null) appendLine("watch battery: ${n.akkuBeginn ?: "-"}% -> ${n.akkuEnde ?: "-"}%")
            if (n.abschnitte.isNotEmpty()) {
                val summe = n.abschnitte.groupBy { it.phase }.mapValues { (_, l) -> l.sumOf { it.bis - it.von } }
                appendLine("stages total: " + PHASEN.entries.filter { it.key in summe }.joinToString(", ") { "${it.value} ${summe[it.key]} min" })
                appendLine("stages:")
                for (a in n.abschnitte.sortedBy { it.von }) appendLine("${uhr.format(ort(a.von))}-${uhr.format(ort(a.bis))} ${PHASEN[a.phase] ?: a.phase}")
            }
            if (n.hrvFenster.isNotEmpty()) {
                val r = n.hrvFenster.map { it.rmssd }.sorted()
                appendLine("HRV windows (5 min each): ${r.size}, RMSSD median ${r[r.size / 2]} ms, range ${r.first()}-${r.last()} ms")
            }
        }
    }

    fun trainings(von: LocalDate, bis: LocalDate): String {
        pruefe(!bis.isBefore(von), "bis liegt vor von")
        pruefe(ChronoUnit.DAYS.between(von, bis) < 366, "hoechstens 366 Tage je Abfrage")
        val l = speicher.trainings(von.atStartOfDay(zone).toEpochSecond(), bis.plusDays(1).atStartOfDay(zone).toEpochSecond())
        if (l.isEmpty()) return "no workouts between $von and $bis"
        return buildString {
            appendLine("start,end,sport,active_min,distance_km,hr_avg,hr_max,source")
            for (t in l) {
                val a = Instant.ofEpochSecond(t.beginn).atZone(zone)
                val b = Instant.ofEpochSecond(t.ende).atZone(zone)
                appendLine(listOf(
                    zeitpunkt.format(a), uhr.format(b), t.sport, (t.aktivSekunden / 60).toString(),
                    if (t.meter > 0) "%.2f".format(java.util.Locale.ROOT, t.meter / 1000) else "",
                    t.pulsMittel?.toString().orEmpty(), t.pulsMax?.toString().orEmpty(),
                    if (t.eigen) "Boulder" else "other app",
                ).joinToString(","))
            }
        }
    }

    /** Puls in 5-Minuten-Bloecken aus den Minuten: Median und Maximum der gemessenen Werte. */
    fun puls(von: LocalDateTime, bis: LocalDateTime): String {
        val (a, b) = zeitraum(von, bis, 24 * 60, "24 h")
        val bloecke = speicher.minuten(a, b).filter { it.puls > 0 }.groupBy { (it.zeit - a) / 300 }
        if (bloecke.isEmpty()) return "no heart rate between $von and $bis"
        return buildString {
            appendLine("time,hr_median,hr_max,measured_minutes")
            for ((block, l) in bloecke.toSortedMap()) {
                val p = l.map { it.puls }.sorted()
                appendLine("${uhr.format(Instant.ofEpochSecond(a + block * 300).atZone(zone))},${p[p.size / 2]},${p.last()},${p.size}")
            }
        }
    }

    fun minuten(von: LocalDateTime, bis: LocalDateTime): String {
        val (a, b) = zeitraum(von, bis, 6 * 60, "6 h")
        val l = speicher.minuten(a, b)
        if (l.isEmpty()) return "no minute records between $von and $bis (Boulder uploads them only if enabled)"
        return buildString {
            appendLine("# steps: counted steps; vmc: movement intensity; hr: bpm, 0 = not measured; orientation: wrist position code")
            appendLine("time,steps,vmc,hr,orientation")
            for (m in l) appendLine("${uhr.format(Instant.ofEpochSecond(m.zeit).atZone(zone))},${m.schritte},${m.vmc},${m.puls},${m.lage}")
        }
    }

    private fun aufwachTag(n: Nacht): LocalDate =
        Instant.ofEpochSecond(n.beginn + n.aufwachen * 60L).atOffset(ZoneOffset.ofTotalSeconds(n.utcZuLokal)).toLocalDate()

    private fun zeitraum(von: LocalDateTime, bis: LocalDateTime, hoechstensMin: Long, text: String): Pair<Long, Long> {
        pruefe(bis.isAfter(von), "bis liegt nicht nach von")
        pruefe(ChronoUnit.MINUTES.between(von, bis) <= hoechstensMin, "hoechstens $text je Abfrage")
        return von.atZone(zone).toEpochSecond() to bis.atZone(zone).toEpochSecond()
    }

    private fun pruefe(bedingung: Boolean, text: String) {
        if (!bedingung) throw Fehler(text)
    }

    companion object {
        /** Was Boulders Tagesspalten bedeuten (Spalte.kt in der App), in der Reihenfolge der Tabelle. */
        val SPALTEN = linkedMapOf(
            "schritte" to "steps",
            "aktiv" to "active minutes from steps",
            "schlaf" to "sleep of the night ending this day [min]",
            "tief" to "deep sleep of that night [min]",
            "einschlafen" to "fell asleep [min after 18:00 of the previous day]",
            "schlaf_mitte" to "middle of sleep [min after 18:00 of the previous day]",
            "ruhepuls" to "resting heart rate of that night [bpm]",
            "hrv" to "HRV of that night, RMSSD [ms]",
            "wasser" to "water [ml]",
            "koffein_mg" to "caffeine [mg]",
            "supp_faellig" to "supplements due [count]",
            "supp_genommen" to "supplements taken [count]",
        )
        val PHASEN = linkedMapOf(0 to "awake", 1 to "light", 2 to "deep", 3 to "REM", 4 to "asleep (no stage)")

        private fun zahl(x: Double): String = if (x == Math.floor(x) && kotlin.math.abs(x) < 1e15) x.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, x)
    }
}

private val NUR_LESEN = ToolAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false)

private const val ANLEITUNG =
    "Read-only health data of one person from a Pebble watch, mirrored by the Boulder app. " +
        "Call 'stand' first: it tells the timezone and which days are available. Dates are local (YYYY-MM-DD), " +
        "times local (YYYY-MM-DDTHH:MM). A night belongs to the day of waking up. Steps and active minutes " +
        "of a day appear once the day is over; for today use 'puls' or 'minuten' (if minute records are uploaded)."

private fun schema(vararg felder: Pair<String, String>): ToolSchema = ToolSchema(
    properties = buildJsonObject {
        for ((name, beschreibung) in felder) putJsonObject(name) {
            put("type", "string")
            put("description", beschreibung)
        }
    },
    required = felder.map { it.first },
)

private fun JsonObject?.text(name: String): String =
    this?.get(name)?.jsonPrimitive?.contentOrNull ?: throw Werkzeuge.Fehler("$name fehlt")

private fun JsonObject?.datum(name: String): LocalDate =
    runCatching { LocalDate.parse(text(name)) }.getOrElse { throw Werkzeuge.Fehler("$name: Datum als JJJJ-MM-TT") }

private fun JsonObject?.zeit(name: String): LocalDateTime =
    runCatching { LocalDateTime.parse(text(name)) }.getOrElse { throw Werkzeuge.Fehler("$name: Zeit als JJJJ-MM-TTTHH:MM") }

/** Ein Werkzeug rechnet; ein Fehler in der Eingabe kommt als Fehlerantwort zurueck, nicht als Ausnahme. */
private inline fun antwort(rechne: () -> String): CallToolResult = try {
    CallToolResult(content = listOf(TextContent(rechne())))
} catch (e: Werkzeuge.Fehler) {
    CallToolResult(content = listOf(TextContent(e.message.orEmpty())), isError = true)
}

/** Der MCP-Server fuer eine Anfrage (zustandslos, siehe App.kt). */
internal fun mcpServer(speicher: Speicher): Server {
    val w = Werkzeuge(speicher)
    val s = Server(
        Implementation(name = "boulder", version = "1"),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        instructions = ANLEITUNG,
    )
    val von = "von" to "first day, YYYY-MM-DD (local)"
    val bis = "bis" to "last day, YYYY-MM-DD (local), at most 366 days after von"
    s.addTool(name = "stand", description = "Timezone, last upload, watch firmware, goals and the available range of data.",
        inputSchema = ToolSchema(), toolAnnotations = NUR_LESEN) { antwort { w.stand() } }
    s.addTool(name = "tage", description = "Daily values per day (steps, sleep, resting heart rate, HRV, water, caffeine ...), one CSV row per day with a legend.",
        inputSchema = schema(von, bis), toolAnnotations = NUR_LESEN) { r -> antwort { w.tage(r.arguments.datum("von"), r.arguments.datum("bis")) } }
    s.addTool(name = "nacht", description = "One night in detail: sleep times, sleep stages, resting heart rate, HRV.",
        inputSchema = schema("datum" to "the day of waking up, YYYY-MM-DD (local)"), toolAnnotations = NUR_LESEN) { r -> antwort { w.nacht(r.arguments.datum("datum")) } }
    s.addTool(name = "trainings", description = "Workouts recorded with the watch (sport, duration, distance, heart rate).",
        inputSchema = schema(von, bis), toolAnnotations = NUR_LESEN) { r -> antwort { w.trainings(r.arguments.datum("von"), r.arguments.datum("bis")) } }
    val vonZeit = "von" to "start, YYYY-MM-DDTHH:MM (local)"
    s.addTool(name = "puls", description = "Heart rate in 5-minute blocks (median, maximum), at most 24 hours.",
        inputSchema = schema(vonZeit, "bis" to "end, YYYY-MM-DDTHH:MM (local), at most 24 h after von"), toolAnnotations = NUR_LESEN) { r ->
        antwort { w.puls(r.arguments.zeit("von"), r.arguments.zeit("bis")) }
    }
    s.addTool(name = "minuten", description = "Raw minute records of the watch (steps, movement, heart rate, wrist orientation), at most 6 hours.",
        inputSchema = schema(vonZeit, "bis" to "end, YYYY-MM-DDTHH:MM (local), at most 6 h after von"), toolAnnotations = NUR_LESEN) { r ->
        antwort { w.minuten(r.arguments.zeit("von"), r.arguments.zeit("bis")) }
    }
    return s
}
