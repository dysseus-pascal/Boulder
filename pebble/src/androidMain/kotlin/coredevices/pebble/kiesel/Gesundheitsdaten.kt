package coredevices.pebble.kiesel

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import coredevices.pebble.R
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.time.TimeRangeFilter
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.connection.Pulsreihe
import io.rebble.libpebblecommon.database.dao.DailyMovementAggregate
import io.rebble.libpebblecommon.database.dao.HealthAggregates
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid

/** Eine Nacht der Firmware, in Zeitpunkten statt Minuten ab dem Fensterbeginn. */
internal data class Nacht(
    val beginnUtc: Long,
    val einschlafen: Instant,
    val aufwachen: Instant,
    val schlafMinuten: Int,
    /** Minuten je Phase: 0 wach, 1 leicht, 2 tief, 3 REM, 4 Schlaf ohne Phase. */
    val phasen: IntArray,
    val abschnitte: List<Abschnitt>,
    val ruhepuls: Int?,
    val hrv: Int?,
) {
    data class Abschnitt(val von: Instant, val bis: Instant, val phase: Int)
    val mitPhasen: Boolean get() = Gesundheitsrechnung.mitPhasen(phasen)
    /**
     * Die Nacht, wie die Nachtansicht sie zeigt: alle Abschnitte, ohne sie
     * Einschlafen bis Aufwachen. Auch die Herzseite nimmt ihr SpO2 daraus -
     * sonst nannten beide einen anderen Tiefstwert derselben Nacht.
     */
    val spanne: LongRange get() =
        (abschnitte.minOfOrNull { it.von.epochSecond } ?: einschlafen.epochSecond)..(abschnitte.maxOfOrNull { it.bis.epochSecond } ?: aufwachen.epochSecond)
}

/**
 * Ein Ernaehrungseintrag aus der Akte: ein Getraenk oder ein Praeparat.
 * [eigen]: von Boulder geschrieben - nur solche Eintraege darf
 * Boulder in der Akte aendern oder loeschen. [kennung]: die eigene Kennung
 * (clientRecordId), an der ein SupCycle-Eintrag seinen Platz traegt.
 */
internal data class Tageseintrag(
    val zeit: Instant, val praeparat: Boolean, val name: String, val koffeinMg: Double?,
    val id: String = "", val eigen: Boolean = false, val kennung: String? = null,
)

/** Was ein Ernaehrungseintrag der Akte auf der Gesundheitsseite ist. */
internal enum class Eintragsart { GETRAENK, PRAEPARAT }

/** Eine Blutdruckmessung aus der Akte, in mmHg. */
internal data class Blutdruck(val zeit: Instant, val oben: Int, val unten: Int)

/**
 * Wasser aus der Akte, mit seiner Kennung. [eigen]: ein Glas von
 * Drinktervall - nur das ist ein "Glas"; was andere Apps eintragen, hat
 * seine eigene Menge.
 */
internal data class Glaseintrag(val zeit: Instant, val id: String, val eigen: Boolean, val ml: Int = 0)

internal data class GesundheitsStand(
    val schritte: Long,
    val aktivMinuten: Long,
    val meter: Double,
    /** Schritte der letzten sieben Tage bis heute, aeltester zuerst; heute bis jetzt. */
    val wocheSchritte: List<Pair<LocalDate, Long>>,
    val nacht: Nacht?,
    /**
     * Ruhepulse der frueheren Naechte, aelteste zuerst, hoechstens sieben.
     * OHNE DIE LETZTE NACHT: sie ist es, die mit dem Schnitt verglichen
     * wird, und zoege ihn sonst zu sich.
     */
    val ruhepulsWoche: List<Int>,
    /** Schlafminuten der letzten sieben Naechte, aelteste zuerst; 0 = keine Nacht. */
    val wocheSchlaf: List<Pair<LocalDate, Int>>,
    /** Puls der letzten 24 Stunden: Sekunde seit 1970, Schlaege je Minute. */
    val puls: List<Pair<Long, Int>>,
    /** SpO2 der letzten 24 Stunden, mit Nacht ab ihrem Einschlafen - auch wenn das laenger her ist. */
    val spo2: List<Pair<Long, Int>>,
    val wasserMl: Int,
    /** Die Glaeser von Drinktervall - Wasser anderer Apps zaehlt in [wasserMl], aber nicht als Glas. */
    val glaeser: List<Instant>,
    val wasserZiel: Drinktervall.Ziel?,
    val supplemente: SupCycle.Stand?,
    val koffein: List<Pair<Instant, Double>>,
    /** Drinktervall auf der Uhr: nur dann Wasser und Koffein (siehe [UhrApps]). */
    val mitDrinktervall: Boolean = true,
    /** SupCycle auf der Uhr: nur dann die Praeparate. */
    val mitSupCycle: Boolean = true,
    /** Getraenke und Praeparate von heute, fuer das Tagesband. */
    val eintraege: List<Tageseintrag> = emptyList(),
    /** Der Puls dieses Tages und der frueheren bis zur selben Uhrzeit, aelteste zuerst. */
    val tagesherz: List<Gesundheitsrechnung.Tagesherz> = emptyList(),
    /** Die Glaeser von heute mit Kennung - zum Loeschen im Tagesband. */
    val glasEintraege: List<Glaseintrag> = emptyList(),
    /** Blutdruck der letzten [Fenster.BLUTDRUCK_TAGE] Tage aus der Akte, aelteste zuerst. */
    val blutdruck: List<Blutdruck> = emptyList(),
    /** Der juengste Minutenwert der Uhr - wann zuletzt synchronisiert wurde. */
    val letzteDaten: Instant?,
    /** Die Grenzen moderat, kraeftig, Spitze in bpm (siehe [Gesundheitsrechnung.pulsZonen]). */
    val pulsZonen: List<Int> = emptyList(),
    /** Die HRV der letzten sieben Naechte mit dem persoenlichen Band, aelteste zuerst. */
    val hrvTage: List<Gesundheitsrechnung.Bandtag> = emptyList(),
    /** Der Blutdruck der letzten sieben Tage, oben und unten je mit Band (Herzseite); ohne Messung null. */
    val blutdruckWoche: Gesundheitsrechnung.Blutdruckwoche? = null,
    /** Ohne [nacht]: der Beginn der juengsten Nacht ueberhaupt - der Weg zu ihr und zu den frueheren. */
    val juengsteNacht: Long? = null,
    /**
     * Eigene Getraenke und Glaeser von gestern, hoechstens 24 Stunden alt:
     * der Strahl zeigt nur heute, und ein Fehleintrag kurz vor Mitternacht
     * muss sich auch danach noch aendern lassen.
     */
    val gestern: List<Tageseintrag> = emptyList(),
    val gesternGlaeser: List<Glaseintrag> = emptyList(),
    /**
     * Die Naechte der letzten [Fenster.AKKU_TAGE] Tage, roh wie auf der
     * Akkuseite - damit die Zeile auf Health dasselbe sagt wie sie.
     */
    val akkuNaechte: List<NachtEntity> = emptyList(),
    /**
     * Der Puls des Tagesbands, ab dessen Anfang ([Tagesbandrechnung.von]) bis
     * jetzt. EIGENS NEBEN [puls]: das Band beginnt beim Einschlafen und reicht
     * spaetabends weiter als 24 Stunden zurueck; die Herzseite zeigt genau 24.
     */
    val bandPuls: List<Pair<Long, Int>> = emptyList(),
    /** Die Schritte je halbe Stunde ab dem Anfang des Tagesbands: Beginn in Sekunden, Schritte. */
    val schrittBins: List<Pair<Long, Long>> = emptyList(),
    /**
     * Das Wasser der letzten sieben Tage in ml, aeltester zuerst, heute =
     * [wasserMl] - die Saeulen der Seite Water. Nur die Seiten lesen es.
     */
    val wasserWoche: List<Pair<LocalDate, Int>> = emptyList(),
)

/** Ein Wassereintrag der Akte, roh: die Menge ungerundet, die Summe des Tages rundet erst am Ende. */
internal data class Wasserroh(val zeit: Instant, val id: String, val eigen: Boolean, val ml: Double)

/** Ein Ernaehrungseintrag der Akte, roh: was die Einordnung, der Name und das Koffein brauchen. */
internal data class Nahrungroh(
    val zeit: Instant, val id: String, val eigen: Boolean, val kennung: String?,
    val koffeinMg: Double?, val kcal: Double?, val mahlzeit: Boolean, val name: String?,
)

/**
 * Woher [Gesundheitsdaten] liest: Boulders Datenbank, die Akte und was die
 * App sich merkt. EINE SCHNITTSTELLE FUER SEITE UND WIDGET - beide laufen
 * durch dieselbe Rechnung, und ein Test kann sie ohne Telefon vergleichen
 * (GesundheitsdatenTest). Null bei der Akte heisst: keine Akte auf dem Telefon.
 */
internal interface Gesundheitsquelle {
    suspend fun summe(von: Long, bis: Long): HealthAggregates?
    suspend fun tageswerte(von: Long, bis: Long): List<DailyMovementAggregate>
    suspend fun naechte(von: Long, bis: Long): List<NachtEntity>
    suspend fun abschnitte(beginnUtc: Long): List<NachtAbschnittEntity>
    suspend fun puls(von: Long, bis: Long): Pulsreihe
    /** Die Schritte je halbe Stunde, siehe [HealthDataApi.getSchritteJeHalbstunde]. */
    suspend fun schritte(von: Long, bis: Long): List<Pair<Long, Long>>
    suspend fun spo2(von: Long, bis: Long): List<Pair<Long, Int>>
    suspend fun letzterZeitstempel(): Long?
    suspend fun wasser(von: Instant, bis: Instant): List<Wasserroh>?
    suspend fun nahrung(von: Instant, bis: Instant): List<Nahrungroh>?
    suspend fun blutdruck(von: Instant, bis: Instant): List<Blutdruck>?
    suspend fun apps(): Set<Uuid>?
    suspend fun maxpuls(ruhe: Int): Int
    fun wasserZiel(): Drinktervall.Ziel?
    fun supplemente(): SupCycle.Stand?
    /** Der Name eines Getraenks, das nur sein Koffein nennt. */
    val koffeinName: String
}

/** Die Quelle auf dem Telefon: Boulders Datenbank ueber [api], dazu die Akte. */
private class Telefonquelle(private val context: Context, private val api: HealthDataApi) : Gesundheitsquelle {
    // Einmal je Lesen geholt, wie vorher: die Akte fragt sonst bei jedem Zugriff nach ihrem Stand.
    private val klient by lazy { Akte(context).bereit() }
    private fun eigen(paket: String) = paket == context.packageName

    override suspend fun summe(von: Long, bis: Long) = api.getTotalHealthData(von, bis)
    override suspend fun tageswerte(von: Long, bis: Long) = api.getDailyAggregates(von, bis)
    override suspend fun naechte(von: Long, bis: Long) = api.getNaechte(von, bis)
    override suspend fun abschnitte(beginnUtc: Long) = api.getNachtAbschnitte(beginnUtc)
    override suspend fun puls(von: Long, bis: Long) = api.getPulsMinuten(von, bis)
    override suspend fun schritte(von: Long, bis: Long) = api.getSchritteJeHalbstunde(von, bis)
    override suspend fun spo2(von: Long, bis: Long) = api.getSpo2Readings(von, bis).map { it.timestamp to it.spo2Percent }
    override suspend fun letzterZeitstempel() = api.getLatestTimestamp()

    override suspend fun wasser(von: Instant, bis: Instant) =
        klient?.lies(context, HydrationRecord::class, TimeRangeFilter.between(von, bis), Herkunft.ALLE_SONST_EIGENE)
            ?.map { Wasserroh(it.startTime, it.metadata.id, eigen(it.metadata.dataOrigin.packageName), it.volume.inMilliliters) }

    override suspend fun nahrung(von: Instant, bis: Instant) =
        klient?.lies(context, NutritionRecord::class, TimeRangeFilter.between(von, bis), Herkunft.ALLE_SONST_EIGENE)?.map { r ->
            Nahrungroh(
                r.startTime, r.metadata.id, eigen(r.metadata.dataOrigin.packageName), r.metadata.clientRecordId,
                r.caffeine?.inMilligrams, r.energy?.inKilocalories, r.mealType != MealType.MEAL_TYPE_UNKNOWN, r.name,
            )
        }

    // DIE UHR MISST KEINEN BLUTDRUCK - er kommt von einer Manschette und
    // deren App ueber die Akte, deshalb auch fremde Eintraege.
    override suspend fun blutdruck(von: Instant, bis: Instant) =
        klient?.lies(context, BloodPressureRecord::class, TimeRangeFilter.between(von, bis), Herkunft.ALLE_SONST_EIGENE)
            ?.map { Blutdruck(it.time, it.systolic.inMillimetersOfMercury.roundToInt(), it.diastolic.inMillimetersOfMercury.roundToInt()) }

    override suspend fun apps() = UhrApps.installiert()
    override suspend fun maxpuls(ruhe: Int) = Herzgrenzen.maxpuls(context, ruhe)
    override fun wasserZiel() = Drinktervall.ziel(context)
    override fun supplemente() = SupCycle.heute(context)
    override val koffeinName: String get() = context.getString(R.string.ks_h_koffein_n)
}

/**
 * Ein Lesen, das mehrere teilen - Seite, Herzseite und Widget lesen dasselbe.
 *
 * GETEILT: laeuft schon ein Lesen, das noch gilt, wartet ein zweiter Aufrufer
 * darauf, statt dasselbe noch einmal zu lesen. FRISCH: ein Wert, juenger als
 * das verlangte Alter und seither nicht [veraltet], kommt ganz ohne Lesen.
 * [veraltet] zaehlt die Generation hoch: was davor begann, teilt niemand
 * mehr, und kein frueherer Wert gilt mehr als frisch.
 *
 * DER ABBRUCH GEHT DURCH: steigen alle Wartenden aus (die Seite ist weg),
 * endet das Lesen - wie vorher, als jeder fuer sich las. [gilt] prueft, ob
 * ein gemerkter Wert noch taugt (etwa: vom selben Tag).
 */
internal class Ladestelle<T : Any>(
    private val uhr: () -> Long,
    private val bereich: CoroutineScope,
    private val gilt: (T) -> Boolean = { true },
) {
    private class Lauf<T>(val generation: Long, val ergebnis: Deferred<T>) {
        var wartende = 0
    }

    private class Merk<T>(val wert: T, val generation: Long, val um: Long)

    private val schloss = Any()
    private var generation = 0L
    private var lauf: Lauf<T>? = null
    @Volatile
    private var merk: Merk<T>? = null

    /** Der zuletzt gelesene Wert, gleich wie alt. */
    val letzter: T? get() = merk?.wert

    /** Neue Daten (ein Schub, ein eigener Eintrag) oder Zeit ausser Sicht: was vorher gelesen wurde, ist nicht mehr frisch. */
    fun veraltet() {
        synchronized(schloss) { generation++ }
    }

    /** Der gemerkte Wert, wenn er juenger als [maxAlterMs] ist und seither nichts veraltet ist. */
    fun frisch(maxAlterMs: Long): T? {
        val m = merk ?: return null
        val gen = synchronized(schloss) { generation }
        return m.wert.takeIf { m.generation == gen && uhr() - m.um < maxAlterMs && gilt(it) }
    }

    /** Ein laufendes Lesen, das noch gilt, abwarten - ohne selbst eines anzustossen. Null, wenn keines laeuft. */
    suspend fun mitwarten(): T? {
        val l = synchronized(schloss) { gueltigerLauf()?.also { it.wartende++ } } ?: return null
        return warte(l)
    }

    suspend fun lies(maxAlterMs: Long, lade: suspend () -> T): T {
        frisch(maxAlterMs)?.let { return it }
        val l = synchronized(schloss) {
            val l = gueltigerLauf() ?: run {
                val gen = generation
                // Das Alter zaehlt ab dem Beginn: was danach dazukam, kann fehlen.
                val beginn = uhr()
                Lauf(gen, bereich.async { lade().also { merke(it, gen, beginn) } }).also { lauf = it }
            }
            l.wartende++
            l
        }
        return warte(l)
    }

    private fun gueltigerLauf(): Lauf<T>? = lauf?.takeIf { it.generation == generation && it.ergebnis.isActive }

    private suspend fun warte(l: Lauf<T>): T = try {
        l.ergebnis.await()
    } finally {
        synchronized(schloss) {
            l.wartende--
            if (l.wartende == 0 && l.ergebnis.isActive) l.ergebnis.cancel()
        }
    }

    /** Ein spaet fertiges Lesen einer frueheren Generation ueberschreibt kein neueres. */
    private fun merke(wert: T, gen: Long, um: Long) {
        synchronized(schloss) {
            if (merk.let { it == null || it.generation <= gen }) merk = Merk(wert, gen, um)
        }
    }
}

/**
 * Laedt, was die Gesundheitsseite zeigt.
 *
 * BEWEGUNG, PULS, SPO2 UND NAECHTE AUS BOULDERS EIGENER DATENBANK: die Uhr
 * ist ihre Quelle, und so zeigt die Seite sie auch ohne Health Connect.
 * Wasser und Koffein kommen aus der Akte - dort traegt Boulder sie ein, und
 * andere Apps koennen dasselbe tun.
 */
internal object Gesundheitsdaten {
    private const val TAG = "Gesundheit"

    /**
     * So lange gilt ein Stand beim Neuaufbau einer Seite als frisch: Health ->
     * Heart -> Health las sonst dreimal in Sekunden dasselbe (je 100-350 ms).
     * Ein Schub, ein eigener Eintrag oder das Verlassen der App machen ihn
     * sofort alt ([veraltet]).
     */
    const val FRISCH_MS = 30_000L

    private class Tagesstand(val tag: LocalDate, val stand: GesundheitsStand)

    // Ein gemerkter Stand gilt nur am selben Tag: nach Mitternacht gaebe er die Zahlen von gestern als die von heute aus.
    private val stelle = Ladestelle<Tagesstand>(
        SystemClock::elapsedRealtime, CoroutineScope(SupervisorJob() + Dispatchers.IO), gilt = { it.tag == LocalDate.now() },
    )

    /**
     * Der zuletzt gelesene Stand - von der Seite oder der Herzseite -, solange
     * der Prozess lebt. Die Seiten zeigen ihn bei der Rueckkehr gleich, statt
     * erst einen Ladekreis. NUR VOM SELBEN TAG, siehe [stelle]. Das Widget
     * liest schlanker und legt seinen Stand hier nicht ab.
     */
    val zuletzt: GesundheitsStand? get() = stelle.letzter?.takeIf { it.tag == LocalDate.now() }?.stand

    /** Die zuletzt gelesene Nacht im Einzelnen, solange der Prozess lebt - wie [zuletzt]. */
    @Volatile
    var zuletztNacht: NachtDetail? = null
        private set

    /** Neue Daten von der Uhr oder ein eigener Eintrag: der gemerkte Stand ist nicht mehr frisch. */
    fun veraltet() = stelle.veraltet()

    private val beobachtet = AtomicBoolean(false)

    /**
     * WER DIE APP VERLAESST, KANN ANDERSWO EINTRAGEN - eine Waage, eine
     * Wasser-App. Danach gilt kein gemerkter Stand mehr als frisch, auch wenn
     * die Seite neu aufgebaut statt fortgesetzt wird. Einmal je Prozess.
     */
    fun beobachte(context: Context) {
        val app = context.applicationContext as? Application ?: return
        if (!beobachtet.compareAndSet(false, true)) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStopped(activity: Activity) {
                veraltet()
                Koerperdaten.veraltet()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * Der Stand der Gesundheitsseite. [maxAlterMs] > 0: ein frischer Stand von
     * eben darf es auch sein ([FRISCH_MS]). Liest schon jemand, wartet dieser
     * Aufruf mit, statt dasselbe noch einmal zu lesen.
     */
    suspend fun lies(context: Context, api: HealthDataApi, maxAlterMs: Long = 0): GesundheitsStand {
        beobachte(context)
        // Das Lesen kann die Seite ueberleben (ein anderer wartet mit): ohne die Activity festzuhalten.
        val app = context.applicationContext
        return stelle.lies(maxAlterMs) {
            val jetzt = Instant.now()
            val zone = ZoneId.systemDefault()
            Tagesstand(jetzt.atZone(zone).toLocalDate(), lade(Telefonquelle(app, api), voll = true, jetzt, zone))
        }.stand
    }

    /**
     * Was das Widget zeigt - OHNE DAS, WAS NUR DIE SEITEN ZEIGEN: 14 Tage
     * Minutenpuls, Tagesherz, SpO2, Blutdruck, HRV-Band, Akkunaechte und die
     * Schritte der Woche. Das Widget zeichnet ~100-150-mal am Tag; vorher las
     * jedes Zeichnen alles. Liest die Seite gerade oder hat sie eben gelesen,
     * nimmt es deren Stand - die Zahlen sind dieselben (GesundheitsdatenTest).
     */
    suspend fun liesFuerWidget(context: Context, api: HealthDataApi): GesundheitsStand {
        stelle.frisch(FRISCH_MS)?.let { return it.stand }
        stelle.mitwarten()?.let { return it.stand }
        return withContext(Dispatchers.IO) { lade(Telefonquelle(context, api), voll = false, Instant.now(), ZoneId.systemDefault()) }
    }

    /**
     * Das eine Lesen fuer Seite und Widget. [voll] = false laesst weg, was nur
     * die Seiten zeigen (siehe [liesFuerWidget]). WAS BEIDE ZEIGEN, LAEUFT
     * DURCH DIESELBEN ZEILEN - so koennen Seite und Widget nicht auseinanderlaufen.
     */
    internal suspend fun lade(q: Gesundheitsquelle, voll: Boolean, jetzt: Instant, zone: ZoneId): GesundheitsStand {
        val heute = jetzt.atZone(zone).toLocalDate()
        fun anfang(tag: LocalDate) = tag.atStartOfDay(zone).toEpochSecond()

        val summe = versuche(TAG, "Bewegung heute") { q.summe(anfang(heute), jetzt.epochSecond) }
        val woche = (Fenster.WOCHE_TAGE - 1 downTo 0).map { heute.minusDays(it.toLong()) }

        // ALLE NAECHTE IN EINER ABFRAGE: Vergleich, Tagesherz, HRV-Band und Akku
        // reichen verschieden weit zurueck. Gelesen wird einmal das weiteste
        // Fenster, und jeder nimmt sich seinen Teil - vorher waren es vier Abfragen.
        // Scheitert sie, fehlen alle diese Teile zugleich statt nur einer.
        // Das Widget braucht nur den Vergleich (letzte Nacht, Schlaf der Woche): 9 statt 37 Tage.
        val vonVergleich = anfang(heute.minusDays(Fenster.VERGLEICHSNAECHTE + 2L))
        val vonTagesherz = anfang(heute.minusDays(Fenster.TAGESHERZ_TAGE))
        // Das HRV-Band der aeltesten Nacht der Woche braucht noch einmal Fenster.HRV_NAECHTE davor.
        val vonHrv = anfang(woche.first().minusDays(Fenster.HRV_NAECHTE + 1L))
        val vonAkku = jetzt.minus(Duration.ofDays(Fenster.AKKU_TAGE)).epochSecond
        val alleNaechte = versuche(TAG, "Naechte") {
            q.naechte(if (voll) minOf(vonVergleich, vonTagesherz - 86_400, vonHrv, vonAkku) else vonVergleich, jetzt.epochSecond)
        }.orEmpty()
        fun naechteAb(von: Long) = alleNaechte.filter { it.beginnUtc >= von }

        val naechte = Gesundheitsrechnung.jeTag(naechteAb(vonVergleich), zone)
        val (letzte, frueher) = Gesundheitsrechnung.naechteTeilen(naechte, heute, zone)
        val nacht = letzte?.let { Gesundheitsrechnung.nacht(it, versuche(TAG, "Abschnitte der Nacht") { q.abschnitte(it.beginnUtc) }.orEmpty()) }
        val schlafJeTag = naechte.associate { Gesundheitsrechnung.nachtTag(it.beginnUtc, it.aufwachen, zone) to it.schlafMinuten }

        val vorEinemTag = jetzt.minus(Duration.ofHours(24))
        // Wasser von heute und den letzten 24 Stunden; die Seiten dazu die Woche fuer Water
        // in derselben Abfrage - das Widget zeigt sie nicht und liest sie nicht.
        val wasserVon = minOf(Instant.ofEpochSecond(anfang(heute)), vorEinemTag)
            .let { if (voll) minOf(it, Instant.ofEpochSecond(anfang(woche.first()))) else it }
        val wasser = versuche(TAG, "Wasser lesen") { q.wasser(wasserVon, jetzt) }.orEmpty()
        val heuteWasser = wasser.filter { it.zeit.atZone(zone).toLocalDate() == heute }
        val nahrung = versuche(TAG, "Ernaehrung lesen") { q.nahrung(vorEinemTag, jetzt) }.orEmpty()
        val koffein = nahrung.mapNotNull { r -> r.koffeinMg?.takeIf { it > 0 }?.let { r.zeit to it } }
        val alleEintraege = nahrung.mapNotNull { r ->
            val mg = r.koffeinMg?.takeIf { it > 0 }
            val art = Gesundheitsrechnung.einordnung(r.eigen, r.kennung, mg, r.kcal, r.mahlzeit) ?: return@mapNotNull null
            val name = r.name?.ifBlank { null } ?: if (mg != null) q.koffeinName else return@mapNotNull null
            Tageseintrag(r.zeit, art == Eintragsart.PRAEPARAT, name, mg, r.id, r.eigen, r.kennung)
        }
        val eintraege = alleEintraege.filter { it.zeit.atZone(zone).toLocalDate() == heute }
        val gesternWasser = wasser.filter { it.zeit.isAfter(vorEinemTag) && it.zeit.atZone(zone).toLocalDate() < heute }
        val apps = q.apps()

        val gemeinsam = GesundheitsStand(
            schritte = summe?.steps ?: 0,
            aktivMinuten = summe?.activeMinutes ?: 0,
            meter = (summe?.distanceCm ?: 0) / 100.0,
            wocheSchritte = emptyList(),
            nacht = nacht,
            ruhepulsWoche = frueher.map { it.ruhepuls }.filter { it > 0 },
            wocheSchlaf = Gesundheitsrechnung.reihe(woche, schlafJeTag, 0),
            puls = emptyList(),
            spo2 = emptyList(),
            wasserMl = heuteWasser.sumOf { it.ml }.toInt(),
            glaeser = heuteWasser.filter { it.eigen }.map { it.zeit }.sorted(),
            wasserZiel = q.wasserZiel(),
            supplemente = q.supplemente(),
            koffein = koffein.sortedBy { it.first },
            mitDrinktervall = UhrApps.hat(apps, Drinktervall.UUID),
            mitSupCycle = UhrApps.hat(apps, SupCycle.UUID),
            eintraege = eintraege.sortedByDescending { it.zeit },
            glasEintraege = heuteWasser.map { glaseintrag(it) },
            letzteDaten = null,
            gestern = alleEintraege.filter { it.eigen && !it.praeparat && it.zeit.atZone(zone).toLocalDate() < heute }.sortedBy { it.zeit },
            gesternGlaeser = gesternWasser.map { glaseintrag(it) }.filter { it.eigen }.sortedBy { it.zeit },
        )
        if (!voll) return gemeinsam

        // --- Ab hier nur fuer die Seiten ---

        // Der heutige Tag steht nicht in den Tagesaggregaten - er kommt aus der Summe bis jetzt.
        val tageswerte = versuche(TAG, "Bewegung der Woche") { q.tageswerte(anfang(woche.first()), anfang(heute)) }.orEmpty()
            .associate { LocalDate.parse(it.day) to (it.steps ?: 0L) } + (heute to (summe?.steps ?: 0L))
        val vor24 = jetzt.epochSecond - 24 * 3600
        // Der Minutenpuls einmal, so weit das Tagesherz zurueckblickt - die 24 Stunden der Kurve stecken darin.
        // Nur Zeit und Puls (Pulsreihe): 14 Tage sind bis zu 20'160 Minuten.
        val minutenpuls = versuche(TAG, "Puls") { q.puls(minOf(vonTagesherz, vor24), jetzt.epochSecond) } ?: Pulsreihe.LEER
        val puls = buildList {
            for (i in 0 until minutenpuls.size) if (minutenpuls.zeiten[i] >= vor24) add(minutenpuls.zeiten[i] to minutenpuls.werte[i])
        }
        // Das Tagesband beginnt beim Einschlafen - aus derselben Reihe, ohne zweite Abfrage.
        val bandVon = Tagesbandrechnung.von(nacht, heute, zone).epochSecond
        val bandPuls = buildList {
            for (i in 0 until minutenpuls.size) if (minutenpuls.zeiten[i] >= bandVon) add(minutenpuls.zeiten[i] to minutenpuls.werte[i])
        }
        val schrittBins = versuche(TAG, "Schritte je halbe Stunde") { q.schritte(bandVon, jetzt.epochSecond) }.orEmpty()
        // Spaetabends liegt das Einschlafen der letzten Nacht vor dem 24-Stunden-Fenster:
        // ihr Anfang fehlte, und die Herzseite nannte einen anderen Tiefstwert als die Nachtansicht.
        val spo2Von = minOf(vor24, nacht?.spanne?.first ?: vor24)
        val spo2 = versuche(TAG, "SpO2") { q.spo2(spo2Von, jetzt.epochSecond) }.orEmpty()
        // Der Blutdruck einmal, so weit das Band des aeltesten Tages der Woche zurueckblickt -
        // die Messungen der Karte (Fenster.BLUTDRUCK_TAGE) stecken darin.
        val blutdruckVon = jetzt.minus(Duration.ofDays(Fenster.BLUTDRUCK_TAGE))
        val vonBlutdruckband = Instant.ofEpochSecond(anfang(woche.first().minusDays(Fenster.HRV_NAECHTE.toLong())))
        val blutdruckAlle = versuche(TAG, "Blutdruck lesen") {
            q.blutdruck(minOf(blutdruckVon, vonBlutdruckband), jetzt).orEmpty().sortedBy { it.zeit }
        }.orEmpty()
        val blutdruck = blutdruckAlle.filter { !it.zeit.isBefore(blutdruckVon) }
        val blutdruckWoche = if (blutdruckAlle.isEmpty()) null
        else versuche(TAG, "Blutdruckwoche") { Gesundheitsrechnung.blutdruckwoche(blutdruckAlle, heute, zone) }

        // Dieselben Grenzen wie bei den Trainings, siehe Herzgrenzen
        val ruhe = Herzgrenzen.ruhepuls(frueher.map { it.ruhepuls } + listOfNotNull(nacht?.ruhepuls)) ?: Trainingsanalyse.RUHEPULS_ERSATZ
        // Eine Rechnung der Zonen fuer Kurve und Belastung, damit beide dieselbe Grenze ziehen
        val zonen = Gesundheitsrechnung.pulsZonen(ruhe, q.maxpuls(ruhe))
        val tagesherz = versuche(TAG, "Tagesherz") {
            val schlafSpannen = naechteAb(vonTagesherz - 86_400).map { e ->
                (e.beginnUtc + e.einschlafen * 60L) to (e.beginnUtc + e.aufwachen * 60L)
            }
            Gesundheitsrechnung.tagesherz(minutenpuls, vonTagesherz, schlafSpannen, zonen, jetzt.atZone(zone).toLocalTime(), zone, heute)
        }.orEmpty()
        val langeNaechte = Gesundheitsrechnung.jeTag(naechteAb(vonHrv), zone)
        val hrvTage = versuche(TAG, "HRV-Woche") {
            val jeTag = langeNaechte.associate { Gesundheitsrechnung.nachtTag(it.beginnUtc, it.aufwachen, zone) to it.hrv }
            Gesundheitsrechnung.bandwoche(jeTag, heute)
        }.orEmpty()
        // Ohne Nacht von heute fuehrt nur dieser Weg zu den frueheren Naechten und
        // zu Sleep trends; nach Wochen ohne Uhr reichen die 37 Tage oben nicht.
        val juengste = if (nacht != null) null else langeNaechte.lastOrNull()?.beginnUtc
            ?: versuche(TAG, "Juengste Nacht") { q.naechte(0, jetzt.epochSecond) }.orEmpty().maxOfOrNull { it.beginnUtc }
        return gemeinsam.copy(
            wocheSchritte = Gesundheitsrechnung.reihe(woche, tageswerte, 0L),
            puls = puls,
            spo2 = spo2,
            tagesherz = tagesherz,
            blutdruck = blutdruck,
            // Die echte Bibliothek meldet null, solange nichts da ist; eine 0 kaeme nur aus der
            // Vorschau-Bibliothek (FakeLibPebble) oder von 1970 - beides keine letzten Daten.
            letzteDaten = versuche(TAG, "Letzte Daten") { q.letzterZeitstempel() }?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it) },
            pulsZonen = zonen,
            hrvTage = hrvTage,
            blutdruckWoche = blutdruckWoche,
            juengsteNacht = juengste,
            akkuNaechte = naechteAb(vonAkku),
            bandPuls = bandPuls,
            schrittBins = schrittBins,
            // GESUMMT WIE HEUTE ([wasserMl]): die Saeule von heute ist dieselbe Zahl wie "0.5 of 2.5 l" darueber.
            wasserWoche = Gesundheitsrechnung.reihe(
                woche, wasser.groupBy { it.zeit.atZone(zone).toLocalDate() }.mapValues { (_, l) -> l.sumOf { it.ml }.toInt() }, 0,
            ),
        )
    }

    private fun glaseintrag(r: Wasserroh) = Glaseintrag(r.zeit, r.id, r.eigen, r.ml.roundToInt())

    /** Eine Nacht mit dem Puls und SpO2 aus ihr, und den Nachbarnaechten zum Blaettern. */
    data class NachtDetail(
        val nacht: Nacht,
        val puls: List<Pair<Long, Int>>,
        val spo2: List<Pair<Long, Int>>,
        val vorige: Long?,
        val naechste: Long?,
        /** Bis zu sieben Naechte vor dieser, aelteste zuerst - fuer Schnitt und Saeulen. */
        val davor: List<NachtEntity> = emptyList(),
    )

    /**
     * EINE NACHT JE TAG UND DIE FRUEHEREN WIE AUF HEALTH: geblaettert und
     * verglichen wird nur mit der laengsten Nacht jedes Tages, die frueheren
     * aus derselben Woche davor ([Gesundheitsrechnung.naechteTeilen]). Roh
     * zaehlte ein Kurzschlaf am Morgen als eigene Nacht - zwei Saeulen "Tu",
     * und der Schnitt fiel auf 5:31 h, waehrend die Trends 7:42 h sagten.
     */
    suspend fun nacht(api: HealthDataApi, beginnUtc: Long): NachtDetail? = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val roh = versuche(TAG, "Naechte um die Nacht") { api.getNaechte(beginnUtc - 30L * 86_400, beginnUtc + 30L * 86_400) }.orEmpty()
        val e = roh.firstOrNull { it.beginnUtc == beginnUtc } ?: return@withContext null
        val umgebung = Gesundheitsrechnung.jeTag(roh, zone)
        val (_, frueher) = Gesundheitsrechnung.naechteTeilen(umgebung, Gesundheitsrechnung.nachtTag(e.beginnUtc, e.aufwachen, zone), zone)
        val n = Gesundheitsrechnung.nacht(e, versuche(TAG, "Abschnitte der Nacht") { api.getNachtAbschnitte(beginnUtc) }.orEmpty())
        val von = minOf(n.einschlafen.epochSecond, n.spanne.first) - 1800
        val bis = maxOf(n.aufwachen.epochSecond, n.spanne.last) + 1800
        // Nur Zeit und Puls statt ganzer Minutenzeilen, wie im Tagesherz.
        val puls = versuche(TAG, "Puls der Nacht") { api.getPulsMinuten(von, bis) } ?: Pulsreihe.LEER
        NachtDetail(
            nacht = n,
            puls = List(puls.size) { puls.zeiten[it] to puls.werte[it] },
            spo2 = versuche(TAG, "SpO2 der Nacht") { api.getSpo2Readings(von, bis) }.orEmpty().map { it.timestamp to it.spo2Percent },
            vorige = umgebung.lastOrNull { it.beginnUtc < beginnUtc }?.beginnUtc,
            naechste = umgebung.firstOrNull { it.beginnUtc > beginnUtc }?.beginnUtc,
            davor = frueher,
        ).also { zuletztNacht = it }
    }
}
