package coredevices.pebble.kiesel

import android.content.Context
import android.util.Log
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.pow

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
}

internal data class GesundheitsStand(
    val schritte: Long,
    val aktivMinuten: Long,
    val meter: Double,
    val kcal: Long,
    /** Die sieben Tage vor heute, aeltester zuerst. */
    val wocheSchritte: List<Pair<LocalDate, Long>>,
    val nacht: Nacht?,
    /** Schlafminuten der letzten sieben Naechte, aelteste zuerst; 0 = keine Nacht. */
    val wocheSchlaf: List<Pair<LocalDate, Int>>,
    /** Puls der letzten 24 Stunden: Sekunde seit 1970, Schlaege je Minute. */
    val puls: List<Pair<Long, Int>>,
    val spo2: List<Pair<Long, Int>>,
    val wasserMl: Int,
    val glaeser: List<Instant>,
    /** Wasser je Tag, sieben Tage bis heute. */
    val wocheWasser: List<Pair<LocalDate, Int>>,
    val wasserZiel: Drinktervall.Ziel?,
    val supplemente: SupCycle.Stand?,
    val koffein: List<Pair<Instant, Double>>,
    val energie: Int?,
)

/** Was sich an den Gesundheitsdaten rechnen laesst - ohne Android, damit es pruefbar ist. */
internal object Gesundheitsrechnung {
    /** Halbwertszeit von Koffein, wie in Kiesel-Helper. */
    const val KOFFEIN_HALBWERT_MIN = 300.0

    fun phasenMinuten(abschnitte: List<NachtAbschnittEntity>): IntArray {
        val aus = IntArray(5)
        abschnitte.forEach { if (it.phase in 0..4) aus[it.phase] += it.bis - it.von }
        return aus
    }

    fun nacht(e: NachtEntity, abschnitte: List<NachtAbschnittEntity>): Nacht {
        val beginn = Instant.ofEpochSecond(e.beginnUtc)
        fun zeit(min: Int) = beginn.plusSeconds(min * 60L)
        return Nacht(
            beginnUtc = e.beginnUtc,
            einschlafen = zeit(e.einschlafen),
            aufwachen = zeit(e.aufwachen),
            schlafMinuten = e.schlafMinuten,
            phasen = phasenMinuten(abschnitte),
            abschnitte = abschnitte.map { Nacht.Abschnitt(zeit(it.von), zeit(it.bis), it.phase) },
            ruhepuls = e.ruhepuls.takeIf { it > 0 },
            hrv = e.hrv.takeIf { it > 0 },
        )
    }

    /** Wieviel Koffein zum Zeitpunkt [jetzt] noch wirkt - jede Dosis halbiert sich alle fuenf Stunden. */
    fun koffeinAktiv(dosen: List<Pair<Instant, Double>>, jetzt: Instant): Double = dosen
        .filter { !it.first.isAfter(jetzt) }
        .sumOf { (wann, mg) -> mg * 0.5.pow(Duration.between(wann, jetzt).toMinutes() / KOFFEIN_HALBWERT_MIN) }

    /** Werte je Tag auf eine lueckenlose Reihe bringen; fehlende Tage zaehlen 0. */
    fun <T : Number> reihe(tage: List<LocalDate>, werte: Map<LocalDate, T>, null_: T): List<Pair<LocalDate, T>> =
        tage.map { it to (werte[it] ?: null_) }

    /** Die Nacht, die zu [tag] gehoert: sie beginnt im Fenster am Abend davor. */
    fun nachtTag(beginnUtc: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochSecond(beginnUtc).atZone(zone).toLocalDate().plusDays(1)
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
    private const val TAGE = "kiesel-tage"

    suspend fun lies(context: Context, api: HealthDataApi): GesundheitsStand = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val heute = LocalDate.now()
        val jetzt = Instant.now()
        fun anfang(tag: LocalDate) = tag.atStartOfDay(zone).toEpochSecond()

        val summe = runCatching { api.getTotalHealthData(anfang(heute), jetzt.epochSecond) }.getOrNull()
        val woche = (7 downTo 1).map { heute.minusDays(it.toLong()) }
        val tageswerte = runCatching { api.getDailyAggregates(anfang(woche.first()), anfang(heute)) }.getOrDefault(emptyList())
            .associate { LocalDate.parse(it.day) to (it.steps ?: 0L) }

        val naechte = runCatching { api.getNaechte(anfang(heute.minusDays(8)), jetzt.epochSecond) }.getOrDefault(emptyList())
        val letzte = naechte.lastOrNull()?.takeIf { jetzt.epochSecond - it.beginnUtc < 36 * 3600 }
        val nacht = letzte?.let { Gesundheitsrechnung.nacht(it, runCatching { api.getNachtAbschnitte(it.beginnUtc) }.getOrDefault(emptyList())) }
        val schlafJeTag = naechte.associate { Gesundheitsrechnung.nachtTag(it.beginnUtc, zone) to it.schlafMinuten }

        val vor24 = jetzt.epochSecond - 24 * 3600
        val puls = runCatching { api.getHealthDataForRange(vor24, jetzt.epochSecond) }.getOrDefault(emptyList())
            .filter { it.heartRate > 0 }.map { it.timestamp to it.heartRate }
        val spo2 = runCatching { api.getSpo2Readings(vor24, jetzt.epochSecond) }.getOrDefault(emptyList())
            .map { it.timestamp to it.spo2Percent }

        val klient = Akte(context).bereit()
        val wochenAnfang = heute.minusDays(6).atStartOfDay(zone).toInstant()
        val wasser = try {
            klient?.liesOderEigene(context, HydrationRecord::class, TimeRangeFilter.between(wochenAnfang, jetzt)).orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Wasser lesen: " + e.message)
            emptyList()
        }
        val wasserJeTag = wasser.groupBy { it.startTime.atZone(zone).toLocalDate() }
            .mapValues { (_, r) -> r.sumOf { it.volume.inMilliliters }.toInt() }
        val heuteWasser = wasser.filter { it.startTime.atZone(zone).toLocalDate() == heute }
        val koffein = try {
            klient?.liesOderEigene(context, NutritionRecord::class, TimeRangeFilter.between(jetzt.minus(Duration.ofHours(24)), jetzt))
                .orEmpty().mapNotNull { r -> r.caffeine?.inMilligrams?.takeIf { it > 0 }?.let { r.startTime to it } }
        } catch (e: Exception) {
            Log.w(TAG, "Koffein lesen: " + e.message)
            emptyList()
        }

        GesundheitsStand(
            schritte = summe?.steps ?: 0,
            aktivMinuten = summe?.activeMinutes ?: 0,
            meter = (summe?.distanceCm ?: 0) / 100.0,
            kcal = (summe?.activeGramCalories ?: 0) / 1000,
            wocheSchritte = Gesundheitsrechnung.reihe(woche, tageswerte, 0L),
            nacht = nacht,
            wocheSchlaf = Gesundheitsrechnung.reihe((6 downTo 0).map { heute.minusDays(it.toLong()) }, schlafJeTag, 0),
            puls = puls,
            spo2 = spo2,
            wasserMl = heuteWasser.sumOf { it.volume.inMilliliters }.toInt(),
            glaeser = heuteWasser.map { it.startTime }.sorted(),
            wocheWasser = Gesundheitsrechnung.reihe((6 downTo 0).map { heute.minusDays(it.toLong()) }, wasserJeTag, 0),
            wasserZiel = Drinktervall.ziel(context),
            supplemente = SupCycle.heute(context),
            koffein = koffein.sortedBy { it.first },
            energie = energie(context, heute),
        )
    }

    /** Eine Nacht mit dem Puls und SpO2 aus ihr, und den Nachbarnaechten zum Blaettern. */
    data class NachtDetail(
        val nacht: Nacht,
        val puls: List<Pair<Long, Int>>,
        val spo2: List<Pair<Long, Int>>,
        val vorige: Long?,
        val naechste: Long?,
    )

    suspend fun nacht(api: HealthDataApi, beginnUtc: Long): NachtDetail? = withContext(Dispatchers.IO) {
        val umgebung = runCatching { api.getNaechte(beginnUtc - 30L * 86_400, beginnUtc + 30L * 86_400) }.getOrDefault(emptyList())
        val e = umgebung.firstOrNull { it.beginnUtc == beginnUtc } ?: return@withContext null
        val n = Gesundheitsrechnung.nacht(e, runCatching { api.getNachtAbschnitte(beginnUtc) }.getOrDefault(emptyList()))
        val von = n.einschlafen.epochSecond - 1800
        val bis = n.aufwachen.epochSecond + 1800
        NachtDetail(
            nacht = n,
            puls = runCatching { api.getHealthDataForRange(von, bis) }.getOrDefault(emptyList())
                .filter { it.heartRate > 0 }.map { it.timestamp to it.heartRate },
            spo2 = runCatching { api.getSpo2Readings(von, bis) }.getOrDefault(emptyList()).map { it.timestamp to it.spo2Percent },
            vorige = umgebung.lastOrNull { it.beginnUtc < beginnUtc }?.beginnUtc,
            naechste = umgebung.firstOrNull { it.beginnUtc > beginnUtc }?.beginnUtc,
        )
    }

    fun energie(context: Context, tag: LocalDate): Int? =
        context.getSharedPreferences(TAGE, Context.MODE_PRIVATE).getInt("energie-$tag", 0).takeIf { it in 1..5 }

    fun setzeEnergie(context: Context, tag: LocalDate, wert: Int) {
        context.getSharedPreferences(TAGE, Context.MODE_PRIVATE).edit().putInt("energie-$tag", wert).apply()
    }

    /** Eine Tasse von Hand - als Ernaehrungseintrag mit Koffein, wie in Kiesel-Helper. */
    suspend fun trinkeKoffein(context: Context, name: String, mg: Int): Boolean = withContext(Dispatchers.IO) {
        val jetzt = Instant.now()
        try {
            Akte(context).bereit()?.insertRecords(listOf(NutritionRecord(
                startTime = jetzt, startZoneOffset = null,
                endTime = jetzt.plusSeconds(60), endZoneOffset = null,
                name = name,
                caffeine = Mass.milligrams(mg.toDouble()),
                metadata = Metadata.manualEntryWithId("koffein-${jetzt.epochSecond}", Device(type = Device.TYPE_PHONE)),
            ))) != null
        } catch (e: Exception) {
            Log.w(TAG, "Koffein eintragen: " + e.message)
            false
        }
    }
}
