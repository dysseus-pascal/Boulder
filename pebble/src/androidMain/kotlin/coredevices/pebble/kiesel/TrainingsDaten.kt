package coredevices.pebble.kiesel

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import coredevices.pebble.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Die Sportarten, wie die Ansicht sie zeigt. Die Toene sind die aus
 * Kiesel-Helper, KRAEFTIGER: auf Boulders Grau wirkten die gedeckten
 * Erdfarben ausgewaschen.
 */
internal enum class Sport(val farbe: Long) {
    Laufen(0xFFE8622E),
    Bike(0xFFE2A422),
    Mtb(0xFFE2A422),
    Wandern(0xFF8DB53C),
    Kraft(0xFFD9453A),
    Yoga(0xFFA85CB4),
    Schwimmen(0xFF2CB0C6),
    Anderes(0xFF9C8B7C);

    /**
     * Der Name als Ressource. ALS GETTER, nicht als Feld: sonst luede schon
     * die Aufzaehlung die Ressourcen, und die Rechnung (Wochenrechnung)
     * liesse sich ohne Android nicht pruefen.
     */
    val nameId: Int get() = when (this) {
        Laufen -> R.string.ks_sport_laufen
        Bike -> R.string.ks_sport_bike
        Mtb -> R.string.ks_sport_mtb
        Wandern -> R.string.ks_sport_wandern
        Kraft -> R.string.ks_sport_kraft
        Yoga -> R.string.ks_sport_yoga
        Schwimmen -> R.string.ks_sport_schwimmen
        Anderes -> R.string.ks_training
    }

    /** Ist die Geschwindigkeit hier die Aussage (km/h) und nicht das Tempo (min/km)? */
    val rad: Boolean get() = this == Bike || this == Mtb

    companion object {
        /**
         * Die Art aus der Akte, nicht aus dem Titel. NUR MTB STEHT IM TITEL:
         * die Akte kennt kein Mountainbike.
         */
        fun von(s: ExerciseSessionRecord): Sport = when (s.exerciseType) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> Laufen
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY ->
                if (s.title?.contains("MTB") == true) Mtb else Bike
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING,
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> Wandern
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> Kraft
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA,
            ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> Yoga
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> Schwimmen
            else -> Anderes
        }
    }
}

/** Ein Training in der Liste - ohne Punkte und Puls, die kommen erst im Detail. */
internal data class TrainingEintrag(
    val sitzung: ExerciseSessionRecord,
    val sport: Sport,
    /** Aus Kieselsport (Boulder oder frueher Kiesel-Helper)? */
    val eigen: Boolean,
    /** Die Strecke aus der Spurdatei, 0 ohne Spur. */
    val meter: Double,
) {
    val id: String get() = sitzung.metadata.id
    val minuten: Long get() = Duration.between(sitzung.startTime, sitzung.endTime).toMinutes()
    val einheit: Einheit get() = Einheit(sitzung.startTime.atZone(ZoneId.systemDefault()).toLocalDate(), sport, minuten)
}

/** Eine Spur ohne Training in der Akte - zum Nachtragen. */
internal data class OffeneSpur(val beginn: Long, val minuten: Long, val meter: Double)

internal data class TrainingsUebersicht(
    val akteDa: Boolean,
    val trainings: List<TrainingEintrag>,
    val offen: List<OffeneSpur>,
)

/** Alles zu einem Training, fuer die Detailansicht. */
internal data class TrainingDetail(
    val eintrag: TrainingEintrag,
    val punkte: List<Spur.Punkt>,
    val puls: List<Pulspunkt>,
    val spo2: List<Pair<Long, Double>>,
    /** Die HRV, die die Uhr zu diesem Training eintrug (Yoga) - in ms RMSSD. */
    val hrv: Double?,
    val quelle: String,
)

/**
 * Was die Trainingsansicht zeigt - aus der Akte und den Spurdateien.
 *
 * WIE IN KIESEL-HELPER (TrainingTab.hole), nur ohne Views: ein Vierteljahr
 * Trainings, das eigene gewinnt ueber ein fremdes zur selben Zeit, und
 * Spuren ohne Training koennen nachgetragen werden.
 */
internal object TrainingsDaten {
    private const val TAG = "Kieselsport"
    private const val TAGE = 90L
    private const val AUSGEBLENDET = "kiesel-spuren"

    /**
     * WER ALS EIGEN ZAEHLT: Boulder, und Kiesel-Helper, das bis 0.7.0 die
     * Kieselsport-Trainings eintrug. Beides sind Trainings der Uhr - mit Art,
     * Route und Puls, und sie gewinnen ueber eine fremde Sitzung zur selben
     * Zeit.
     */
    private fun eigen(context: Context, s: ExerciseSessionRecord): Boolean =
        s.metadata.dataOrigin.packageName.let { it == context.packageName || it == KIESEL_HELPER }

    const val KIESEL_HELPER = "ch.dysseus.kieselhelper"

    /**
     * Erkannte Gehstrecken, die Boulders Sync bis 0.21.3 als Training eintrug
     * (PlatformHealthSync, "pebble-exercise-"). Sie sind Bewegung, kein
     * Training, und fehlen deshalb in der Liste.
     */
    fun erkannt(context: Context, s: ExerciseSessionRecord): Boolean =
        s.metadata.dataOrigin.packageName == context.packageName &&
            s.metadata.clientRecordId?.startsWith("pebble-exercise-") == true

    suspend fun uebersicht(context: Context): TrainingsUebersicht = withContext(Dispatchers.IO) {
        val klient = Akte(context).bereit()
            ?: return@withContext TrainingsUebersicht(false, emptyList(), emptyList())
        val jetzt = Instant.now()
        val sitzungen = try {
            klient.readRecords(
                ReadRecordsRequest(
                    ExerciseSessionRecord::class,
                    TimeRangeFilter.between(jetzt.minus(Duration.ofDays(TAGE)), jetzt),
                )
            ).records.filterNot { erkannt(context, it) }.sortedByDescending { it.startTime }
        } catch (e: Exception) {
            Log.w(TAG, "Trainings lesen: " + e.message)
            emptyList()
        }
        val eigene = sitzungen.filter { eigen(context, it) }
        val gewaehlt = gewaehlt(context, sitzungen)
        val trainings = gewaehlt.map { s ->
            val punkte = Spur.lies(context, s.startTime.epochSecond)
            TrainingEintrag(s, Sport.von(s), eigen(context, s), Spur.laenge(punkte))
        }
        TrainingsUebersicht(true, trainings, offeneSpuren(context, eigene))
    }

    /**
     * ZWEI APPS, EIN TRAINING: eine fremde Sitzung, die sich mit einer
     * eigenen ueberschneidet, faellt heraus. In der Akte bleibt sie.
     */
    private fun gewaehlt(context: Context, sitzungen: List<ExerciseSessionRecord>): List<ExerciseSessionRecord> {
        val eigene = sitzungen.filter { eigen(context, it) }
        return sitzungen.filter { s -> eigen(context, s) || eigene.none { e -> s.startTime < e.endTime && e.startTime < s.endTime } }
    }

    /**
     * Das juengste Training der letzten zwei Wochen, fuer die Gesundheitsseite.
     * Nicht ueber [uebersicht]: die liest ein Vierteljahr samt Spurdateien,
     * die Seite braucht eine einzige.
     */
    suspend fun letztes(context: Context): TrainingEintrag? = withContext(Dispatchers.IO) {
        val klient = Akte(context).bereit() ?: return@withContext null
        val jetzt = Instant.now()
        val sitzungen = try {
            klient.readRecords(
                ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.between(jetzt.minus(Duration.ofDays(14)), jetzt))
            ).records.filterNot { erkannt(context, it) }
        } catch (e: Exception) {
            Log.w(TAG, "Letztes Training lesen: " + e.message)
            return@withContext null
        }
        val s = gewaehlt(context, sitzungen).maxByOrNull { it.startTime } ?: return@withContext null
        TrainingEintrag(s, Sport.von(s), eigen(context, s), Spur.laenge(Spur.lies(context, s.startTime.epochSecond)))
    }

    /**
     * Spuren, zu denen kein eigenes Training in der Akte steht. NICHT DIE
     * JUENGSTE HALBE STUNDE: eine Spur, die gerade waechst, gehoert zu einem
     * laufenden Training.
     */
    private fun offeneSpuren(context: Context, eigene: List<ExerciseSessionRecord>): List<OffeneSpur> {
        val grenze = Instant.now().minus(Duration.ofDays(TAGE)).epochSecond
        val belegt = eigene.map { it.startTime.epochSecond }.toSet()
        val aus = ausgeblendet(context)
        val jetzt = Instant.now().epochSecond
        return Spur.alle(context)
            .filter { it >= grenze && it !in belegt && it.toString() !in aus }
            .mapNotNull { b ->
                val punkte = Spur.lies(context, b)
                if (punkte.size < 2 || jetzt - punkte.last().zeit <= 30 * 60) return@mapNotNull null
                OffeneSpur(b, (punkte.last().zeit - b) / 60, Spur.laenge(punkte))
            }
    }

    private fun ausgeblendet(context: Context): Set<String> =
        context.getSharedPreferences(AUSGEBLENDET, Context.MODE_PRIVATE).getStringSet("spuren", null).orEmpty()

    fun blendeAus(context: Context, beginn: Long) {
        val p = context.getSharedPreferences(AUSGEBLENDET, Context.MODE_PRIVATE)
        p.edit().putStringSet("spuren", ausgeblendet(context) + beginn.toString()).apply()
    }

    suspend fun detail(context: Context, id: String): TrainingDetail? = withContext(Dispatchers.IO) {
        val klient = Akte(context).bereit() ?: return@withContext null
        val s = try {
            klient.readRecord(ExerciseSessionRecord::class, id).record
        } catch (e: Exception) {
            Log.w(TAG, "Training lesen: " + e.message)
            return@withContext null
        }
        val punkte = Spur.lies(context, s.startTime.epochSecond)
        val quelle = s.metadata.dataOrigin.packageName
        TrainingDetail(
            eintrag = TrainingEintrag(s, Sport.von(s), eigen(context, s), Spur.laenge(punkte)),
            punkte = punkte,
            puls = puls(klient, s),
            spo2 = spo2(klient, s),
            hrv = hrv(klient, s),
            quelle = quelle,
        )
    }

    /**
     * Der Puls waehrend des Trainings - aus der Akte, so bekommt auch ein
     * fremdes seine Kurve. Entstoert beim Lesen, nicht beim Schreiben: in
     * der Akte bleibt, was der Sensor mass.
     */
    private suspend fun puls(klient: HealthConnectClient, s: ExerciseSessionRecord): List<Pulspunkt> = try {
        klient.readRecords(
            ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(s.startTime, s.endTime))
        ).records
            .flatMap { it.samples }
            .filter { !it.time.isBefore(s.startTime) && !it.time.isAfter(s.endTime) }
            .sortedBy { it.time }
            .map { Pulspunkt(Duration.between(s.startTime, it.time).seconds, it.beatsPerMinute) }
            .let(Trainingsanalyse::entstoert)
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * SpO2 waehrend des Trainings und zehn Minuten danach - die Messung am
     * Ende einer Yogastunde faellt oft kurz hinter den Schluss.
     */
    private suspend fun spo2(klient: HealthConnectClient, s: ExerciseSessionRecord): List<Pair<Long, Double>> = try {
        klient.readRecords(
            ReadRecordsRequest(OxygenSaturationRecord::class, TimeRangeFilter.between(s.startTime, s.endTime.plusSeconds(600)))
        ).records
            .filter { it.percentage.value in 50.0..100.0 }
            .distinctBy { it.time.epochSecond / 60 }
            .sortedBy { it.time }
            .map { Duration.between(s.startTime, it.time).seconds to it.percentage.value }
    } catch (e: Exception) {
        emptyList()
    }

    /** Die juengste HRV waehrend des Trainings und zehn Minuten danach - wie SpO2. */
    private suspend fun hrv(klient: HealthConnectClient, s: ExerciseSessionRecord): Double? = try {
        klient.readRecords(
            ReadRecordsRequest(
                HeartRateVariabilityRmssdRecord::class,
                TimeRangeFilter.between(s.startTime, s.endTime.plusSeconds(600)),
            )
        ).records.maxByOrNull { it.time }?.heartRateVariabilityMillis
    } catch (e: Exception) {
        null
    }

    /** Fehlen Leserechte in der Akte, die die Ansicht braucht? */
    suspend fun fehlendeRechte(context: Context): Set<String> {
        val klient = Akte(context).bereit() ?: return emptySet()
        return try {
            KIESEL_RECHTE - klient.permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            emptySet()
        }
    }
}
