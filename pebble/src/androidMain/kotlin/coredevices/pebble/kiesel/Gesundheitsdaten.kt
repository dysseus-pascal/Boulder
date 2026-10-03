package coredevices.pebble.kiesel

import android.content.Context
import android.util.Log
import coredevices.pebble.R
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.database.entity.NachtEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

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
    val mitPhasen: Boolean get() = phasen[1] + phasen[2] + phasen[3] > 0
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
    val kcal: Long,
    /** Schritte der letzten sieben Tage bis heute, aeltester zuerst; heute bis jetzt. */
    val wocheSchritte: List<Pair<LocalDate, Long>>,
    val nacht: Nacht?,
    /**
     * Ruhepulse der frueheren Naechte, aelteste zuerst, hoechstens sieben.
     * OHNE DIE LETZTE NACHT: sie ist es, die mit dem Schnitt verglichen
     * wird, und zoege ihn sonst zu sich.
     */
    val ruhepulsWoche: List<Int>,
    /**
     * Die HRV derselben frueheren Naechte, wie [ruhepulsWoche]. Nur fuer den
     * Schnitt, den "Your usual" nennt - geurteilt wird an ihrem Band in [hrvTage].
     */
    val hrvWoche: List<Int>,
    /** Schlafminuten der letzten sieben Naechte, aelteste zuerst; 0 = keine Nacht. */
    val wocheSchlaf: List<Pair<LocalDate, Int>>,
    /** Puls der letzten 24 Stunden: Sekunde seit 1970, Schlaege je Minute. */
    val puls: List<Pair<Long, Int>>,
    /** SpO2 der letzten 24 Stunden, mit Nacht ab ihrem Einschlafen - auch wenn das laenger her ist. */
    val spo2: List<Pair<Long, Int>>,
    val wasserMl: Int,
    /** Die Glaeser von Drinktervall - Wasser anderer Apps zaehlt in [wasserMl], aber nicht als Glas. */
    val glaeser: List<Instant>,
    /** Wasser je Tag, sieben Tage bis heute. */
    val wocheWasser: List<Pair<LocalDate, Int>>,
    val wasserZiel: Drinktervall.Ziel?,
    val supplemente: SupCycle.Stand?,
    val koffein: List<Pair<Instant, Double>>,
    /** Drinktervall auf der Uhr: nur dann Wasser und Koffein (siehe [UhrApps]). */
    val mitDrinktervall: Boolean = true,
    /** SupCycle auf der Uhr: nur dann die Praeparate. */
    val mitSupCycle: Boolean = true,
    /** Getraenke und Praeparate von heute, fuer den Tagesstrahl. */
    val eintraege: List<Tageseintrag> = emptyList(),
    /** Der Puls dieses Tages und der frueheren bis zur selben Uhrzeit, aelteste zuerst. */
    val tagesherz: List<Gesundheitsrechnung.Tagesherz> = emptyList(),
    /** Die Glaeser von heute mit Kennung - zum Loeschen im Tagesstrahl. */
    val glasEintraege: List<Glaseintrag> = emptyList(),
    /** Blutdruck der letzten [Fenster.BLUTDRUCK_TAGE] Tage aus der Akte, aelteste zuerst. */
    val blutdruck: List<Blutdruck> = emptyList(),
    /** Der juengste Minutenwert der Uhr - wann zuletzt synchronisiert wurde. */
    val letzteDaten: Instant?,
    /** Die Grenzen moderat, kraeftig, Spitze in bpm (siehe [Gesundheitsrechnung.pulsZonen]). */
    val pulsZonen: List<Int> = emptyList(),
    /** Die HRV der letzten sieben Naechte mit dem persoenlichen Band, aelteste zuerst. */
    val hrvTage: List<Gesundheitsrechnung.HrvTag> = emptyList(),
    /** Ohne [nacht]: der Beginn der juengsten Nacht ueberhaupt - der Weg zu ihr und zu den frueheren. */
    val juengsteNacht: Long? = null,
    /**
     * Eigene Getraenke und Glaeser von gestern, hoechstens 24 Stunden alt:
     * der Strahl zeigt nur heute, und ein Fehleintrag kurz vor Mitternacht
     * muss sich auch danach noch aendern lassen.
     */
    val gestern: List<Tageseintrag> = emptyList(),
    val gesternGlaeser: List<Glaseintrag> = emptyList(),
)

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

    suspend fun lies(context: Context, api: HealthDataApi): GesundheitsStand = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val heute = LocalDate.now()
        val jetzt = Instant.now()
        fun anfang(tag: LocalDate) = tag.atStartOfDay(zone).toEpochSecond()

        val summe = versuche(TAG, "Bewegung heute") { api.getTotalHealthData(anfang(heute), jetzt.epochSecond) }
        val woche = (Fenster.WOCHE_TAGE - 1 downTo 0).map { heute.minusDays(it.toLong()) }
        // Der heutige Tag steht nicht in den Tagesaggregaten - er kommt aus der Summe bis jetzt.
        val tageswerte = versuche(TAG, "Bewegung der Woche") { api.getDailyAggregates(anfang(woche.first()), anfang(heute)) }.orEmpty()
            .associate { LocalDate.parse(it.day) to (it.steps ?: 0L) } + (heute to (summe?.steps ?: 0L))

        val naechte = Gesundheitsrechnung.jeTag(
            versuche(TAG, "Naechte") { api.getNaechte(anfang(heute.minusDays(Fenster.VERGLEICHSNAECHTE + 2L)), jetzt.epochSecond) }.orEmpty(), zone,
        )
        val (letzte, frueher) = Gesundheitsrechnung.naechteTeilen(naechte, heute, zone)
        val nacht = letzte?.let { Gesundheitsrechnung.nacht(it, versuche(TAG, "Abschnitte der Nacht") { api.getNachtAbschnitte(it.beginnUtc) }.orEmpty()) }
        val schlafJeTag = naechte.associate { Gesundheitsrechnung.nachtTag(it.beginnUtc, it.aufwachen, zone) to it.schlafMinuten }

        val vor24 = jetzt.epochSecond - 24 * 3600
        val puls = versuche(TAG, "Puls") { api.getHealthDataForRange(vor24, jetzt.epochSecond) }.orEmpty()
            .filter { it.heartRate > 0 }.map { it.timestamp to it.heartRate }
        // Spaetabends liegt das Einschlafen der letzten Nacht vor dem 24-Stunden-Fenster:
        // ihr Anfang fehlte, und die Herzseite nannte einen anderen Tiefstwert als die Nachtansicht.
        val spo2Von = minOf(vor24, nacht?.spanne?.first ?: vor24)
        val spo2 = versuche(TAG, "SpO2") { api.getSpo2Readings(spo2Von, jetzt.epochSecond) }.orEmpty()
            .map { it.timestamp to it.spo2Percent }

        val klient = Akte(context).bereit()
        val wochenAnfang = woche.first().atStartOfDay(zone).toInstant()
        val wasser = versuche(TAG, "Wasser lesen") {
            klient?.lies(context, HydrationRecord::class, TimeRangeFilter.between(wochenAnfang, jetzt), Herkunft.ALLE_SONST_EIGENE)
        }.orEmpty()
        val wasserJeTag = wasser.groupBy { it.startTime.atZone(zone).toLocalDate() }
            .mapValues { (_, r) -> r.sumOf { it.volume.inMilliliters }.toInt() }
        val heuteWasser = wasser.filter { it.startTime.atZone(zone).toLocalDate() == heute }
        val nahrung = versuche(TAG, "Ernaehrung lesen") {
            klient?.lies(context, NutritionRecord::class, TimeRangeFilter.between(jetzt.minus(Duration.ofHours(24)), jetzt), Herkunft.ALLE_SONST_EIGENE)
        }.orEmpty()
        val koffein = nahrung.mapNotNull { r -> r.caffeine?.inMilligrams?.takeIf { it > 0 }?.let { r.startTime to it } }
        val alleEintraege = nahrung.mapNotNull { r ->
            val eigen = r.metadata.dataOrigin.packageName == context.packageName
            val mg = r.caffeine?.inMilligrams?.takeIf { it > 0 }
            val mahlzeit = r.mealType != MealType.MEAL_TYPE_UNKNOWN
            val art = Gesundheitsrechnung.einordnung(eigen, r.metadata.clientRecordId, mg, r.energy?.inKilocalories, mahlzeit) ?: return@mapNotNull null
            val name = r.name?.ifBlank { null } ?: if (mg != null) context.getString(R.string.ks_h_koffein_n) else return@mapNotNull null
            Tageseintrag(r.startTime, art == Eintragsart.PRAEPARAT, name, mg, r.metadata.id, eigen, r.metadata.clientRecordId)
        }
        val eintraege = alleEintraege.filter { it.zeit.atZone(zone).toLocalDate() == heute }
        val vorEinemTag = jetzt.minus(Duration.ofHours(24))
        val gesternWasser = wasser.filter { it.startTime.isAfter(vorEinemTag) && it.startTime.atZone(zone).toLocalDate() < heute }

        // DIE UHR MISST KEINEN BLUTDRUCK - er kommt von einer Manschette und
        // deren App ueber die Akte, deshalb auch fremde Eintraege.
        val blutdruck = versuche(TAG, "Blutdruck lesen") {
            klient?.lies(context, BloodPressureRecord::class, TimeRangeFilter.between(jetzt.minus(Duration.ofDays(Fenster.BLUTDRUCK_TAGE)), jetzt), Herkunft.ALLE_SONST_EIGENE).orEmpty()
                .map { Blutdruck(it.time, it.systolic.inMillimetersOfMercury.roundToInt(), it.diastolic.inMillimetersOfMercury.roundToInt()) }
                .sortedBy { it.zeit }
        }.orEmpty()

        val apps = UhrApps.installiert()
        // Dieselben Grenzen wie bei den Trainings, siehe Herzgrenzen
        val ruhe = Herzgrenzen.ruhepuls(frueher.map { it.ruhepuls } + listOfNotNull(nacht?.ruhepuls)) ?: Trainingsanalyse.RUHEPULS_ERSATZ
        val maxpuls = Herzgrenzen.maxpuls(context, ruhe)
        // Eine Rechnung der Zonen fuer Kurve und Belastung, damit beide dieselbe Grenze ziehen
        val zonen = Gesundheitsrechnung.pulsZonen(ruhe, maxpuls)
        val tagesherz = versuche(TAG, "Tagesherz") {
            val von = anfang(heute.minusDays(Fenster.TAGESHERZ_TAGE))
            val minutenpuls = api.getHealthDataForRange(von, jetzt.epochSecond).filter { it.heartRate > 0 }.map { it.timestamp to it.heartRate }
            val schlafSpannen = api.getNaechte(von - 86_400, jetzt.epochSecond).map { e ->
                (e.beginnUtc + e.einschlafen * 60L) to (e.beginnUtc + e.aufwachen * 60L)
            }
            Gesundheitsrechnung.tagesherz(minutenpuls, schlafSpannen, zonen, LocalTime.now(), zone, heute)
        }.orEmpty()
        // Das HRV-Band der aeltesten Nacht der Woche braucht noch einmal
        // Fenster.HRV_NAECHTE davor - die Naechte oben reichen nur eine Woche zurueck.
        val langeNaechte = versuche(TAG, "Naechte der HRV") {
            Gesundheitsrechnung.jeTag(api.getNaechte(anfang(woche.first().minusDays(Fenster.HRV_NAECHTE + 1L)), jetzt.epochSecond), zone)
        }.orEmpty()
        val hrvTage = versuche(TAG, "HRV-Woche") {
            val jeTag = langeNaechte.associate { Gesundheitsrechnung.nachtTag(it.beginnUtc, it.aufwachen, zone) to it.hrv }
            Gesundheitsrechnung.hrvWoche(jeTag, heute)
        }.orEmpty()
        // Ohne Nacht von heute fuehrt nur dieser Weg zu den frueheren Naechten und
        // zu Sleep trends; nach Wochen ohne Uhr reichen die 37 Tage oben nicht.
        val juengste = if (nacht != null) null else langeNaechte.lastOrNull()?.beginnUtc
            ?: versuche(TAG, "Juengste Nacht") { api.getNaechte(0, jetzt.epochSecond) }.orEmpty().maxOfOrNull { it.beginnUtc }
        GesundheitsStand(
            schritte = summe?.steps ?: 0,
            aktivMinuten = summe?.activeMinutes ?: 0,
            meter = (summe?.distanceCm ?: 0) / 100.0,
            kcal = (summe?.activeGramCalories ?: 0) / 1000,
            wocheSchritte = Gesundheitsrechnung.reihe(woche, tageswerte, 0L),
            nacht = nacht,
            ruhepulsWoche = frueher.map { it.ruhepuls }.filter { it > 0 },
            hrvWoche = frueher.map { it.hrv }.filter { it > 0 },
            wocheSchlaf = Gesundheitsrechnung.reihe(woche, schlafJeTag, 0),
            puls = puls,
            spo2 = spo2,
            wasserMl = heuteWasser.sumOf { it.volume.inMilliliters }.toInt(),
            glaeser = heuteWasser.filter { it.metadata.dataOrigin.packageName == context.packageName }.map { it.startTime }.sorted(),
            wocheWasser = Gesundheitsrechnung.reihe(woche, wasserJeTag, 0),
            wasserZiel = Drinktervall.ziel(context),
            supplemente = SupCycle.heute(context),
            koffein = koffein.sortedBy { it.first },
            mitDrinktervall = UhrApps.hat(apps, Drinktervall.UUID),
            mitSupCycle = UhrApps.hat(apps, SupCycle.UUID),
            tagesherz = tagesherz,
            eintraege = eintraege.sortedByDescending { it.zeit },
            blutdruck = blutdruck,
            glasEintraege = heuteWasser.map { glaseintrag(context, it) },
            // Die Fake-Bibliothek meldet 0 statt nichts.
            letzteDaten = versuche(TAG, "Letzte Daten") { api.getLatestTimestamp() }?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it) },
            pulsZonen = zonen,
            hrvTage = hrvTage,
            juengsteNacht = juengste,
            gestern = alleEintraege.filter { it.eigen && !it.praeparat && it.zeit.atZone(zone).toLocalDate() < heute }.sortedBy { it.zeit },
            gesternGlaeser = gesternWasser.map { glaseintrag(context, it) }.filter { it.eigen }.sortedBy { it.zeit },
        )
    }

    private fun glaseintrag(context: Context, r: HydrationRecord) =
        Glaseintrag(r.startTime, r.metadata.id, r.metadata.dataOrigin.packageName == context.packageName, r.volume.inMilliliters.roundToInt())

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
        NachtDetail(
            nacht = n,
            puls = versuche(TAG, "Puls der Nacht") { api.getHealthDataForRange(von, bis) }.orEmpty()
                .filter { it.heartRate > 0 }.map { it.timestamp to it.heartRate },
            spo2 = versuche(TAG, "SpO2 der Nacht") { api.getSpo2Readings(von, bis) }.orEmpty().map { it.timestamp to it.spo2Percent },
            vorige = umgebung.lastOrNull { it.beginnUtc < beginnUtc }?.beginnUtc,
            naechste = umgebung.firstOrNull { it.beginnUtc > beginnUtc }?.beginnUtc,
            davor = frueher,
        )
    }

    /**
     * Eintraege aus der Akte loeschen - nur Boulders eigene, andere lehnt die
     * Akte ohnehin ab. Die Uhr erfaehrt davon nichts: ein geloeschtes Glas
     * zaehlt Drinktervall auf der Uhr weiter.
     */
    suspend fun loesche(context: Context, klasse: kotlin.reflect.KClass<out androidx.health.connect.client.records.Record>, ids: List<String>): Boolean = withContext(Dispatchers.IO) {
        try {
            Akte(context).bereit()?.deleteRecords(klasse, recordIdsList = ids, clientRecordIdsList = emptyList()) != null
        } catch (e: Exception) {
            Log.w(TAG, "Loeschen: " + e.message)
            false
        }
    }

    /**
     * Ein Getraenk umstellen - etwa wenn auf der Uhr die falsche Sorte
     * gewaehlt war. Zeit und Kennung bleiben, Name, Koffein, kcal und Zucker
     * kommen neu aus der Sorte ([art] wie COFFEE_KIND der Uhr).
     */
    suspend fun aendereGetraenk(context: Context, id: String, art: Int): Boolean = withContext(Dispatchers.IO) {
        val p = Kaffeesorten.portion(art) ?: return@withContext false
        try {
            val klient = Akte(context).bereit() ?: return@withContext false
            val alt = klient.readRecord(NutritionRecord::class, id).record
            // DIESELBEN METADATEN: daran erkennt die Akte den Eintrag, der ersetzt wird.
            klient.updateRecords(listOf(
                Kaffeesorten.satz(p, Kaffeesorten.name(context, p), alt.metadata, alt.startTime, alt.endTime, alt.startZoneOffset ?: versatz(alt.startTime), alt.endZoneOffset ?: versatz(alt.endTime)),
            ))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Getraenk aendern: " + e.message)
            false
        }
    }

    /** Eine Tasse von Hand - mit denselben Werten, die die Uhr fuer diese Sorte eintraegt. */
    suspend fun trinkeKoffein(context: Context, art: Int): Boolean = withContext(Dispatchers.IO) {
        val p = Kaffeesorten.portion(art) ?: return@withContext false
        val jetzt = Instant.now()
        try {
            // Eigene Kennung per manualEntry - manualEntryWithId setzte die der Akte.
            val metadaten = Metadata.manualEntry("koffein-${jetzt.epochSecond}", 0L, Device(type = Device.TYPE_PHONE))
            Akte(context).bereit()?.insertRecords(listOf(Kaffeesorten.satz(p, Kaffeesorten.name(context, p), metadaten, jetzt))) != null
        } catch (e: Exception) {
            Log.w(TAG, "Koffein eintragen: " + e.message)
            false
        }
    }
}
