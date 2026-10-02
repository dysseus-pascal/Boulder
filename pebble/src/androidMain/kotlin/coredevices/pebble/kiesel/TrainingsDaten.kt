package coredevices.pebble.kiesel

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import coredevices.pebble.R
import androidx.compose.ui.graphics.toArgb
import coredevices.pebble.kiesel.ansicht.Farben
import coredevices.pebble.health.fehlendeAkteRechte
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Die Sportarten, wie die Ansicht sie zeigt. Ihre Farben stehen bei allen
 * anderen in Farben.kt ([Farben.sport]).
 */
internal enum class Sport {
    Laufen,
    Bike,
    Mtb,
    Wandern,
    Kraft,
    Yoga,
    Schwimmen,
    Anderes;

    /** Die Flaechenfarbe als ARGB - nur noch, bis die Seiten [Farben.sport] nehmen. */
    @Deprecated("Farben.sport(sport)", ReplaceWith("Farben.sport(this).flaecheDunkel"))
    val farbe: Long get() = Farben.sport(this).flaecheDunkel.toArgb().toLong() and 0xFFFFFFFFL

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
    /** Die Strecke: aus Spur oder Route, sonst der Distanz der Akte, sonst den Bahnen; 0 ohne. */
    val meter: Double,
) {
    val id: String get() = sitzung.metadata.id

    /** Die Pausen der Sitzung, Sekunden ab ihrem Beginn - so, wie sie in der Akte stehen. */
    val pausen: List<Pair<Long, Long>> by lazy { TrainingsDaten.pausen(sitzung) }

    /**
     * DIE AKTIVE ZEIT: die Sitzung ohne ihre Pausen. Dieselbe Bezugszeit fuer
     * Dauer, Tempo und Wochensummen - und dieselbe wie auf der Uhr. Frueher
     * war es mit Spur die Wandzeit, ohne die aktive, und die Woche mischte beides.
     */
    val sekunden: Long
        get() = (Duration.between(sitzung.startTime, sitzung.endTime).seconds - pausen.sumOf { it.second - it.first }).coerceAtLeast(0)
    val minuten: Long get() = sekunden / 60
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
    /** Die aktiven kcal aus dem Puls, wie Boulder sie in die Akte schrieb; null bei fremden. */
    val kcal: Double? = null,
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
    private const val STRECKEN = "kiesel-strecken"

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
     * Erkannte Gehstrecken: die, die Boulders Sync bis 0.21.3 als Training
     * eintrug (PlatformHealthSync, "pebble-exercise-"), und die, die eine
     * fremde App von selbst aufzeichnete (Samsung, Fitbit, siehe
     * [Sportrechnung.erkannterGang]). Sie sind Bewegung, kein Training, und
     * fehlen deshalb in Liste, Woche und Widget - sonst stand ein Gang zum
     * Bahnhof als "Hiking" da.
     */
    fun erkannt(context: Context, s: ExerciseSessionRecord): Boolean =
        (s.metadata.dataOrigin.packageName == context.packageName &&
            s.metadata.clientRecordId?.startsWith("pebble-exercise-") == true) ||
            (!eigen(context, s) && Sportrechnung.erkannterGang(s.exerciseType, s.metadata.recordingMethod))

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
        val distanzen = distanzen(klient, context, jetzt.minus(Duration.ofDays(TAGE)), jetzt)
        val trainings = gewaehlt.map { s -> eintrag(context, s, distanzen) }
        TrainingsUebersicht(true, trainings, offeneSpuren(context, eigene))
    }

    /** Siehe [Sportrechnung.waehle]: ein Training, das zwei Apps schrieben, steht einmal da. */
    private fun gewaehlt(context: Context, sitzungen: List<ExerciseSessionRecord>): List<ExerciseSessionRecord> =
        Sportrechnung.waehle(sitzungen, { eigen(context, it) }, { it.startTime }, { it.endTime }, { Sport.von(it) })

    private fun eintrag(context: Context, s: ExerciseSessionRecord, distanzen: Distanzen): TrainingEintrag =
        TrainingEintrag(s, Sport.von(s), eigen(context, s), strecke(context, s, punkte(context, s), distanzen))

    /**
     * Die Strecke eines Trainings, in dieser Reihenfolge: aus der Spur (oder
     * der Route der Akte, auch einer im Detail freigegebenen), aus der
     * Distanz, die Boulder zur Sitzung schrieb (Lauf ohne Ortung, Laufband),
     * aus der Distanz, die die fremde App selbst zur Sitzung schrieb, aus den
     * Bahnen. Ohne Spur zaehlte die Woche 1500 m Schwimmen frueher
     * als 0 km.
     */
    private fun strecke(context: Context, s: ExerciseSessionRecord, punkte: List<Spur.Punkt>, distanzen: Distanzen): Double =
        Spur.laenge(punkte).takeIf { punkte.size >= 2 && it > 0 }
            ?: gemerkteStrecke(context, s.metadata.id)
            ?: distanzen.eigene[distanzKennung(s)]?.takeIf { it > 0 }
            ?: distanzen.fremde(s).takeIf { it > 0 }
            ?: s.laps.sumOf { it.length?.inMeters ?: 0.0 }

    /**
     * Die Strecke einer fremden Route merken, die der Nutzer im Detail
     * freigab. DIE AKTE GIBT SIE NUR DIESES EINE MAL HERAUS (Zustimmung je
     * Training) - zurueck in der Liste stand der Lauf sonst wieder mit 0 km.
     */
    fun merkeStrecke(context: Context, s: ExerciseSessionRecord, meter: Double) {
        if (meter <= 0) return
        val p = context.getSharedPreferences(STRECKEN, Context.MODE_PRIVATE)
        val grenze = Instant.now().minus(Duration.ofDays(TAGE)).epochSecond
        val e = p.edit()
        // Was aus der Liste gefallen ist (oder nicht lesbar), braucht niemand mehr.
        p.all.forEach { (k, v) -> if ((v as? String)?.substringBefore(';')?.toLongOrNull()?.let { it >= grenze } != true) e.remove(k) }
        e.putString(s.metadata.id, "${s.startTime.epochSecond};$meter").apply()
    }

    private fun gemerkteStrecke(context: Context, id: String): Double? =
        context.getSharedPreferences(STRECKEN, Context.MODE_PRIVATE).getString(id, null)
            ?.substringAfter(';')?.toDoubleOrNull()?.takeIf { it > 0 }

    /** Die Kennung der Distanz, die Boulder zu einer eigenen Sitzung schrieb (Trainingswerte). */
    private fun distanzKennung(s: ExerciseSessionRecord): String? = s.startTime.epochSecond.let { beginn ->
        if (s.metadata.clientRecordId == Kieselsport.kennung(beginn)) "kieselsport-distanz-$beginn" else null
    }

    /**
     * Die Distanzen eines Zeitraums: Boulders eigene nach Kennung, dazu die
     * fremden mit ihrer App, damit eine fremde Sitzung ohne Route (Strava-Lauf
     * mit verborgener Karte, Laufband) ihre Strecke bekommt.
     */
    internal class Distanzen(val eigene: Map<String, Double>, private val fremd: List<Sportrechnung.FremdeDistanz>) {
        fun fremde(s: ExerciseSessionRecord): Double =
            Sportrechnung.fremdeStrecke(s.metadata.dataOrigin.packageName, s.startTime, s.endTime, fremd)
    }

    /**
     * Alle Distanzen des Zeitraums in EINER Abfrage. Ohne das Leserecht gibt
     * die Akte fremde nicht heraus und lehnt die ganze Abfrage ab - dann
     * wenigstens Boulders eigene, die sie immer herausgibt.
     */
    private suspend fun distanzen(klient: HealthConnectClient, context: Context, von: Instant, bis: Instant): Distanzen {
        val alle = try {
            liesAlleSeiten(klient, TimeRangeFilter.between(von, bis))
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            Log.w(TAG, "Distanzen lesen: " + e.message)
            null
        } ?: try {
            klient.readRecords(
                ReadRecordsRequest(DistanceRecord::class, TimeRangeFilter.between(von, bis), dataOriginFilter = setOf(DataOrigin(context.packageName)))
            ).records
        } catch (e: Exception) {
            Log.w(TAG, "Eigene Distanzen lesen: " + e.message)
            emptyList()
        }
        val (eigen, fremd) = alle.partition { it.metadata.dataOrigin.packageName == context.packageName }
        return Distanzen(
            eigen.mapNotNull { r -> r.metadata.clientRecordId?.let { it to r.distance.inMeters } }.toMap(),
            fremd.map { Sportrechnung.FremdeDistanz(it.metadata.dataOrigin.packageName, it.startTime, it.endTime, it.distance.inMeters) },
        )
    }

    /** Alle Seiten einer Abfrage - ein Schrittzaehler schreibt in einem Vierteljahr leicht mehr als eine Seite. */
    private suspend fun liesAlleSeiten(klient: HealthConnectClient, zeitraum: TimeRangeFilter): List<DistanceRecord> {
        val alle = mutableListOf<DistanceRecord>()
        var seite: String? = null
        do {
            val antwort = klient.readRecords(
                ReadRecordsRequest(DistanceRecord::class, zeitraum, pageToken = seite)
            )
            alle += antwort.records
            seite = antwort.pageToken?.takeIf { it.isNotEmpty() }
        } while (seite != null)
        return alle
    }

    /** Die Pausen einer Sitzung (ExerciseSegment PAUSE), Sekunden ab ihrem Beginn, auf sie beschnitten. */
    fun pausen(s: ExerciseSessionRecord): List<Pair<Long, Long>> = s.segments
        .filter { it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE }
        .map { maxOf(it.startTime, s.startTime) to minOf(it.endTime, s.endTime) }
        .filter { (a, b) -> b.isAfter(a) }
        .map { (a, b) -> Duration.between(s.startTime, a).seconds to Duration.between(s.startTime, b).seconds }

    /** Der Name eines Trainings: sein Titel, aber ein deutscher aus Kiesel-Helper auf Englisch. */
    fun name(context: Context, s: ExerciseSessionRecord): String {
        val titel = s.title
        return Sportrechnung.artAusTitel(titel)?.let { Kieselsport.artAlsSatzart(context, it).second }
            ?: titel ?: context.getString(Sport.von(s).nameId)
    }

    /**
     * Die Trainings von heute, aelteste zuerst, fuer den Zeitstrahl der
     * Gesundheitsseite. Alle, nicht nur das juengste: neben der Fahrt am Abend
     * fehlte sonst der Lauf am Morgen. Nicht ueber [uebersicht]: die liest ein
     * Vierteljahr samt Spurdateien, die Seite braucht einen Tag.
     */
    suspend fun heute(context: Context): List<TrainingEintrag> = withContext(Dispatchers.IO) {
        val klient = Akte(context).bereit() ?: return@withContext emptyList()
        val jetzt = Instant.now()
        val anfang = jetzt.atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant()
        val sitzungen = try {
            klient.readRecords(
                ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.between(anfang, jetzt))
            ).records.filterNot { erkannt(context, it) }
        } catch (e: Exception) {
            Log.w(TAG, "Trainings von heute lesen: " + e.message)
            return@withContext emptyList()
        }
        // Dieselbe Strecke wie auf der Sportseite - auch 1500 m im Becken.
        val distanzen = distanzen(klient, context, anfang, jetzt)
        gewaehlt(context, sitzungen).filter { !it.startTime.isBefore(anfang) }.sortedBy { it.startTime }
            .map { s -> eintrag(context, s, distanzen) }
    }

    /**
     * Die Spur eines Trainings, beschnitten auf seine Sitzung - wie die Route in
     * der Akte. So zeigen Liste, Karte und Detail dieselbe Strecke, die dort
     * steht. Ohne Spurdatei die Route der Akte, wenn sie sie herausgibt
     * (eine fremde nur nach Zustimmung, siehe [routeGesperrt]).
     */
    fun punkte(context: Context, s: ExerciseSessionRecord): List<Spur.Punkt> {
        val spur = Spur.inSitzung(Spur.lies(context, s.startTime.epochSecond), s.startTime, s.endTime)
        if (spur.size >= 2) return spur
        return (s.exerciseRouteResult as? ExerciseRouteResult.Data)?.exerciseRoute?.let(::ausRoute).orEmpty()
    }

    /** Eine Route der Akte als Spurpunkte - mit Genauigkeit und Hoehe, soweit sie sie kennt. */
    fun ausRoute(route: ExerciseRoute): List<Spur.Punkt> = route.route.map { l ->
        Spur.Punkt(
            l.time.epochSecond, l.latitude, l.longitude, l.altitude?.inMeters,
            l.horizontalAccuracy?.inMeters?.toFloat() ?: Float.NaN,
        )
    }

    /** Braucht die Route dieser Sitzung erst eine Zustimmung (fremde App)? */
    fun routeGesperrt(s: ExerciseSessionRecord): Boolean = s.exerciseRouteResult is ExerciseRouteResult.ConsentRequired

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
        val punkte = punkte(context, s)
        val quelle = s.metadata.dataOrigin.packageName
        val distanzen = distanzen(klient, context, s.startTime, s.endTime)
        TrainingDetail(
            eintrag = TrainingEintrag(s, Sport.von(s), eigen(context, s), strecke(context, s, punkte, distanzen)),
            punkte = punkte,
            puls = puls(klient, context, s),
            spo2 = spo2(klient, s),
            hrv = hrv(klient, s),
            quelle = quelle,
            kcal = kcal(klient, context, s),
        )
    }

    /**
     * Die aktiven Kalorien, die Boulder aus dem Puls in die Akte schrieb
     * (Trainingswerte) - das Detail zeigt dieselbe Zahl wie die Akte. Die der
     * Uhr (aus Schritten) zaehlen hier nicht; dann rechnet das Detail selbst.
     */
    private suspend fun kcal(klient: HealthConnectClient, context: Context, s: ExerciseSessionRecord): Double? {
        val beginn = s.startTime.epochSecond
        if (s.metadata.clientRecordId != Kieselsport.kennung(beginn)) return null
        return try {
            klient.readRecords(
                ReadRecordsRequest(
                    ActiveCaloriesBurnedRecord::class, TimeRangeFilter.between(s.startTime, s.endTime),
                    dataOriginFilter = setOf(DataOrigin(context.packageName)),
                )
            ).records.firstOrNull {
                it.metadata.clientRecordId == "kieselsport-kcal-$beginn" && it.metadata.clientRecordVersion > Trainingswerte.FASSUNG_UHR
            }?.energy?.inKilocalories
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Der mittlere Puls eines Trainings fuer die grosse Karte der Sportseite:
     * eine Abfrage statt des ganzen Details, und derselbe Wert wie dort.
     */
    suspend fun mittelpuls(context: Context, s: ExerciseSessionRecord): Int? = withContext(Dispatchers.IO) {
        val klient = Akte(context).bereit() ?: return@withContext null
        puls(klient, context, s).takeIf { it.isNotEmpty() }?.map { it.bpm }?.average()?.toInt()
    }

    /**
     * Das juengste Training seit [seit] fuer das Widget - gewaehlt wie oben auf
     * der Sportseite (eines unter zwei Apps), mit derselben Strecke. IM
     * HINTERGRUND gibt die Akte ohne Sonderrecht nur Eigenes heraus.
     */
    suspend fun juengstes(context: Context, seit: Duration): TrainingEintrag? = withContext(Dispatchers.IO) {
        val klient = Akte(context).bereit() ?: return@withContext null
        val jetzt = Instant.now()
        val von = jetzt.minus(seit)
        val sitzungen = try {
            klient.liesOderEigene(context, ExerciseSessionRecord::class, TimeRangeFilter.between(von, jetzt))
                .filterNot { erkannt(context, it) }
        } catch (e: Exception) {
            Log.w(TAG, "Juengstes Training lesen: " + e.message)
            return@withContext null
        }
        val s = gewaehlt(context, sitzungen).maxByOrNull { it.startTime } ?: return@withContext null
        eintrag(context, s, distanzen(klient, context, von, jetzt))
    }

    /**
     * Der Puls waehrend des Trainings - aus der Akte, so bekommt auch ein
     * fremdes seine Kurve. Entstoert beim Lesen, nicht beim Schreiben: in
     * der Akte bleibt, was der Sensor mass.
     */
    private suspend fun puls(klient: HealthConnectClient, context: Context, s: ExerciseSessionRecord): List<Pulspunkt> = try {
        Trainingsanalyse.entstoert(rohpuls(klient, context, s))
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Der Puls einer Sitzung, wie ihn die Ansicht zeigt und die Akte ihre
     * Kalorien rechnet (Trainingswerte) - noch nicht entstoert.
     *
     * EIN TRAINING VON BOULDER NUR AUS BOULDERS PULS: im Hintergrund gibt die
     * Akte ohne Sonderrecht nur Eigenes heraus, und die Kalorien dort sollen aus
     * denselben Punkten kommen wie die Zahlen hier. Boulder schreibt zu jedem
     * Training selbst Puls (Kurve und Minutenpuls).
     *
     * KIESEL-HELPER UND FREMDE NEHMEN JEDEN PULS: Kiesel-Helper schrieb erst ab
     * 0.38.0 eine eigene Kurve, davor stand der Puls nur unter der Pebble-App.
     * Kalorien rechnet Boulder fuer diese Trainings nicht in die Akte.
     */
    suspend fun rohpuls(klient: HealthConnectClient, context: Context, s: ExerciseSessionRecord): List<Pulspunkt> =
        // OHNE DIE PAUSEN: die Uhr zaehlt sie nicht, und die Zonen, der Schnitt
        // und die Kalorien eines Trainings sollen es auch nicht.
        Trainingsanalyse.ohnePausen(
            rohpuls(klient, s.metadata.dataOrigin.packageName.takeIf { it == context.packageName }, s.startTime, s.endTime),
            pausen(s),
        )

    /** Der Puls in [von, bis) - von [quelle], ohne sie von allen. Wirft, wenn die Akte nicht liest. */
    suspend fun rohpuls(klient: HealthConnectClient, quelle: String?, von: Instant, bis: Instant): List<Pulspunkt> {
        val saetze = klient.readRecords(
            ReadRecordsRequest(
                HeartRateRecord::class, TimeRangeFilter.between(von, bis),
                dataOriginFilter = quelle?.let { setOf(DataOrigin(it)) }.orEmpty(),
            )
        ).records
        return Trainingsanalyse.pulsInSitzung(saetze.flatMap { r -> r.samples.map { it.time to it.beatsPerMinute } }, von, bis)
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

    /**
     * Welche Rechte der Akte fehlen - die, die der eine Dialog fragt, damit
     * Zahl, Abzeichen und Sportzeile nichts melden, was er nicht beheben kann.
     * Die der Waage zaehlen nicht: wer keine hat, verweigert sie zu Recht und
     * soll dafuer keine Dauerwarnung sehen. Erteilen laesst sie sich trotzdem.
     */
    suspend fun fehlendeRechte(context: Context): Set<String> {
        val klient = Akte(context).bereit() ?: return emptySet()
        return try {
            fehlendeAkteRechte(klient) - KOERPER_RECHTE
        } catch (e: Exception) {
            emptySet()
        }
    }
}
