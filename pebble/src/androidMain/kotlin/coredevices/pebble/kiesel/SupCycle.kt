package coredevices.pebble.kiesel

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import co.touchlab.kermit.Logger
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import coredevices.pebble.R
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
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
    private val logger = Logger.withTag(TAG)
    private const val HEUTE = "kiesel-supplemente"
    private const val QUOTEN = "kiesel-supplemente-quoten"
    private const val NACHTRAG = "kiesel-supplemente-nachtrag"

    /** So lange wartet ein abgehaktes Praeparat eines frueheren Tages auf die Akte (wie die Ablage im Mithoerer). */
    internal const val NACHTRAG_TAGE = 30L

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

    // Nach SupCycle 0.13.3. Eine aeltere Uhr-App schickt keine Zeiten und ueberhoert UNTAKE.
    const val SC_TAKEN_AT = 10045 //< je Platz 4 Byte little endian, Sekunden seit 1970, 0 = offen
    const val SC_UNTAKE = 10046 //< an die Uhr: Bitmaske, dazu TODAY und die gemeinten TAKEN_AT

    /**
     * Wie der Plan zu lesen ist - die Uhr schickt es mit jedem Plan (plan.h,
     * SC_PLANFASSUNG). Fehlt der Schluessel, ist es eine Uhr-App bis 0.15.0:
     * [PLANFASSUNG_ALT]. Die Haken haengen in beiden Faellen am Datum (TODAY),
     * nicht an der Fassung.
     */
    const val SC_PLANFASSUNG = 10048

    /** Anker als Ortsmitternacht durch 86400; Einnahmewochen ohne Pause laufen endlos. */
    const val PLANFASSUNG_ALT = 1

    /** Anker als Kalendertag; Einnahmewochen ohne Pause sind eine Kur, die endet. */
    const val PLANFASSUNG_KALENDER = 2

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

    /**
     * [fassung]: wie der [plan] zu lesen ist (siehe [uhrTag]) - die Seite
     * Stack rechnet damit die Kurwoche wie die Uhr.
     */
    data class Stand(
        val tag: LocalDate, val namen: List<String>, val faellig: Long, val genommen: Long, val plan: List<Platz> = emptyList(),
        val fassung: Int = PLANFASSUNG_ALT,
    ) {
        /**
         * Nur was heute faellig ist, in der Reihenfolge des Plans. Ein
         * Praeparat in der Pausenwoche gehoert nicht auf die Liste - ein
         * "offen" daneben waere ein Vorwurf.
         */
        fun heute(platz: (Int) -> String): List<Eintrag> = heuteJePlatz(platz).map { it.second }

        /**
         * Dasselbe mit dem Platz (0..63) jedes Eintrags: an ihm haengen seine
         * Kennung in der Akte ([kennung]) und seine Kur im [plan].
         */
        fun heuteJePlatz(platz: (Int) -> String): List<Pair<Int, Eintrag>> = (0 until 64)
            .filter { ((faellig shr it) and 1L) == 1L }
            .map { i ->
                i to Eintrag(
                    namen.getOrNull(i)?.ifBlank { null } ?: platz(i + 1), ((genommen shr i) and 1L) == 1L,
                    plan.getOrNull(i)?.takeIf { it.benutzt }?.zeit,
                )
            }
    }

    /** Die wievielte Einnahmewoche einer Kur heute ist ("week 3 of 8"): [woche] von [von]. */
    data class Kurwoche(val woche: Int, val von: Int)

    /**
     * Die Kurwoche von [p] am Tag [tag] (wie [uhrTag] ihn zaehlt) - nach
     * derselben Rechnung wie [faelligAm], damit Woche und Haken der Uhr
     * zusammenpassen. Null ohne Kur ([Platz.wochenAn] 0), in der Pause und
     * fuer Einnahmewochen ohne Pause in der alten Fassung: die liefen endlos,
     * eine "Woche 40 von 8" waere Unsinn.
     */
    fun kurwoche(p: Platz, tag: Long, fassung: Int): Kurwoche? {
        if (!p.benutzt || p.wochenAn == 0) return null
        if (p.wochenAus == 0 && fassung < PLANFASSUNG_KALENDER) return null
        val seit = maxOf(0L, tag - p.anker)
        val an = p.wochenAn * 7L
        val imZyklus = if (p.wochenAus == 0) seit else seit % (an + p.wochenAus * 7L)
        if (imZyklus >= an) return null
        return Kurwoche((imZyklus / 7).toInt() + 1, p.wochenAn)
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

    /** Null von einer Uhr-App, die keine Hakenzeiten schickt. */
    fun hakenzeiten(bytes: ByteArray?): List<Long>? {
        if (bytes == null || bytes.isEmpty() || bytes.size % 4 != 0) return null
        return (0 until bytes.size / 4).map { i -> (0 until 4).fold(0L) { z, b -> z or ((bytes[i * 4 + b].toLong() and 0xFF) shl (8 * b)) } }
    }

    fun hakenzeitenBytes(zeiten: List<Long>): ByteArray = ByteArray(zeiten.size * 4) { k -> (zeiten[k / 4] shr (8 * (k % 4))).toByte() }

    private fun zeitenText(zeiten: List<Long>) = zeiten.joinToString(",")
    private fun zeitenAus(text: String?): List<Long>? = text?.split(",")?.mapNotNull { it.toLongOrNull() }

    /**
     * Die Hakenzeit, wenn sie in den Tag faellt: offline Abgehaktes kommt erst
     * beim Wiederverbinden an. Nie nach [jetzt], die Akte lehnt Zukunft ab.
     */
    fun eintragZeit(tag: LocalDate, haken: Long?, planZeit: LocalTime?, jetzt: Instant, zone: ZoneId): Instant {
        val t = haken?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it) }
        if (t != null && t.atZone(zone).toLocalDate() == tag) return minOf(t, jetzt)
        if (tag == jetzt.atZone(zone).toLocalDate()) return jetzt
        return minOf(jetzt, tag.atTime(planZeit ?: LocalTime.of(23, 59)).atZone(zone).toInstant())
    }

    /**
     * Ein Haken mit neuer Zeit ist eine neue Einnahme. Fehlen Zeiten auf einer
     * Seite, gelten alle als gleich. Einen zweiten Eintrag gibt es dabei nicht:
     * gleicher Name heisst gleiche [kennung], und die Akte ersetzt den alten
     * Eintrag (siehe [fassung]).
     */
    fun gleicheZeiten(alte: List<Long>?, neue: List<Long>?): Long =
        if (alte == null || neue == null) -1L
        else (0 until 64).fold(0L) { m, i -> if (alte.getOrElse(i) { 0L } == neue.getOrElse(i) { 0L }) m or (1L shl i) else m }

    /**
     * Die Fassung eines Eintrags fuer die Akte: seine Hakenzeit. Die Akte
     * ersetzt einen Eintrag gleicher Kennung nur bei hoeherer (laut Doku) oder
     * gleicher Fassung (laut Quelltext) - ein neu gesetzter Haken ist spaeter,
     * also hoeher, und verschiebt den Eintrag in beiden Lesarten auf seine Zeit.
     * Ohne Zeit 0 wie bis Boulder 0.45.
     */
    fun fassung(haken: Long?): Long = haken?.takeIf { it > 0 } ?: 0L

    /** Die Uhr nimmt einen Haken nur zurueck, solange er die Zeit aus [zeiten] traegt (plan_untake). */
    data class Ruecknahme(val tag: LocalDate, val maske: Long, val zeiten: List<Long>) {
        fun felder(): Map<Int, Any> = mapOf(
            SC_TODAY to tag.year * 10000 + tag.monthValue * 100 + tag.dayOfMonth,
            SC_UNTAKE to maske.toInt(),
            SC_TAKEN_AT to hakenzeitenBytes(zeiten),
        )
    }

    /** Jeder Platz behaelt die Zeit, mit der er verworfen wurde. */
    fun vorgemerkt(offen: Ruecknahme?, tag: LocalDate, maske: Long, zeiten: List<Long>): Ruecknahme {
        val alt = offen?.takeIf { it.tag == tag }
        val n = maxOf(zeiten.size, alt?.zeiten?.size ?: 0)
        val z = (0 until n).map { i -> if ((maske shr i) and 1L == 1L) zeiten.getOrElse(i) { 0L } else alt?.zeiten?.getOrElse(i) { 0L } ?: 0L }
        return Ruecknahme(tag, (alt?.maske ?: 0L) or maske, z)
    }

    /**
     * Ein Haken, der weg ist oder eine neue Zeit traegt, braucht keinen Befehl
     * mehr. NUR DER STAND DER UHR ERLEDIGT IHN, nicht ihre Bestaetigung: die
     * Uhr-App uebergeht einen Befehl fuer einen anderen Tag (Uhrzeit nach einem
     * Neustart noch falsch) und bestaetigt ihn trotzdem.
     */
    fun nochOffen(offen: Ruecknahme?, tag: LocalDate, genommen: Long, zeiten: List<Long>?): Ruecknahme? {
        if (offen == null || offen.tag != tag || zeiten == null) return null
        val maske = (0 until 64).fold(0L) { m, i ->
            val bit = 1L shl i
            val bleibt = offen.maske and bit != 0L && genommen and bit != 0L && zeiten.getOrElse(i) { 0L } == offen.zeiten.getOrElse(i) { 0L }
            if (bleibt) m or bit else m
        }
        return if (maske == 0L) null else offen.copy(maske = maske)
    }

    private fun liesRuecknahme(p: SharedPreferences): Ruecknahme? {
        val tag = tagOderNull(p.getString("zurueck_tag", null), TAG) ?: return null
        val maske = p.getLong("zurueck", 0L).takeIf { it != 0L } ?: return null
        return Ruecknahme(tag, maske, zeitenAus(p.getString("zurueck_zeiten", null)).orEmpty())
    }

    private fun SharedPreferences.Editor.schreibeRuecknahme(r: Ruecknahme?): SharedPreferences.Editor =
        if (r == null) remove("zurueck_tag").remove("zurueck").remove("zurueck_zeiten")
        else putString("zurueck_tag", r.tag.toString()).putLong("zurueck", r.maske).putString("zurueck_zeiten", zeitenText(r.zeiten))

    /** Weckt den laufenden Mithoerer: ein Befehl fuer die Uhr liegt bereit. */
    val anstoss = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Synchronized
    fun ruecknahme(context: Context): Ruecknahme? {
        val p = context.getSharedPreferences(HEUTE, Context.MODE_PRIVATE)
        val r = liesRuecknahme(p) ?: return null
        if (r.tag == LocalDate.now()) return r
        // Nach Mitternacht hat die Uhr ihre Haken ohnehin geleert.
        p.edit().schreibeRuecknahme(null).apply()
        return null
    }

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
     * Der Tag, wie SupCycle ihn zaehlt (plan_today) - je nach [fassung] des
     * Plans, denn der Anker zaehlt genauso.
     *
     * [PLANFASSUNG_KALENDER] ist der Kalendertag (days_from_civil in
     * SupCycles kalender.c, hier toEpochDay). Bis SupCycle 0.15.0
     * ([PLANFASSUNG_ALT]) war es die Ortsmitternacht in Sekunden seit 1970,
     * ganzzahlig durch einen Tag: oestlich von Greenwich der Kalendertag
     * davor, und in London am Umstellungssonntag mitten am Tag springend
     * (Audit M1). Eine alte Uhr-App rechnet so weiter, also das Telefon mit
     * ihrem Plan auch.
     */
    fun uhrTag(tag: LocalDate, zone: ZoneId, fassung: Int): Long =
        if (fassung >= PLANFASSUNG_KALENDER) tag.toEpochDay()
        else Math.floorDiv(tag.atStartOfDay(zone).toEpochSecond(), 86_400L)

    /** Die Fassung aus einer Meldung der Uhr - ohne Schluessel die alte. */
    fun planfassung(n: KieselNachricht): Int = n.zahlen[SC_PLANFASSUNG]?.toInt() ?: PLANFASSUNG_ALT

    /**
     * Ob [p] am Tag [tag] ansteht - plan_due_on und cycle.c der Uhr
     * nachgerechnet, in der [fassung] des Plans.
     */
    fun faelligAm(p: Platz, tag: Long, fassung: Int): Boolean {
        if (!p.benutzt) return false
        val seit = maxOf(0L, tag - p.anker)
        if (p.alle > 1 && seit % p.alle != 0L) return false
        if (p.wochenAn == 0) return true
        val an = p.wochenAn * 7L
        // Einnahmewochen ohne Pause sind seit Fassung 2 eine Kur, die
        // nach [wochenAn] Wochen endet (Audit N6); vorher liefen sie endlos.
        if (p.wochenAus == 0) return fassung < PLANFASSUNG_KALENDER || seit < an
        return seit % (an + p.wochenAus * 7L) < an
    }

    fun faellig(plan: List<Platz>, tag: Long, fassung: Int): Long =
        plan.withIndex().fold(0L) { maske, (i, p) -> if (faelligAm(p, tag, fassung)) maske or (1L shl i) else maske }

    /**
     * Der Stand fuer [heute]. HAT SICH DIE UHR HEUTE NOCH NICHT GEMELDET,
     * rechnet das Telefon aus dem zuletzt gemeldeten Plan, was ansteht -
     * genommen ist dann nichts. Sonst fehlte die Zeile bis zum ersten Haken.
     * [fassung]: die des gespeicherten Plans.
     */
    fun standFuer(heute: LocalDate, zone: ZoneId, gemeldet: Stand?, plan: List<Platz>, fassung: Int): Stand? = when {
        gemeldet != null && gemeldet.tag == heute -> gemeldet.copy(plan = plan, fassung = fassung)
        plan.isEmpty() -> null
        else -> Stand(heute, plan.map { it.name }, faellig(plan, uhrTag(heute, zone, fassung), fassung), 0L, plan, fassung)
    }

    private fun gespeicherterPlan(p: SharedPreferences): List<Platz> =
        p.getString("plan", null)?.let { versuche(TAG, "Gespeicherter Plan") { plan(Base64.decode(it, Base64.DEFAULT)) } }.orEmpty()

    /** Ohne Eintrag stammt der gespeicherte Plan von einer Uhr-App bis 0.15.0 (oder einem Boulder davor). */
    private fun gespeicherteFassung(p: SharedPreferences): Int = p.getInt("planfassung", PLANFASSUNG_ALT)

    /**
     * Der Stand von heute, oder nichts. Gestern ist hier nicht interessant.
     * Im Tagesstrahl Geloeschtes zaehlt nicht als genommen (siehe [verwirf]).
     */
    fun heute(context: Context): Stand? {
        val p = context.getSharedPreferences(HEUTE, Context.MODE_PRIVATE)
        val gemeldet = tagOderNull(p.getString("tag", null), TAG)?.let { tag ->
            Stand(tag, p.getString("namen", "")!!.split("\n"), p.getLong("faellig", 0), p.getLong("genommen", 0) and p.getLong("verworfen", 0).inv())
        }
        return standFuer(LocalDate.now(), ZoneId.systemDefault(), gemeldet, gespeicherterPlan(p), gespeicherteFassung(p))
    }

    /** Die Quote je Tag (faellig, genommen) - fuer Verlauf und Trends. */
    fun quoten(context: Context): Map<LocalDate, Pair<Int, Int>> =
        context.getSharedPreferences(QUOTEN, Context.MODE_PRIVATE).all.mapNotNull { (k, v) ->
            val tag = tagOderNull(k, TAG) ?: return@mapNotNull null
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

    /** Der Anfang jeder Kennung von SupCycle, ob mit Namen oder ohne. */
    private const val KENNUNG_ANFANG = "supcycle-"

    /**
     * Ob ein Eintrag der Akte von SupCycle stammt. Die Gesundheitsseite
     * erkennt Praeparate daran: aendert sich die Kennung hier, folgt sie mit.
     */
    fun istKennung(kennung: String?): Boolean = kennung?.startsWith(KENNUNG_ANFANG) == true

    /** Die Kennung bis Boulder 0.42.1, ohne Namen - zum Loeschen von Eintraegen dieser Fassungen. */
    private fun kennungOhneName(tag: LocalDate, platz: Int) = "$KENNUNG_ANFANG$tag-$platz"

    /** Der Platz aus einer Kennung vom Tag [tag] - null fuer eine fremde oder eine alte ohne Kennung. */
    fun platzAus(kennung: String?, tag: String): Int? =
        kennung?.removePrefix("$KENNUNG_ANFANG$tag-")?.takeIf { it != kennung }?.substringBefore('-')?.toIntOrNull()?.takeIf { it in 0..63 }

    /**
     * Einnahmen, die im Tagesstrahl geloescht wurden: sie zaehlen in Liste,
     * Satz und Quote nicht mehr als genommen. Nimmt die Uhr den Haken zurueck
     * und setzt ihn neu, gilt er wieder.
     *
     * Eine Uhr-App, die Hakenzeiten meldet, nimmt den Haken auch zurueck: der
     * Befehl geht hinaus, sobald SupCycle laeuft (Mithoerer).
     *
     * Gesperrt wie das Schreiben in [verarbeite]: beide fuehren denselben Stand.
     */
    @Synchronized
    fun verwirf(context: Context, kennungen: List<String?>) {
        val p = context.getSharedPreferences(HEUTE, Context.MODE_PRIVATE)
        val tag = p.getString("tag", null) ?: return
        val maske = kennungen.mapNotNull { platzAus(it, tag) }.fold(0L) { m, i -> m or (1L shl i) }
        if (maske == 0L) return
        val genommen = p.getLong("genommen", 0)
        val verworfen = p.getLong("verworfen", 0) or maske
        val zeiten = zeitenAus(p.getString("zeiten", null))
        val zurueck = maske and genommen
        val e = p.edit()
            .putLong("verworfen", verworfen)
            .putLong("eingetragen", p.getLong("eingetragen", genommen) and maske.inv())
        if (zeiten != null && zurueck != 0L) e.schreibeRuecknahme(vorgemerkt(liesRuecknahme(p), LocalDate.parse(tag), zurueck, zeiten))
        e.apply()
        merkeQuote(context, tag, p.getLong("faellig", 0), genommen and verworfen.inv())
        if (zeiten != null && zurueck != 0L) anstoss.tryEmit(Unit)
    }

    /**
     * Die Eintraege zurueckgenommener Haken loeschen. Nach der Kennung - und,
     * fuer die Eintraege bis 0.33.0 ohne Kennung, nach Namen: eigener Eintrag
     * von heute, ohne Koffein und ohne Energie, mit dem Namen des Platzes.
     * True, wenn das Loeschen durchging.
     */
    private suspend fun entferne(context: Context, klient: HealthConnectClient, tag: LocalDate, plaetze: Map<Int, String>): Boolean = try {
        val von = tag.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val heute = klient.lies(context, NutritionRecord::class, TimeRangeFilter.between(von, von.plus(Duration.ofDays(1))), Herkunft.EIGENE)
        val kennungen = plaetze.flatMap { (platz, name) -> listOf(kennung(tag, platz, name), kennungOhneName(tag, platz)) }.toSet()
        val namen = plaetze.values.filter { it.isNotBlank() }.toSet()
        val weg = heute.filter { r ->
            r.metadata.clientRecordId in kennungen ||
                (r.metadata.clientRecordId == null && r.caffeine == null && r.energy == null && r.name in namen)
        }.map { it.metadata.id }
        if (weg.isNotEmpty()) klient.deleteRecords(NutritionRecord::class, recordIdsList = weg, clientRecordIdsList = emptyList())
        true
    } catch (e: Exception) {
        logger.w { "Zurueckgenommenes Praeparat nicht geloescht: " + e.message }
        false
    }

    /** [plaetze] des Tages [tag] in die Akte, je auf ihre [eintragZeit] - die Maske der eingetragenen. */
    private suspend fun trageEin(
        klient: HealthConnectClient, tag: LocalDate, plaetze: List<Int>, name: (Int) -> String, plan: List<Platz>, zeiten: List<Long>?,
    ): Long {
        val jetzt = Instant.now()
        var eingetragen = 0L
        for (platz in plaetze) {
            val n = name(platz)
            val wann = eintragZeit(tag, zeiten?.getOrNull(platz), plan.getOrNull(platz)?.zeit, jetzt, ZoneId.systemDefault())
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
                    metadata = Metadata.manualEntry(kennung(tag, platz, n), fassung(zeiten?.getOrNull(platz)), Device(type = Device.TYPE_PHONE)),
                )))
                eingetragen = eingetragen or (1L shl platz)
            } catch (e: Exception) {
                logger.w { "Praeparat nicht eingetragen: " + e.message }
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
            p.all.keys.filter { k -> tagOderNull(k, TAG)?.isBefore(grenze) ?: true }.forEach { remove(it) }
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
        val eingetragen = trageEin(
            klient, tag, plaetze, { namen.getOrNull(it)?.ifBlank { null } ?: context.getString(R.string.ks_s_praeparat_n, it + 1) }, plan,
            hakenzeiten(n.bytes[SC_TAKEN_AT]),
        )
        val rest = maske(plaetze) and eingetragen.inv()
        merkeNachtrag(context, tag, rest)
        return if (rest == 0L) Verarbeitet(null) else Verarbeitet(context.getString(R.string.ks_a_nicht_eingetragen), gescheitert = true)
    }

    suspend fun verarbeite(context: Context, n: KieselNachricht): Verarbeitet? {
        val ymd = n.zahlen[SC_TODAY] ?: return null
        val faellig = n.zahlen[SC_DUE] ?: return null
        val genommen = n.zahlen[SC_TAKEN] ?: 0L
        val tag = UhrApps.datum(ymd) ?: run {
            logger.w { "Unbrauchbares Datum $ymd" }
            return null
        }
        val p = context.getSharedPreferences(HEUTE, Context.MODE_PRIVATE)
        val gemerkt = tagOderNull(p.getString("tag", null), TAG)
        // EIN AELTERER TAG UEBERSCHREIBT DEN STAND NICHT - ein liegengebliebener
        // Stand von gestern drehte sonst den von heute zurueck. Er traegt nur nach.
        if (gemerkt != null && tag.isBefore(gemerkt)) return nachtrag(context, n, tag, faellig, genommen)
        val zeiten = hakenzeiten(n.bytes[SC_TAKEN_AT])
        // Ein offline zurueckgenommener und neu gesetzter Haken unterscheidet sich
        // nur in der Zeit - ohne sie im Schluessel hielte der Riegel ihn auf.
        if (!Riegel.neu(context, "supcycle", "$ymd:$faellig:$genommen" + (zeiten?.let { ":" + zeitenText(it) } ?: ""))) return null

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
        val gleich = gleicheNamen(bekannt, namen) and gleicheZeiten(if (gleicherTag) zeitenAus(p.getString("zeiten", null)) else null, zeiten)
        var eingetragen = if (gleicherTag) p.getLong("eingetragen", vorherGenommen) and gleich else 0L
        val verworfenGelesen = if (gleicherTag) p.getLong("verworfen", 0) else 0L
        var verworfen = verworfenGelesen and genommen and gleich
        // DER VORTAG GEHT NICHT VERLOREN: was an ihm abgehakt, aber nicht
        // eingetragen ist, holt sein liegengebliebener Stand nach (siehe nachtrag).
        if (gemerkt != null && !gleicherTag) {
            val g = p.getLong("genommen", 0)
            merkeNachtrag(context, gemerkt, fehlt(p.getLong("faellig", 0), g, p.getLong("eingetragen", g), p.getLong("verworfen", 0)))
        }

        val klient = Akte(context).bereit()
        val neu = neuGenommen(faellig, genommen, eingetragen or verworfen)
        val geschrieben = if (klient != null) trageEin(klient, tag, neu, { name(it) }, plan, zeiten) else 0L
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

        // WAS DER TAGESSTRAHL INZWISCHEN VERWARF, BLEIBT VERWORFEN: zwischen Lesen
        // und Schreiben liegen Aufrufe an die Akte, und [verwirf] fuehrt denselben
        // Stand - ohne Abgleich ueberschriebe dieser ihn, die geloeschte Einnahme
        // zaehlte wieder als genommen.
        synchronized(this) {
            if (gleicherTag) {
                val inzwischen = p.getLong("verworfen", 0) and verworfenGelesen.inv() and genommen
                verworfen = verworfen or inzwischen
                eingetragen = eingetragen and inzwischen.inv()
            }
            p.edit().apply {
                putString("tag", tag.toString())
                putString("namen", namen.joinToString("\n"))
                putLong("faellig", faellig)
                putLong("genommen", genommen)
                putLong("eingetragen", eingetragen)
                putLong("verworfen", verworfen)
                if (planBytes != null) {
                    putString("plan", Base64.encodeToString(planBytes, Base64.NO_WRAP))
                    // Die Fassung gehoert zum Plan: mit ihm gemerkt, mit ihm ersetzt.
                    putInt("planfassung", planfassung(n))
                }
                if (zeiten != null) putString("zeiten", zeitenText(zeiten)) else remove("zeiten")
                schreibeRuecknahme(nochOffen(liesRuecknahme(p), tag, genommen, zeiten))
                apply()
            }
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
