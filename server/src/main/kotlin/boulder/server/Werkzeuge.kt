package boulder.server

import boulder.austausch.Akteneintrag
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
 * Ortszeit: die Feldnamen stehen einmal im Kopf, und Claudes Kontext fasst so mehr Tage.
 *
 * NUR LESEN: kein Werkzeug aendert den Spiegel; geschrieben wird allein ueber /sync von Boulder.
 * DIE GRENZEN (366 Tage, 24 h Puls, 6 h Minuten, 500 Saetze der Akte, 7 Tage Messreihe) halten eine
 * Antwort klein genug fuer Claudes Kontext; mehr fragt Claude in Teilen.
 */
class Werkzeuge(private val speicher: Speicher) {

    class Fehler(text: String) : Exception(text)

    // Einmal je Anfrage: mcpServer baut die Werkzeuge fuer jede neu, und eine Antwort braucht die Zone in jeder Zeile.
    private val zone: ZoneId by lazy {
        speicher.stand("zone")?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC
    }

    private val uhr = DateTimeFormatter.ofPattern("HH:mm")
    private val sekundenUhr = DateTimeFormatter.ofPattern("HH:mm:ss")
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
            if (u.vortage > 0) {
                appendLine("history days: ${u.ersterVortag} .. ${u.letzterVortag} (from Kiesel-Helper and older backups, measured " +
                    "differently; 'tage' shows them before Boulder's own values and marks them in its source column)")
            }
            appendLine("watch SpO2 readings: ${u.sauerstoff}" +
                (u.ersterSauerstoff?.let { " (since ${zeitpunkt.format(Instant.ofEpochSecond(it).atZone(zone))})" } ?: ""))
            val arten = speicher.akteArten()
            if (arten.isEmpty()) {
                appendLine("Health Connect records: none uploaded")
            } else {
                appendLine("Health Connect records of all apps on the phone (tools akte, akte_tage, akte_verlauf, akte_eintrag); type: count (first .. last) sources:")
                for (a in arten) {
                    appendLine("  ${a.art}: ${a.anzahl} (${tag(a.erster)} .. ${tag(a.letzter)}) " + a.quellen.entries.joinToString(", ") { "${it.key} ${it.value}" })
                }
            }
        }
    }

    /**
     * Boulders Tage, davor die Vorgeschichte wie in den Trends der App: je Spalte nur vor dem ersten
     * eigenen Wert, die Aktivminuten nie (Kiesel-Helper mass dort die Trainingsdauer).
     */
    fun tage(von: LocalDate, bis: LocalDate): String {
        pruefeTage(von, bis)
        val eigen = speicher.tage(von.toString(), bis.toString()).associate { it.datum to it.werte }
        val vor = speicher.vortage(von.toString(), bis.toString()).associate { it.datum to it.werte }
        val eigenAb = if (vor.isEmpty()) emptyMap() else speicher.ersteEigeneTage()
        val tage = (eigen.keys + vor.keys).sorted().map { datum ->
            val e = eigen[datum].orEmpty()
            val v = vor[datum].orEmpty().filter { (k, _) -> k !in e && k !in OHNE_VORGESCHICHTE && eigenAb[k]?.let { datum < it } != false }
            Triple(datum, e + v, if (v.isEmpty()) "boulder" else if (e.isEmpty()) "history" else "boulder+history")
        }.filter { it.second.isNotEmpty() }
        if (tage.isEmpty()) return "no days between $von and $bis"
        val bekannt = SPALTEN.keys + VORSPALTEN.keys
        val spalten = bekannt.filter { k -> tage.any { k in it.second } } +
            tage.flatMap { it.second.keys }.distinct().filter { it !in bekannt }.sorted()
        val mitQuelle = tage.any { it.third != "boulder" }
        return buildString {
            for (k in spalten) appendLine("# $k: ${SPALTEN[k] ?: VORSPALTEN[k] ?: "value as stored by Boulder"}")
            if (mitQuelle) appendLine("# source: boulder = measured by Boulder, history = from Kiesel-Helper or older backups (measured differently)")
            appendLine((listOf("date") + spalten + if (mitQuelle) listOf("source") else emptyList()).joinToString(","))
            for ((datum, werte, quelle) in tage) {
                appendLine((listOf(datum) + spalten.map { k -> werte[k]?.let(::zahl) ?: "" } + if (mitQuelle) listOf(quelle) else emptyList()).joinToString(","))
            }
        }
    }

    /**
     * Die Nacht, aus der man an [datum] aufwachte, und bei mehreren die laengste - so bucht Boulder
     * die Nachtwerte eines Tages (Gesundheitsrechnung.jeTag), und so passt sie zu [tage].
     */
    fun nacht(datum: LocalDate): String {
        val start = datum.minusDays(2).atStartOfDay(zone).toEpochSecond()
        val ende = datum.plusDays(1).atStartOfDay(zone).toEpochSecond()
        val n = speicher.naechte(start, ende).filter { aufwachTag(it) == datum }.maxByOrNull { it.schlafMinuten }
            ?: return "no night ending on $datum"
        fun ort(minute: Int) = Instant.ofEpochSecond(n.beginn + minute * 60L).atOffset(ZoneOffset.ofTotalSeconds(n.utcZuLokal))
        return buildString {
            appendLine("night ending $datum")
            appendLine("fell asleep: ${uhr.format(ort(n.einschlafen))}, woke up: ${uhr.format(ort(n.aufwachen))}, asleep: ${n.schlafMinuten} min")
            appendLine("resting heart rate: ${n.ruhepuls.takeIf { it > 0 }?.let { "$it bpm" } ?: "-"}, HRV (RMSSD): ${n.hrv.takeIf { it > 0 }?.let { "$it ms" } ?: "-"}")
            n.messstufe?.let { appendLine("measuring level: $it of 5") }
            if (n.akkuBeginn != null || n.akkuEnde != null) {
                appendLine("watch battery: ${n.akkuBeginn ?: "-"}% -> ${n.akkuEnde ?: "-"}%" + if (n.geladen) " (charged during the night, no consumption figure)" else "")
            }
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
        pruefeTage(von, bis)
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

    /** Die Saetze einer Art aus Health Connect, die zwischen [von] und [bis] beginnen, ohne Messreihe. */
    fun akte(art: String, von: LocalDate, bis: LocalDate): String {
        pruefeTage(von, bis)
        val l = speicher.akte(art, anfang(von), ende(bis), HOECHSTENS_SAETZE)
        if (l.isEmpty()) return "no $art records between $von and $bis" + andereArten(art)
        pruefe(l.size <= HOECHSTENS_SAETZE, "more than $HOECHSTENS_SAETZE $art records between $von and $bis; ask for a shorter range or use akte_tage")
        val werte = l.flatMap { it.werte.keys }.distinct().sorted()
        val texte = l.flatMap { it.texte.keys }.distinct().sorted()
        val umfang = l.associate { it.id to speicher.probenUmfang(it.id) }
        val einheit = PROBENEINHEIT[art] ?: "sample"
        val mitProben = umfang.values.any { it != null }
        val mitAbschnitten = l.any { it.abschnitte.isNotEmpty() }
        return buildString {
            if (mitProben) appendLine("# ${einheit}_n/_min/_mean/_max: the samples inside one record; akte_verlauf and akte_eintrag show them")
            if (mitAbschnitten) appendLine("# parts: sleep stages, exercise segments or laps, with their total minutes; akte_eintrag lists each")
            val kopf = listOf("start", "end", "source") + werte + texte +
                (if (mitProben) listOf("${einheit}_n", "${einheit}_min", "${einheit}_mean", "${einheit}_max") else emptyList()) +
                (if (mitAbschnitten) listOf("parts") else emptyList()) + "id"
            appendLine(kopf.joinToString(","))
            for (e in l) {
                val u = umfang[e.id]
                val zeile = listOf(zeitpunkt(e.beginn), e.ende?.let { bisZeit(e.beginn, it) }.orEmpty(), csv(e.quelle)) +
                    werte.map { k -> e.werte[k]?.let(::wert).orEmpty() } +
                    texte.map { k -> csv(e.texte[k].orEmpty()) } +
                    (if (mitProben) listOf(u?.anzahl?.toString().orEmpty(), u?.kleinster?.let(::wert).orEmpty(), u?.mittel?.let(::wert).orEmpty(), u?.groesster?.let(::wert).orEmpty()) else emptyList()) +
                    (if (mitAbschnitten) listOf(csv(teile(e))) else emptyList()) + csv(e.id)
                appendLine(zeile.joinToString(","))
            }
        }
    }

    /** Ein Satz der Akte ganz: Werte, Texte, jede Spanne und die Messreihe (lang in 5-Minuten-Bloecken). */
    fun akteEintrag(id: String): String {
        val e = speicher.akteEintrag(id) ?: return "no Health Connect record with id $id"
        return buildString {
            appendLine("${e.art} from ${e.quelle}")
            appendLine("start: ${zeitpunkt(e.beginn)}" + (e.ende?.let { ", end: ${zeitpunkt(it)}" } ?: ""))
            for ((k, v) in e.werte.toSortedMap()) appendLine("$k: ${wert(v)}")
            for ((k, v) in e.texte.toSortedMap()) appendLine("$k: $v")
            if (e.abschnitte.isNotEmpty()) {
                appendLine("parts:")
                for (a in e.abschnitte.sortedBy { it.von }) {
                    appendLine("${uhrzeit(a.von)}-${uhrzeit(a.bis)} ${a.art}" + (a.wert?.let { " ${wert(it)}" } ?: ""))
                }
            }
            if (e.proben.isNotEmpty()) {
                val einheit = PROBENEINHEIT[e.art] ?: "value"
                if (e.proben.size <= HOECHSTENS_PROBEN) {
                    appendLine("samples (time,$einheit):")
                    for (w in e.proben) appendLine("${sekundenzeit(w.t)},${wert(w.w)}")
                } else {
                    appendLine("samples in 5-minute blocks (time,n,${einheit}_min,${einheit}_mean,${einheit}_max):")
                    val a = e.proben.first().t
                    for ((block, l) in e.proben.groupBy { (it.t - a) / 300 }.toSortedMap()) {
                        val w = l.map { it.w }
                        appendLine("${uhrzeit(a + block * 300)},${w.size},${wert(w.min())},${wert(w.average())},${wert(w.max())}")
                    }
                }
            }
        }
    }

    /**
     * Je Tag und Quelle zusammengefasst: Mengen (Schritte, Strecke, Kalorien, Trinken, Essen) als
     * Summe, Messungen als Kleinster, Mittel, Groesster, Sitzungen mit ihren Minuten, Messreihen nach
     * der Zeit jedes Werts. Ein Satz zaehlt zum Tag, an dem er beginnt.
     */
    fun akteTage(art: String, von: LocalDate, bis: LocalDate): String {
        pruefeTage(von, bis)
        val a = anfang(von)
        val b = ende(bis)
        val eimer = speicher.akteEimer(art, a, b, EIMER_S) + speicher.probenEimer(art, a, b, EIMER_S)
        if (eimer.none { it.name == Speicher.SAETZE }) return "no $art records between $von and $bis" + andereArten(art)
        val summe = art in SUMMEN
        // Viertelstunden statt Tage aus SQLite: die Grenze eines Tages verschiebt sich mit der Sommerzeit.
        val tage = eimer.groupBy { Triple(Instant.ofEpochSecond(a + it.nummer * EIMER_S).atZone(zone).toLocalDate(), it.quelle, it.name) }
            .mapValues { (_, l) -> Tagessumme(l.sumOf { it.n }, l.sumOf { it.summe }, l.minOf { it.kleinster }, l.maxOf { it.groesster }) }
        val namen = tage.keys.map { it.third }.toSet()
        val mitMinuten = art in SITZUNGEN
        val mitProben = Speicher.PROBEN in namen
        val werte = (namen - setOf(Speicher.SAETZE, Speicher.MINUTEN, Speicher.PROBEN)).sorted()
        val einheit = PROBENEINHEIT[art] ?: "sample"
        val spalten = buildList {
            add("records")
            if (mitMinuten) add("minutes")
            for (k in werte) if (summe) add(k) else addAll(listOf("${k}_min", "${k}_mean", "${k}_max"))
            if (mitProben) addAll(listOf("${einheit}_n", "${einheit}_min", "${einheit}_mean", "${einheit}_max"))
        }
        return buildString {
            appendLine("# records: records starting that day" + if (summe) "; values are daily sums" else "")
            if (mitProben) appendLine("# ${einheit}_n/_min/_mean/_max: samples taken that day")
            appendLine((listOf("date", "source") + spalten).joinToString(","))
            for ((tag, quelle) in tage.keys.map { it.first to it.second }.distinct().sortedWith(compareBy({ it.first }, { it.second }))) {
                fun z(name: String) = tage[Triple(tag, quelle, name)]
                val zellen = buildList {
                    add(z(Speicher.SAETZE)?.n?.toString().orEmpty())
                    if (mitMinuten) add(z(Speicher.MINUTEN)?.summe?.let(::wert).orEmpty())
                    for (k in werte) {
                        val x = z(k)
                        if (summe) add(x?.summe?.let(::wert).orEmpty())
                        else addAll(listOf(x?.kleinster?.let(::wert).orEmpty(), x?.let { wert(it.summe / it.n) }.orEmpty(), x?.groesster?.let(::wert).orEmpty()))
                    }
                    if (mitProben) {
                        val x = z(Speicher.PROBEN)
                        addAll(listOf(x?.n?.toString().orEmpty(), x?.kleinster?.let(::wert).orEmpty(), x?.let { wert(it.summe / it.n) }.orEmpty(), x?.groesster?.let(::wert).orEmpty()))
                    }
                }
                appendLine((listOf(tag.toString(), csv(quelle)) + zellen).joinToString(","))
            }
        }
    }

    /** Die Messreihen einer Art je Quelle: bis 24 h in 5-Minuten-Bloecken, bis 7 Tage stuendlich. */
    fun akteVerlauf(art: String, von: LocalDateTime, bis: LocalDateTime): String {
        val (a, b) = zeitraum(von, bis, 7 * 24 * 60, "7 days")
        val breite = if (b - a <= 86_400) 300L else 3_600L
        val eimer = speicher.probenEimer(art, a, b, breite)
        if (eimer.isEmpty()) return "no $art samples between $von and $bis" + andereArten(art)
        val einheit = PROBENEINHEIT[art] ?: "value"
        return buildString {
            appendLine("# blocks of ${breite / 60} min; n = samples in the block")
            appendLine("time,source,n,${einheit}_min,${einheit}_mean,${einheit}_max")
            for (e in eimer.sortedWith(compareBy({ it.nummer }, { it.quelle }))) {
                appendLine("${zeitpunkt(a + e.nummer * breite)},${csv(e.quelle)},${e.n},${wert(e.kleinster)},${wert(e.summe / e.n)},${wert(e.groesster)}")
            }
        }
    }

    fun spo2(von: LocalDate, bis: LocalDate): String {
        pruefe(!bis.isBefore(von), "bis is before von")
        pruefe(ChronoUnit.DAYS.between(von, bis) <= HOECHSTENS_SPO2_TAGE, "bis may be at most $HOECHSTENS_SPO2_TAGE days after von")
        val l = speicher.sauerstoff(anfang(von), ende(bis))
        if (l.isEmpty()) return "no SpO2 readings of the watch between $von and $bis"
        return buildString {
            appendLine("# quality: sensor quality 0-7 as reported by the watch")
            appendLine("time,spo2_percent,quality")
            for (o in l) appendLine("${zeitpunkt(o.zeit)},${o.prozent},${o.qualitaet}")
        }
    }

    /** Fuer eine Art ohne Saetze: welche es gibt - Claude kennt die Namen nur aus "stand". */
    private fun andereArten(art: String): String {
        val arten = speicher.akteArten().map { it.art }
        return if (art in arten || arten.isEmpty()) "" else "; known types: " + arten.joinToString(", ")
    }

    private fun teile(e: Akteneintrag): String =
        e.abschnitte.groupBy { it.art }.entries.joinToString("; ") { (art, l) -> "$art ${l.sumOf { it.bis - it.von } / 60} min" }

    private fun anfang(tag: LocalDate): Long = tag.atStartOfDay(zone).toEpochSecond()

    private fun ende(tag: LocalDate): Long = tag.plusDays(1).atStartOfDay(zone).toEpochSecond()

    private fun zeitpunkt(t: Long): String = zeitpunkt.format(Instant.ofEpochSecond(t).atZone(zone))

    private fun uhrzeit(t: Long): String = uhr.format(Instant.ofEpochSecond(t).atZone(zone))

    private fun sekundenzeit(t: Long): String = sekundenUhr.format(Instant.ofEpochSecond(t).atZone(zone))

    private fun tag(t: Long): String = Instant.ofEpochSecond(t).atZone(zone).toLocalDate().toString()

    /** Das Ende eines Satzes: am selben Tag nur die Uhrzeit, sonst mit Datum. */
    private fun bisZeit(beginn: Long, ende: Long): String =
        if (tag(beginn) == tag(ende)) uhrzeit(ende) else zeitpunkt(ende)

    private data class Tagessumme(val n: Long, val summe: Double, val kleinster: Double, val groesster: Double)

    private fun aufwachTag(n: Nacht): LocalDate =
        Instant.ofEpochSecond(n.beginn + n.aufwachen * 60L).atOffset(ZoneOffset.ofTotalSeconds(n.utcZuLokal)).toLocalDate()

    /** Wie das Schema der Werkzeuge sagt: bis hoechstens 366 Tage nach von. */
    private fun pruefeTage(von: LocalDate, bis: LocalDate) {
        pruefe(!bis.isBefore(von), "bis is before von")
        pruefe(ChronoUnit.DAYS.between(von, bis) <= HOECHSTENS_TAGE, "bis may be at most $HOECHSTENS_TAGE days after von")
    }

    private fun zeitraum(von: LocalDateTime, bis: LocalDateTime, hoechstensMin: Long, text: String): Pair<Long, Long> {
        pruefe(bis.isAfter(von), "bis must be after von")
        pruefe(ChronoUnit.MINUTES.between(von, bis) <= hoechstensMin, "at most $text per query")
        return sekunde(von) to sekunde(bis)
    }

    /**
     * Eine Ortszeit als UTC-Sekunde, in derselben Reihenfolge wie die Ortszeiten. Eine Ortszeit in
     * der Luecke der Umstellung auf Sommerzeit gibt es nicht; sie zaehlt als der Augenblick, an dem
     * die Uhr springt. atZone schiebt sie um die Luecke nach hinten, und 02:30 laege nach 03:00.
     */
    private fun sekunde(t: LocalDateTime): Long {
        val regeln = zone.rules
        return if (regeln.getValidOffsets(t).isEmpty()) regeln.getTransition(t).instant.epochSecond else t.atZone(zone).toEpochSecond()
    }

    private fun pruefe(bedingung: Boolean, text: String) {
        if (!bedingung) throw Fehler(text)
    }

    companion object {
        const val HOECHSTENS_TAGE = 366L
        const val HOECHSTENS_SAETZE = 500
        const val HOECHSTENS_PROBEN = 300
        const val HOECHSTENS_SPO2_TAGE = 31L

        /** Eimer fuer akte_tage: eine Viertelstunde, damit auch Zonen mit halben Stunden ganze Tage ergeben. */
        private const val EIMER_S = 900L

        /** Satzarten, deren Werte sich ueber den Tag zusammenzaehlen (Namen wie in Boulders Akteneintrag). */
        val SUMMEN = setOf(
            "Steps", "Distance", "ActiveCaloriesBurned", "TotalCaloriesBurned", "FloorsClimbed",
            "ElevationGained", "Hydration", "Nutrition", "WheelchairPushes",
        )

        /** Satzarten mit einer Dauer, die zaehlt: dort zeigt akte_tage die Minuten. */
        val SITZUNGEN = setOf("SleepSession", "ExerciseSession", "MindfulnessSession", "PlannedExerciseSession", "MenstruationPeriod")

        /** Die Einheit der Messreihe je Satzart. */
        val PROBENEINHEIT = mapOf(
            "HeartRate" to "bpm", "Speed" to "m_per_s", "Power" to "watts", "StepsCadence" to "steps_per_min",
            "CyclingPedalingCadence" to "rpm", "SkinTemperature" to "delta_celsius",
        )

        /** Wie in der App (Trenddaten.OHNE_VORGESCHICHTE): in Kiesel-Helper war es die Trainingsdauer. */
        val OHNE_VORGESCHICHTE = setOf("aktiv")

        /** Spalten, die nur die Vorgeschichte kennt (Kiesel-Helper). */
        val VORSPALTEN = linkedMapOf(
            "rem" to "REM sleep of that night [min] (history only)",
            "leicht" to "light sleep of that night [min] (history only)",
            "wach" to "awake time in that night [min] (history only)",
            "schlaf_von" to "went to sleep [min after 18:00 of the previous day] (history only)",
            "schlaf_bis" to "woke up [min after 18:00 of the previous day] (history only)",
            "puls_min" to "estimated resting heart rate [bpm] (history only)",
            "puls_hoch" to "heart rate value as recorded by Kiesel-Helper (history only)",
            "puls_tief" to "heart rate value as recorded by Kiesel-Helper (history only)",
            "puls_nacht_sd" to "spread of the night heart rate as recorded by Kiesel-Helper (history only)",
            "distanz" to "distance as recorded by Kiesel-Helper (history only)",
            "kalorien" to "calories as recorded by Kiesel-Helper (history only)",
            "koffein_letzt" to "last caffeine as recorded by Kiesel-Helper (history only)",
        )

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

        /** Werte der Akte mit bis zu zwei Stellen: Geschwindigkeit, Temperaturdifferenz und Glukose brauchen sie. */
        internal fun wert(x: Double): String =
            if (x == Math.floor(x) && kotlin.math.abs(x) < 1e15) x.toLong().toString()
            else "%.2f".format(java.util.Locale.ROOT, x).trimEnd('0').trimEnd('.')

        /** Ein Feld fuer CSV: Titel und Notizen anderer Apps koennen Komma, Anfuehrungszeichen und Zeilenumbrueche tragen. */
        internal fun csv(s: String): String =
            if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}

private val NUR_LESEN = ToolAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false)

private const val ANLEITUNG =
    "Read-only health data of one person from a Pebble watch, mirrored by the Boulder app, plus everything in " +
        "Health Connect on the phone (all apps). Call 'stand' first: it tells the timezone, which days are available " +
        "and which Health Connect record types exist. Dates are local (YYYY-MM-DD), times local (YYYY-MM-DDTHH:MM). " +
        "A night belongs to the day of waking up. Steps and active minutes of a day appear once the day is over; for " +
        "today use 'puls' or 'minuten' (if minute records are uploaded). Several apps can record the same thing " +
        "(steps from the watch and the phone): Health Connect tools keep sources apart, do not add them up."

private fun schema(vararg felder: Pair<String, String>): ToolSchema = ToolSchema(
    properties = buildJsonObject {
        for ((name, beschreibung) in felder) putJsonObject(name) {
            put("type", "string")
            put("description", beschreibung)
        }
    },
    required = felder.map { it.first },
)

// Die Fehlertexte liest Claude: englisch wie die Antworten.
private fun JsonObject?.text(name: String): String =
    this?.get(name)?.jsonPrimitive?.contentOrNull ?: throw Werkzeuge.Fehler("$name is missing")

private fun JsonObject?.datum(name: String): LocalDate =
    runCatching { LocalDate.parse(text(name)) }.getOrElse { throw Werkzeuge.Fehler("$name: expected a date YYYY-MM-DD") }

private fun JsonObject?.zeit(name: String): LocalDateTime =
    runCatching { LocalDateTime.parse(text(name)) }.getOrElse { throw Werkzeuge.Fehler("$name: expected a time YYYY-MM-DDTHH:MM") }

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
    val bis = "bis" to "last day, YYYY-MM-DD (local), at most ${Werkzeuge.HOECHSTENS_TAGE} days after von"
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
    s.addTool(name = "spo2", description = "Blood oxygen (SpO2) readings of the watch with their sensor quality.",
        inputSchema = schema(von, "bis" to "last day, YYYY-MM-DD (local), at most ${Werkzeuge.HOECHSTENS_SPO2_TAGE} days after von"),
        toolAnnotations = NUR_LESEN) { r -> antwort { w.spo2(r.arguments.datum("von"), r.arguments.datum("bis")) } }
    val art = "art" to "Health Connect record type as listed by 'stand', e.g. HeartRate, Steps, Weight, SleepSession, BloodPressure"
    s.addTool(name = "akte",
        description = "Health Connect records of one type from all apps on the phone, starting in a date range: time, source app, " +
            "values, texts, a summary of samples and parts, and the record id (at most ${Werkzeuge.HOECHSTENS_SAETZE} records).",
        inputSchema = schema(art, von, bis), toolAnnotations = NUR_LESEN) { r ->
        antwort { w.akte(r.arguments.text("art"), r.arguments.datum("von"), r.arguments.datum("bis")) }
    }
    s.addTool(name = "akte_eintrag",
        description = "One Health Connect record in full: all values and texts, every part (sleep stage, segment, lap) and its samples.",
        inputSchema = schema("id" to "record id from the akte tool"), toolAnnotations = NUR_LESEN) { r -> antwort { w.akteEintrag(r.arguments.text("id")) } }
    s.addTool(name = "akte_tage",
        description = "Health Connect data of one type summarised per day and source app: sums for amounts (steps, distance, calories, " +
            "water, nutrition), minimum/mean/maximum for measurements (weight, blood pressure, SpO2 ...) and samples (heart rate ...), " +
            "count and minutes for sessions (sleep, exercise, mindfulness). A record counts on the day it starts, " +
            "so a night counts on the evening before.",
        inputSchema = schema(art, von, bis), toolAnnotations = NUR_LESEN) { r ->
        antwort { w.akteTage(r.arguments.text("art"), r.arguments.datum("von"), r.arguments.datum("bis")) }
    }
    s.addTool(name = "akte_verlauf",
        description = "Samples of one Health Connect type (heart rate, speed, power, cadence, skin temperature) per source app, " +
            "in 5-minute blocks up to 24 h or hourly blocks up to 7 days.",
        inputSchema = schema(art, vonZeit, "bis" to "end, YYYY-MM-DDTHH:MM (local), at most 7 days after von"), toolAnnotations = NUR_LESEN) { r ->
        antwort { w.akteVerlauf(r.arguments.text("art"), r.arguments.zeit("von"), r.arguments.zeit("bis")) }
    }
    return s
}
