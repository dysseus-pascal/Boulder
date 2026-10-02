package coredevices.pebble.kiesel

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import coredevices.pebble.R
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.uuid.Uuid

/**
 * SupCycle: was heute ansteht und was davon genommen ist - wie in
 * Kiesel-Helper (Aufgaben.supplemente, Supplemente).
 *
 * DIE AKTE KENNT KEIN "GENOMMEN". Was ihr am naechsten kommt, ist ein
 * Ernaehrungseintrag: er bekommt den Namen, aber keine Naehrstoffmengen,
 * denn die weiss SupCycle nicht. Die Quote des Tages (faellig und davon
 * genommen) steht deshalb im eigenen Speicher.
 */
internal object SupCycle {
    private const val TAG = "SupCycle"
    private const val HEUTE = "kiesel-supplemente"
    private const val QUOTEN = "kiesel-supplemente-quoten"
    private const val NACHTRAG = "kiesel-supplemente-nachtrag"

    /** So lange wartet ein abgehaktes Praeparat eines frueheren Tages auf die Akte (wie die Ablage im Mithoerer). */
    private const val NACHTRAG_TAGE = 30L

    val UUID: Uuid = Uuid.parse("33ce868a-2beb-4335-9421-4d741ed16eb3")

    /**
     * SupCycle meldet nach jeder Einnahme den Kalendertag (JJJJMMTT) und zwei
     * Bitmasken je Platz: was heute faellig ist und was abgehakt wurde. Die
     * Namen kommen seit SupCycle 0.10.0 mit, durch Zeilenumbruch getrennt,
     * auch die leeren - die Bitmasken zaehlen Plaetze, nicht Eintraege. Der
     * ganze Plan (PLAN) faehrt in jeder Meldung mit.
     */
    const val SC_PLAN = 10001
    const val SC_TODAY = 10039
    const val SC_DUE = 10040
    const val SC_TAKEN = 10041
    const val SC_NAMES = 10044

    /** Ein Platz im Plan: 26 Byte, davon 16 fuer den Namen (plan.h in SupCycle). */
    private const val PLATZ_BYTES = 26
    private const val NAME_BYTES = 16

    /** Ein Praeparat, das heute ansteht - [zeit] ist seine Planzeit, null ohne Plan. */
    data class Eintrag(val name: String, val genommen: Boolean, val zeit: LocalTime? = null)

    /**
     * Ein Platz des Plans, wie SupCycle ihn fuehrt: Planzeit, alle [alle]
     * Tage, [wochenAn] Wochen Einnahme und [wochenAus] Pause ab dem
     * [anker] (Tage seit 1970, wie die Uhr sie zaehlt, siehe [uhrTag]).
     */
    data class Platz(
        val name: String, val zeit: LocalTime, val benutzt: Boolean,
        val alle: Int, val wochenAn: Int, val wochenAus: Int, val anker: Long,
    )

    data class Stand(val tag: LocalDate, val namen: List<String>, val faellig: Long, val genommen: Long, val plan: List<Platz> = emptyList()) {
        /**
         * Nur was heute faellig ist, in der Reihenfolge des Plans. Ein
         * Praeparat in der Pausenwoche gehoert nicht auf die Liste - ein
         * "offen" daneben waere ein Vorwurf.
         */
        fun heute(platz: (Int) -> String): List<Eintrag> = (0 until 64)
            .filter { ((faellig shr it) and 1L) == 1L }
            .map { i ->
                Eintrag(
                    namen.getOrNull(i)?.ifBlank { null } ?: platz(i + 1), ((genommen shr i) and 1L) == 1L,
                    plan.getOrNull(i)?.takeIf { it.benutzt }?.zeit,
                )
            }
    }

    /** Welche Plaetze seit dem letzten Bericht neu abgehakt wurden - nur faellige. */
    fun neuGenommen(faellig: Long, genommen: Long, vorher: Long): List<Int> =
        (0 until 64).filter { ((genommen and faellig and vorher.inv()) shr it) and 1L == 1L }

    /** Die Plaetze als Maske. */
    fun maske(plaetze: List<Int>): Long = plaetze.fold(0L) { m, i -> m or (1L shl i) }

    /**
     * Die Plaetze, die in [alte] und [neue] gleich heissen. Nur fuer sie gilt,
     * was zum Platz gemerkt ist (eingetragen, verworfen): ein umbenannter Platz
     * ist ein anderes Praeparat, sein Haken ein neuer.
     */
    fun gleicheNamen(alte: List<String>, neue: List<String>): Long =
        (0 until 64).fold(0L) { m, i -> if (alte.getOrNull(i).orEmpty() == neue.getOrNull(i).orEmpty()) m or (1L shl i) else m }

    /** Was an einem Tag abgehakt, aber nicht in der Akte ist - und nicht im Strahl verworfen. */
    fun fehlt(faellig: Long, genommen: Long, eingetragen: Long, verworfen: Long): Long =
        genommen and faellig and eingetragen.inv() and verworfen.inv()

    /** Ob zwei Meldungen denselben Tag betreffen - die juengere ersetzt dann die aeltere ganz. */
    fun selberTag(a: KieselNachricht, b: KieselNachricht): Boolean = a.zahlen[SC_TODAY] == b.zahlen[SC_TODAY]

    /**
     * Welche eingetragenen Haken die Uhr zuruecknahm. NUR WAS WEITER ANSTEHT
     * UND GLEICH HEISST: die Uhr loescht einen Haken auch, wenn ein Platz einen
     * neuen Namen bekommt, und ein Zykluswechsel nimmt das Faellige weg - die
     * Einnahme davor war aber echt und bleibt in der Akte.
     */
    fun zurueckgenommen(eingetragen: Long, faellig: Long, genommen: Long, alteNamen: List<String>, neueNamen: List<String>): List<Int> =
        (0 until 64).filter { i ->
            val bit = 1L shl i
            eingetragen and bit != 0L && faellig and bit != 0L && genommen and bit == 0L &&
                alteNamen.getOrNull(i).orEmpty() == neueNamen.getOrNull(i).orEmpty()
        }

    /**
     * Der Plan aus den Bytes der Uhr, je Platz: Name 0..15, Stunde 16, Minute
     * 17, benutzt 18, alle X Tage 19, Wochen an 20 und aus 21, Ankertag 22..25
     * little endian. Unsinn wird begrenzt wie in prv_sanitize auf der Uhr -
     * sonst rechnete das Telefon mit anderen Werten als sie. Leer bei einer
     * fremden Laenge.
     */
    fun plan(bytes: ByteArray): List<Platz> {
        if (bytes.isEmpty() || bytes.size % PLATZ_BYTES != 0) return emptyList()
        return (0 until bytes.size / PLATZ_BYTES).map { i ->
            val o = i * PLATZ_BYTES
            fun u(k: Int) = bytes[o + k].toInt() and 0xFF
            val laenge = (0 until NAME_BYTES - 1).firstOrNull { bytes[o + it] == 0.toByte() } ?: (NAME_BYTES - 1)
            val name = String(bytes, o, laenge, Charsets.UTF_8)
            val anker = u(22) or (u(23) shl 8) or (u(24) shl 16) or (u(25) shl 24)
            Platz(
                name = name,
                zeit = LocalTime.of(u(16).takeIf { it <= 23 } ?: 8, u(17).takeIf { it <= 59 } ?: 0),
                benutzt = u(18) != 0 && name.isNotEmpty(),
                alle = u(19).coerceIn(1, 30),
                wochenAn = u(20).coerceAtMost(52),
                wochenAus = u(21).coerceAtMost(52),
                anker = anker.toLong(),
            )
        }
    }

    /**
     * Der Tag, wie SupCycle ihn zaehlt (plan_today): die Ortsmitternacht in
     * Sekunden seit 1970, ganzzahlig durch einen Tag. Oestlich von Greenwich
     * ist das der Kalendertag davor - die Uhr und ihr Anker zaehlen gleich,
     * also das Telefon auch.
     */
    fun uhrTag(tag: LocalDate, zone: ZoneId): Long = Math.floorDiv(tag.atStartOfDay(zone).toEpochSecond(), 86_400L)

    /** Ob [p] am Tag [tag] ansteht - plan_due_on und cycle.c der Uhr nachgerechnet. */
    fun faelligAm(p: Platz, tag: Long): Boolean {
        if (!p.benutzt) return false
        val seit = maxOf(0L, tag - p.anker)
        if (p.alle > 1 && seit % p.alle != 0L) return false
        if (p.wochenAn == 0 || p.wochenAus == 0) return true
        val an = p.wochenAn * 7L
        return seit % (an + p.wochenAus * 7L) < an
    }

    fun faellig(plan: List<Platz>, tag: Long): Long =
        plan.withIndex().fold(0L) { maske, (i, p) -> if (faelligAm(p, tag)) maske or (1L shl i) else maske }

    /**
     * Der Stand fuer [heute]. HAT SICH DIE UHR HEUTE NOCH NICHT GEMELDET,
     * rechnet das Telefon aus dem zuletzt gemeldeten Plan, was ansteht -
     * genommen ist dann nichts. Sonst fehlte die Zeile bis zum ersten Haken.
     */
    fun standFuer(heute: LocalDate, zone: ZoneId, gemeldet: Stand?, plan: List<Platz>): Stand? = when {
        gemeldet != null && gemeldet.tag == heute -> gemeldet.copy(plan = plan)
        plan.isEmpty() -> null
        else -> Stand(heute, plan.map { it.name }, faellig(plan, uhrTag(heute, zone)), 0L, plan)
    }

    private fun gespeicherterPlan(p: SharedPreferences): List<Platz> =
        p.getString("plan", null)?.let { runCatching { plan(Base64.decode(it, Base64.DEFAULT)) }.getOrNull() }.orEmpty()

    /**
     * Der Stand von heute, oder nichts. Gestern ist hier nicht interessant.
     * Im Tagesstrahl Geloeschtes zaehlt nicht als genommen (siehe [verwirf]).
     */
    fun heute(context: Context): Stand? {
        val p = context.getSharedPreferences(HEUTE, Context.MODE_PRIVATE)
        val gemeldet = p.getString("tag", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { tag ->
            Stand(tag, p.getString("namen", "")!!.split("\n"), p.getLong("faellig", 0), p.getLong("genommen", 0) and p.getLong("verworfen", 0).inv())
        }
        return standFuer(LocalDate.now(), ZoneId.systemDefault(), gemeldet, gespeicherterPlan(p))
    }

    /** Die Quote je Tag (faellig, genommen) - fuer Verlauf und Trends. */
    fun quoten(context: Context): Map<LocalDate, Pair<Int, Int>> =
        context.getSharedPreferences(QUOTEN, Context.MODE_PRIVATE).all.mapNotNull { (k, v) ->
            val tag = runCatching { LocalDate.parse(k) }.getOrNull() ?: return@mapNotNull null
            val (f, g) = (v as? String)?.split(":")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 } ?: return@mapNotNull null
            tag to (f to g)
        }.toMap()

    /**
     * UND, nicht blosses Zaehlen: abgehakt bleibt abgehakt, auch wenn ein
     * Praeparat heute gar nicht dran waere. In der Quote hat es nichts verloren.
     */
    private fun merkeQuote(context: Context, tag: String, faellig: Long, genommen: Long): Pair<Int, Int> {
        val wieViele = java.lang.Long.bitCount(faellig)
        val davon = java.lang.Long.bitCount(genommen and faellig)
        context.getSharedPreferences(QUOTEN, Context.MODE_PRIVATE).edit().putString(tag, "$wieViele:$davon").apply()
        return wieViele to davon
    }

    /**
     * Tag, Platz und Name: meldet SupCycle denselben Haken noch einmal,
     * ersetzt die Akte den Eintrag. DER NAME GEHOERT DAZU, weil ein Platz am
     * selben Tag umbenannt werden kann - der Haken des neuen Praeparats
     * ersetzte sonst die Einnahme des alten.
     */
    fun kennung(tag: LocalDate, platz: Int, name: String) = "${kennungOhneName(tag, platz)}-${Integer.toHexString(name.hashCode())}"

    /** Die Kennung bis Boulder 0.42.1, ohne Namen - zum Loeschen von Eintraegen dieser Fassungen. */
    private fun kennungOhneName(tag: LocalDate, platz: Int) = "supcycle-$tag-$platz"

    /** Der Platz aus einer Kennung vom Tag [tag] - null fuer eine fremde oder eine alte ohne Kennung. */
    fun platzAus(kennung: String?, tag: String): Int? =
        kennung?.removePrefix("supcycle-$tag-")?.takeIf { it != kennung }?.substringBefore('-')?.toIntOrNull()?.takeIf { it in 0..63 }

    /**
     * Einnahmen, die im Tagesstrahl geloescht wurden: sie zaehlen in Liste,
     * Satz und Quote nicht mehr als genommen, obwohl die Uhr den Haken behaelt.
     * Nimmt die Uhr ihn zurueck und setzt ihn neu, gilt er wieder.
     */
    fun verwirf(context: Context, kennungen: List<String?>) {
        val p = context.getSharedPreferences(HEUTE, Context.MODE_PRIVATE)
        val tag = p.getString("tag", null) ?: return
        val maske = kennungen.mapNotNull { platzAus(it, tag) }.fold(0L) { m, i -> m or (1L shl i) }
        if (maske == 0L) return
        val genommen = p.getLong("genommen", 0)
        val verworfen = p.getLong("verworfen", 0) or maske
        p.edit()
            .putLong("verworfen", verworfen)
            .putLong("eingetragen", p.getLong("eingetragen", genommen) and maske.inv())
            .apply()
        merkeQuote(context, tag, p.getLong("faellig", 0), genommen and verworfen.inv())
    }

    /**
     * Die Eintraege zurueckgenommener Haken loeschen. Nach der Kennung - und,
     * fuer die Eintraege bis 0.33.0 ohne Kennung, nach Namen: eigener Eintrag
     * von heute, ohne Koffein und ohne Energie, mit dem Namen des Platzes.
     * True, wenn das Loeschen durchging.
     */
    private suspend fun entferne(context: Context, klient: HealthConnectClient, tag: LocalDate, plaetze: Map<Int, String>): Boolean = try {
        val von = tag.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val heute = klient.readRecords(
            ReadRecordsRequest(
                NutritionRecord::class,
                TimeRangeFilter.between(von, von.plus(Duration.ofDays(1))),
                dataOriginFilter = setOf(DataOrigin(context.packageName)),
            )
        ).records
        val kennungen = plaetze.flatMap { (platz, name) -> listOf(kennung(tag, platz, name), kennungOhneName(tag, platz)) }.toSet()
        val namen = plaetze.values.filter { it.isNotBlank() }.toSet()
        val weg = heute.filter { r ->
            r.metadata.clientRecordId in kennungen ||
                (r.metadata.clientRecordId == null && r.caffeine == null && r.energy == null && r.name in namen)
        }.map { it.metadata.id }
        if (weg.isNotEmpty()) klient.deleteRecords(NutritionRecord::class, recordIdsList = weg, clientRecordIdsList = emptyList())
        true
    } catch (e: Exception) {
        Log.w(TAG, "Zurueckgenommenes Praeparat nicht geloescht: " + e.message)
        false
    }

    /**
     * [plaetze] des Tages [tag] in die Akte - die Maske der eingetragenen.
     * DIE UHR SCHICKT KEINE HAKENZEIT: der Eintrag traegt die Ankunft der
     * Meldung. Kommt ein liegengebliebener Stand erst an einem spaeteren Tag
     * dran, steht er auf der Planzeit seines Tages.
     */
    private suspend fun trageEin(
        context: Context, klient: HealthConnectClient, tag: LocalDate, plaetze: List<Int>, name: (Int) -> String, plan: List<Platz>,
    ): Long {
        val jetzt = Instant.now()
        var eingetragen = 0L
        for (platz in plaetze) {
            val n = name(platz)
            val wann = if (tag == LocalDate.now()) jetzt
            else minOf(jetzt, tag.atTime(plan.getOrNull(platz)?.zeit ?: LocalTime.of(23, 59)).atZone(ZoneId.systemDefault()).toInstant())
            try {
                klient.insertRecords(listOf(NutritionRecord(
                    startTime = wann,
                    startZoneOffset = versatz(wann),
                    endTime = wann.plusSeconds(60),
                    endZoneOffset = versatz(wann),
                    name = n,
                    // manualEntry, NICHT manualEntryWithId: das setzt die
                    // Kennung der Akte selbst (fuer Updates), und die
                    // eigene Kennung blieb leer - bis 0.33.0 so geschehen.
                    metadata = Metadata.manualEntry(kennung(tag, platz, n), 0L, Device(type = Device.TYPE_PHONE)),
                )))
                eingetragen = eingetragen or (1L shl platz)
            } catch (e: Exception) {
                Log.w(TAG, "Praeparat nicht eingetragen: " + e.message)
            }
        }
        return eingetragen
    }

    /** Was je frueherem Tag noch in die Akte muss (siehe [nachtrag]). */
    private fun merkeNachtrag(context: Context, tag: LocalDate, fehlt: Long) {
        val p = context.getSharedPreferences(NACHTRAG, Context.MODE_PRIVATE)
        val grenze = LocalDate.now().minusDays(NACHTRAG_TAGE)
        p.edit().apply {
            // Was laenger wartet, ist verfallen - wie die Ablage, aus der es kaeme.
            p.all.keys.filter { k -> runCatching { LocalDate.parse(k) }.getOrNull()?.isBefore(grenze) ?: true }.forEach { remove(it) }
            if (fehlt == 0L) remove(tag.toString()) else putLong(tag.toString(), fehlt)
            apply()
        }
    }

    /**
     * Ein liegengebliebener Stand eines FRUEHEREN Tages: er traegt nur nach,
     * was an jenem Tag abgehakt, aber nicht in die Akte kam - auf die
     * Planzeit jenes Tages. Liste, Quote und der Stand von heute bleiben, wie
     * sie sind; was er nicht nachtraegt, behaelt [merkeNachtrag] fuer den
     * naechsten Start.
     */
    private suspend fun nachtrag(context: Context, n: KieselNachricht, tag: LocalDate, faellig: Long, genommen: Long): Verarbeitet {
        val fehlt = context.getSharedPreferences(NACHTRAG, Context.MODE_PRIVATE).getLong(tag.toString(), 0L)
        val plaetze = neuGenommen(faellig, genommen, fehlt.inv())
        if (plaetze.isEmpty()) return Verarbeitet(null)
        val klient = Akte(context).bereit() ?: return Verarbeitet(context.getString(R.string.ks_g_akte_fehlt), gescheitert = true)
        val namen = n.texte[SC_NAMES]?.split("\n").orEmpty()
        val plan = n.bytes[SC_PLAN]?.let { plan(it) }.orEmpty()
        val eingetragen = trageEin(context, klient, tag, plaetze, { namen.getOrNull(it)?.ifBlank { null } ?: context.getString(R.string.ks_s_praeparat_n, it + 1) }, plan)
        val rest = maske(plaetze) and eingetragen.inv()
        merkeNachtrag(context, tag, rest)
        return if (rest == 0L) Verarbeitet(null) else Verarbeitet(context.getString(R.string.ks_a_nicht_eingetragen), gescheitert = true)
    }

    suspend fun verarbeite(context: Context, n: KieselNachricht): Verarbeitet? {
        val ymd = n.zahlen[SC_TODAY] ?: return null
        val faellig = n.zahlen[SC_DUE] ?: return null
        val genommen = n.zahlen[SC_TAKEN] ?: 0L
        val tag = try {
            LocalDate.of((ymd / 10000).toInt(), ((ymd / 100) % 100).toInt(), (ymd % 100).toInt())
        } catch (e: Exception) {
            Log.w(TAG, "Unbrauchbares Datum $ymd")
            return null
        }
        val p = context.getSharedPreferences(HEUTE, Context.MODE_PRIVATE)
        val gemerkt = p.getString("tag", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        // EIN AELTERER TAG UEBERSCHREIBT DEN STAND NICHT - ein liegengebliebener
        // Stand von gestern drehte sonst den von heute zurueck. Er traegt nur nach.
        if (gemerkt != null && tag.isBefore(gemerkt)) return nachtrag(context, n, tag, faellig, genommen)
        if (!Riegel.neu(context, "supcycle", "$ymd:$faellig:$genommen")) return null

        val gleicherTag = gemerkt == tag
        val bekannt = p.getString("namen", "")!!.split("\n")
        // Nur ueberschreiben, wenn welche kamen: eine aeltere Fassung von
        // SupCycle schickt keine, dann bleiben die zuletzt bekannten stehen.
        val namen = n.texte[SC_NAMES]?.split("\n") ?: bekannt
        fun name(platz: Int) = namen.getOrNull(platz)?.ifBlank { null } ?: context.getString(R.string.ks_s_praeparat_n, platz + 1)
        val planBytes = n.bytes[SC_PLAN]?.takeIf { plan(it).isNotEmpty() }
        val plan = planBytes?.let { plan(it) } ?: gespeicherterPlan(p)
        // WAS SCHON IN DER AKTE STEHT, nicht was die Uhr abgehakt meldet: ein
        // gescheiterter Eintrag kommt so mit dem naechsten Bericht wieder
        // dran. Bis Boulder 0.42 gab es nur "genommen".
        val vorherGenommen = if (gleicherTag) p.getLong("genommen", 0) else 0L
        val gleich = gleicheNamen(bekannt, namen)
        var eingetragen = if (gleicherTag) p.getLong("eingetragen", vorherGenommen) and gleich else 0L
        val verworfen = if (gleicherTag) p.getLong("verworfen", 0) and genommen and gleich else 0L
        // DER VORTAG GEHT NICHT VERLOREN: was an ihm abgehakt, aber nicht
        // eingetragen ist, holt sein liegengebliebener Stand nach (siehe nachtrag).
        if (gemerkt != null && !gleicherTag) {
            val g = p.getLong("genommen", 0)
            merkeNachtrag(context, gemerkt, fehlt(p.getLong("faellig", 0), g, p.getLong("eingetragen", g), p.getLong("verworfen", 0)))
        }

        val klient = Akte(context).bereit()
        val neu = neuGenommen(faellig, genommen, eingetragen or verworfen)
        val geschrieben = if (klient != null) trageEin(context, klient, tag, neu, { name(it) }, plan) else 0L
        eingetragen = eingetragen or geschrieben
        val gescheitert = (maske(neu) and geschrieben.inv()) != 0L

        // WIEDER ABGEHAKT HEISST NICHT GENOMMEN: nimmt die Uhr einen Haken
        // zurueck, geht auch der Eintrag aus der Akte. Sonst stuende ein
        // Praeparat im Tagesstrahl als genommen, das die Uhr als offen fuehrt.
        val zurueck = zurueckgenommen(eingetragen, faellig, genommen, bekannt, namen)
        val geloescht = zurueck.isNotEmpty() && klient != null &&
            entferne(context, klient, tag, zurueck.associateWith { name(it) })
        // Ein Haken, den die Uhr nicht mehr fuehrt, steht fuer keinen Eintrag
        // mehr - ausser sein Loeschen scheiterte: das holt der naechste Bericht nach.
        val bleibt = if (geloescht) 0L else maske(zurueck)
        eingetragen = (eingetragen and genommen) or bleibt

        p.edit().apply {
            putString("tag", tag.toString())
            putString("namen", namen.joinToString("\n"))
            putLong("faellig", faellig)
            putLong("genommen", genommen)
            putLong("eingetragen", eingetragen)
            putLong("verworfen", verworfen)
            if (planBytes != null) putString("plan", Base64.encodeToString(planBytes, Base64.NO_WRAP))
            apply()
        }
        val (wieViele, davon) = merkeQuote(context, tag.toString(), faellig, genommen and verworfen.inv())
        if (gescheitert) {
            // Den Riegel oeffnen: derselbe Stand soll beim naechsten Mal wieder durchkommen.
            Riegel.loese(context, "supcycle")
            return Verarbeitet(context.getString(if (klient == null) R.string.ks_g_akte_fehlt else R.string.ks_a_nicht_eingetragen), gescheitert = true)
        }
        return Verarbeitet(context.getString(R.string.ks_s_davon_von, davon, wieViele))
    }
}
