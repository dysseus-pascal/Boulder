package coredevices.pebble.kiesel

import android.content.Context
import co.touchlab.kermit.Logger
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
import androidx.health.connect.client.time.TimeRangeFilter
import coredevices.pebble.R
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

    /**
     * Bleibt die Strecke metrisch, auch mit "Imperial Units"? NUR BEIM
     * SCHWIMMEN: das Becken ist an der Uhr in Metern eingestellt, und
     * "0.9 mi" ueber "1,500 m" in den Bahnen waeren zwei Masse fuer dieselbe
     * Strecke. Hier und nicht in einer Ansicht, damit Sport, der Health-
     * Zeitstrahl und das Widget dieselbe Regel lesen.
     */
    val immerMetrisch: Boolean get() = this == Schwimmen

    companion object {
        /**
         * Die Art aus der Akte, nicht aus dem Titel. NUR MTB STEHT IM TITEL:
         * die Akte kennt kein Mountainbike.
         */
        fun von(s: ExerciseSessionRecord): Sport = von(s.exerciseType, s.title)

        /** Dasselbe aus Art und Titel - fuer den Eintrag, bevor er in der Akte steht (Kieselsport). */
        fun von(art: Int, titel: String?): Sport = when (art) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> Laufen
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY ->
                if (titel?.contains("MTB") == true) Mtb else Bike
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

/**
 * Die letzten [plaetze] Werte nach Schluessel, der zuletzt benutzte vorn - ein
 * Treffer rueckt nach vorn, der aelteste faellt heraus. Fuer [TrainingsDaten.punkte]:
 * Liste und Detail lesen auf verschiedenen Faeden, deshalb gesperrt.
 */
internal class Zuletzt<K, V>(private val plaetze: Int) {
    private val werte = ArrayDeque<Pair<K, V>>()

    @Synchronized
    fun hole(k: K): V? {
        val i = werte.indexOfFirst { it.first == k }
        if (i < 0) return null
        return werte.removeAt(i).also { werte.addFirst(it) }.second
    }

    @Synchronized
    fun lege(k: K, v: V) {
        werte.removeAll { it.first == k }
        werte.addFirst(k to v)
        while (werte.size > plaetze) werte.removeAt(werte.lastIndex)
    }
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
    private val logger = Logger.withTag(TAG)
    private const val AUSGEBLENDET = "kiesel-spuren"
    private const val STRECKEN = "kiesel-strecken"
    private const val LAENGEN = "kiesel-spurlaengen"

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

    /**
     * Die zuletzt geladene Uebersicht, solange der Prozess lebt. ZUERST DIE
     * ALTE ZEIGEN: jede Rueckkehr aus einem Training baut die Seiten neu, und
     * ohne sie stuende dort erst ein Ladekreis und die Liste landete nicht an
     * ihrer alten Stelle.
     */
    @Volatile
    var zuletzt: TrainingsUebersicht? = null
        private set

    suspend fun uebersicht(context: Context): TrainingsUebersicht = withContext(Dispatchers.IO) {
        val klient = Akte(context).bereit()
            ?: return@withContext TrainingsUebersicht(false, emptyList(), emptyList()).also { zuletzt = it }
        val jetzt = Instant.now()
        val sitzungen = versuche(TAG, "Trainings lesen") {
            klient.lies(context, ExerciseSessionRecord::class, TimeRangeFilter.between(jetzt.minus(Duration.ofDays(Fenster.SPORT_TAGE)), jetzt))
                .filterNot { erkannt(context, it) }.sortedByDescending { it.startTime }
        }.orEmpty()
        val eigene = sitzungen.filter { eigen(context, it) }
        val gewaehlt = gewaehlt(context, sitzungen)
        val distanzen = distanzen(klient, context, jetzt.minus(Duration.ofDays(Fenster.SPORT_TAGE)), jetzt)
        val laengen = Laengen(context)
        val trainings = gewaehlt.mapIndexed { i, s ->
            // NUR DAS OBERSTE BEHAELT SEINE ROUTE: die Vorschau der Sportseite
            // faellt ohne Spurdatei auf sie zurueck (punkte).
            val e = eintrag(context, s, distanzen, laengen, vorschau = i == 0)
            if (i == 0) e else e.copy(sitzung = ohneRoute(e.sitzung))
        }
        val offen = offeneSpuren(context, eigene, laengen)
        laengen.sichere()
        TrainingsUebersicht(true, trainings, offen).also { zuletzt = it }
    }

    /** Siehe [Sportrechnung.waehle]: ein Training, das zwei Apps schrieben, steht einmal da. */
    private fun gewaehlt(context: Context, sitzungen: List<ExerciseSessionRecord>): List<ExerciseSessionRecord> =
        Sportrechnung.waehle(sitzungen, { eigen(context, it) }, { it.startTime }, { it.endTime }, { Sport.von(it) })

    /**
     * Ein Eintrag der Liste, die Strecke aus [Laengen]. [vorschau]: muss er die
     * Datei doch lesen, bleiben die Punkte fuer die Karte liegen ([gemerkt]).
     */
    private fun eintrag(context: Context, s: ExerciseSessionRecord, distanzen: Distanzen, laengen: Laengen, vorschau: Boolean = false): TrainingEintrag {
        val beginn = s.startTime.epochSecond
        val m = laengen.mass(context, "s:" + s.metadata.id, beginn, s.startTime, s.endTime) { stempel, p ->
            if (vorschau && p.size >= 2) merke(stempel, p)
        }
        // Die Route nur ohne Spur umrechnen, wie vorher in punkte(): sonst je Oeffnen alle Routen des Vierteljahres.
        return TrainingEintrag(s, Sport.von(s), eigen(context, s), strecke(context, s, Spur.laengeAus(m) { route(s) }, distanzen))
    }

    /**
     * Die Strecke eines Trainings, in dieser Reihenfolge: aus der Spur (oder
     * der Route der Akte, auch einer im Detail freigegebenen), aus der
     * Distanz, die Boulder zur Sitzung schrieb (Lauf ohne Ortung, Laufband),
     * aus der Distanz, die die fremde App selbst zur Sitzung schrieb, aus den
     * Bahnen. Ohne Spur zaehlte die Woche 1500 m Schwimmen frueher
     * als 0 km. [ausPunkten]: die Laenge der Punkte, null unter zwei Punkten.
     */
    private fun strecke(context: Context, s: ExerciseSessionRecord, ausPunkten: Double?, distanzen: Distanzen): Double =
        ausPunkten?.takeIf { it > 0 }
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
        val grenze = Instant.now().minus(Duration.ofDays(Fenster.SPORT_TAGE)).epochSecond
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
     * Alle Distanzen des Zeitraums in EINER Abfrage (mit allen Seiten - ein
     * Schrittzaehler schreibt in einem Vierteljahr leicht mehr als eine).
     * Ohne das Leserecht gibt die Akte fremde nicht heraus und lehnt die
     * ganze Abfrage ab - dann wenigstens Boulders eigene, die sie immer
     * herausgibt.
     */
    private suspend fun distanzen(klient: HealthConnectClient, context: Context, von: Instant, bis: Instant): Distanzen {
        val alle = versuche(TAG, "Distanzen lesen") {
            klient.lies(context, DistanceRecord::class, TimeRangeFilter.between(von, bis), Herkunft.ALLE_SONST_EIGENE)
        }.orEmpty()
        val (eigen, fremd) = alle.partition { it.metadata.dataOrigin.packageName == context.packageName }
        return Distanzen(
            eigen.mapNotNull { r -> r.metadata.clientRecordId?.let { it to r.distance.inMeters } }.toMap(),
            fremd.map { Sportrechnung.FremdeDistanz(it.metadata.dataOrigin.packageName, it.startTime, it.endTime, it.distance.inMeters) },
        )
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
     * Die Spur eines Trainings, beschnitten auf seine Sitzung - wie die Route in
     * der Akte. So zeigen Liste, Karte und Detail dieselbe Strecke, die dort
     * steht. Ohne Spurdatei die Route der Akte, wenn sie sie herausgibt
     * (eine fremde nur nach Zustimmung, siehe [routeGesperrt]).
     */
    fun punkte(context: Context, s: ExerciseSessionRecord): List<Spur.Punkt> {
        val beginn = s.startTime.epochSecond
        val stempel = Spur.stempel(beginn, beginn, s.endTime.epochSecond, Spur.groesse(context, beginn))
        gemerkt.hole(stempel)?.let { return it }
        val spur = Spur.inSitzung(Spur.lies(context, beginn), s.startTime, s.endTime)
        if (spur.size >= 2) {
            merke(stempel, spur)
            return spur
        }
        return route(s)
    }

    /**
     * Die zuletzt gelesenen Spuren mit Stempel: jede Rueckkehr auf die Sportseite
     * und das Detail desselben Trainings lasen dieselbe Datei sonst neu. ZWEI
     * PLAETZE, Vorschau und ein Detail: mit einem las die Rueckkehr aus einem
     * aelteren Detail die oberste Spur wieder ganz (~1200 Zeilen je GPS-Stunde).
     */
    private val gemerkt = Zuletzt<String, List<Spur.Punkt>>(2)

    private fun merke(stempel: String, punkte: List<Spur.Punkt>) = gemerkt.lege(stempel, punkte)

    /** Die Route der Akte als Punkte, wenn sie sie herausgab - sonst leer. */
    private fun route(s: ExerciseSessionRecord): List<Spur.Punkt> =
        (s.exerciseRouteResult as? ExerciseRouteResult.Data)?.exerciseRoute?.let(::ausRoute).orEmpty()

    /**
     * Die Sitzung ohne Route. Die Akte gibt eigenen Sitzungen die ganze Route mit
     * (~120 B je Punkt): bei 40 GPS-Stunden 3-6 MB in den Prozess-Caches, ungelesen.
     */
    private fun ohneRoute(s: ExerciseSessionRecord): ExerciseSessionRecord =
        if (s.exerciseRouteResult !is ExerciseRouteResult.Data) s
        else versuche(TAG, "Sitzung ohne Route") {
            ExerciseSessionRecord(
                startTime = s.startTime, startZoneOffset = s.startZoneOffset, endTime = s.endTime, endZoneOffset = s.endZoneOffset,
                metadata = s.metadata, exerciseType = s.exerciseType, title = s.title, notes = s.notes,
                segments = s.segments, laps = s.laps, exerciseRoute = null, plannedExerciseSessionId = s.plannedExerciseSessionId,
            )
        } ?: s

    /**
     * DER LAENGENSPEICHER DER LISTE. Jedes Oeffnen der Sportseite las alle Spuren
     * des Vierteljahres nur fuer die Strecke: bei 40 GPS-Stunden ~48 000 Zeilen,
     * 0,3-0,7 s CPU, 20-50 MB Muell. Ein Mass gilt, solange [Spur.stempel] gleich ist.
     */
    private class Laengen(context: Context) {
        private val p = context.getSharedPreferences(LAENGEN, Context.MODE_PRIVATE)
        private val neu = mutableMapOf<String, String>()

        /** Das Mass der Spur in [von, bis) (ohne: ganz), null ohne Datei; [gelesen] nur nach dem Lesen. */
        fun mass(
            context: Context, schluessel: String, beginn: Long, von: Instant?, bis: Instant?,
            gelesen: ((String, List<Spur.Punkt>) -> Unit)? = null,
        ): Spur.Mass? {
            val groesse = Spur.groesse(context, beginn)
            if (groesse < 0) return null
            val stempel = Spur.stempel(beginn, von?.epochSecond ?: -1, bis?.epochSecond ?: -1, groesse)
            Spur.massAus(p.getString(schluessel, null), stempel)?.let { return it }
            val alle = Spur.liesOderNull(context, beginn)
            val punkte = if (von != null && bis != null) Spur.inSitzung(alle.orEmpty(), von, bis) else alle.orEmpty()
            gelesen?.invoke(stempel, punkte)
            // Ein Lesefehler ist keine leere Spur: nicht merken, das naechste Oeffnen liest neu.
            return Spur.mass(punkte).also { if (alle != null) neu[schluessel] = Spur.massText(stempel, it) }
        }

        /** Die neuen Masse in einem Schreibvorgang; was aus dem Vierteljahr fiel, geht mit. */
        fun sichere() {
            if (neu.isEmpty()) return
            val grenze = Instant.now().minus(Duration.ofDays(Fenster.SPORT_TAGE + 1)).epochSecond
            val e = p.edit()
            p.all.forEach { (k, v) ->
                if (k !in neu && ((v as? String)?.substringBefore(';')?.toLongOrNull() ?: 0L) < grenze) e.remove(k)
            }
            neu.forEach { (k, v) -> e.putString(k, v) }
            e.apply()
        }
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
    private fun offeneSpuren(context: Context, eigene: List<ExerciseSessionRecord>, laengen: Laengen): List<OffeneSpur> {
        val grenze = Instant.now().minus(Duration.ofDays(Fenster.SPORT_TAGE)).epochSecond
        val belegt = eigene.map { it.startTime.epochSecond }.toSet()
        val aus = ausgeblendet(context)
        val jetzt = Instant.now().epochSecond
        return Spur.alle(context)
            .filter { it >= grenze && it !in belegt && it.toString() !in aus }
            .mapNotNull { b ->
                // Die ganze Datei, ohne Fenster - wie vorher, nur aus dem Laengenspeicher.
                val m = laengen.mass(context, "o:$b", b, null, null) ?: return@mapNotNull null
                if (m.anzahl < 2 || jetzt - m.letzte <= 30 * 60) return@mapNotNull null
                OffeneSpur(b, (m.letzte - b) / 60, m.meter)
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
            logger.w { "Training lesen: " + e.message }
            return@withContext null
        }
        val punkte = punkte(context, s)
        val quelle = s.metadata.dataOrigin.packageName
        val distanzen = distanzen(klient, context, s.startTime, s.endTime)
        TrainingDetail(
            eintrag = TrainingEintrag(s, Sport.von(s), eigen(context, s), strecke(context, s, punkte.takeIf { it.size >= 2 }?.let(Spur::laenge), distanzen)),
            punkte = punkte,
            puls = puls(klient, context, s),
            spo2 = spo2(klient, context, s),
            hrv = hrv(klient, context, s),
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
        return versuche(TAG, "Kalorien der Sitzung") {
            klient.lies(context, ActiveCaloriesBurnedRecord::class, TimeRangeFilter.between(s.startTime, s.endTime), Herkunft.EIGENE).firstOrNull {
                it.metadata.clientRecordId == "kieselsport-kcal-$beginn" && it.metadata.clientRecordVersion > Trainingswerte.FASSUNG_UHR
            }?.energy?.inKilocalories
        }
    }

    /**
     * Der mittlere Puls eines Trainings fuer die grosse Karte der Sportseite:
     * eine Abfrage statt des ganzen Details, und derselbe Wert wie dort.
     */
    suspend fun mittelpuls(context: Context, s: ExerciseSessionRecord): Int? = withContext(Dispatchers.IO) {
        val klient = Akte(context).bereit() ?: return@withContext null
        Statistik.mittel(puls(klient, context, s).map { it.bpm.toDouble() })?.toInt()
    }

    /**
     * Das juengste Training seit [seit] fuer das Widget - gewaehlt wie oben auf
     * der Sportseite (eines unter zwei Apps), mit derselben Strecke. IM
     * HINTERGRUND gibt die Akte ohne Sonderrecht nur Eigenes heraus.
     * [endeVorHoechstens]: endete es frueher, null - vor Distanzen und Strecke,
     * die das Widget (~100 Mal am Tag) fuer ein aelteres umsonst las. Vorgabe
     * [WIDGET_FRIST], damit das Widget selbst unveraendert bleibt.
     */
    suspend fun juengstes(context: Context, seit: Duration, endeVorHoechstens: Duration? = WIDGET_FRIST): TrainingEintrag? = withContext(Dispatchers.IO) {
        val klient = Akte(context).bereit() ?: return@withContext null
        val jetzt = Instant.now()
        val von = jetzt.minus(seit)
        val sitzungen = versuche(TAG, "Juengstes Training lesen") {
            klient.lies(context, ExerciseSessionRecord::class, TimeRangeFilter.between(von, jetzt), Herkunft.ALLE_SONST_EIGENE)
                .filterNot { erkannt(context, it) }
        } ?: return@withContext null
        val s = gewaehlt(context, sitzungen).maxByOrNull { it.startTime } ?: return@withContext null
        if (zuAlt(s.endTime, jetzt, endeVorHoechstens)) return@withContext null
        val laengen = Laengen(context)
        eintrag(context, s, distanzen(klient, context, von, jetzt), laengen).also { laengen.sichere() }
    }

    /** Boulder: was [fuerSpiegel] fand. [alleHerkuenfte]: auch fremde Trainings waren lesbar, nicht nur Boulders eigene. */
    class SpiegelTrainings(val trainings: List<TrainingEintrag>, val alleHerkuenfte: Boolean)

    /**
     * Boulder: die Trainings der letzten [tage] Tage fuer den Spiegel ([Spiegel]), gewaehlt und
     * vermessen wie in der Liste - neueste zuerst wie dort, so loest [gewaehlt] einen Gleichstand
     * gleich langer Doppel ebenso auf. IM HINTERGRUND ohne Sonderrecht nur die eigenen
     * ([Herkunft.ALLE_SONST_EIGENE]). Null, wenn die Akte nicht liest - das ist kein leeres Fenster.
     */
    suspend fun fuerSpiegel(context: Context, tage: Long): SpiegelTrainings? = withContext(Dispatchers.IO) {
        val klient = Akte(context).bereit() ?: return@withContext null
        val jetzt = Instant.now()
        val von = jetzt.minus(Duration.ofDays(tage))
        var alle = true
        val sitzungen = versuche(TAG, "Trainings fuer den Spiegel") {
            klient.lies(context, ExerciseSessionRecord::class, TimeRangeFilter.between(von, jetzt), Herkunft.ALLE_SONST_EIGENE) { alle = false }
                .filterNot { erkannt(context, it) }.sortedByDescending { it.startTime }
        } ?: return@withContext null
        val distanzen = distanzen(klient, context, von, jetzt)
        val laengen = Laengen(context)
        SpiegelTrainings(gewaehlt(context, sitzungen).map { eintrag(context, it, distanzen, laengen) }.also { laengen.sichere() }, alle)
    }

    /**
     * Boulder: mittlerer und hoechster Puls eines Trainings fuer den Spiegel, entstoert und
     * abgeschnitten wie im Detail; (null, null) ohne Puls. Null, wenn die Akte ihn nicht liest -
     * das ist kein Training ohne Puls.
     */
    suspend fun pulsKennzahlen(context: Context, s: ExerciseSessionRecord): Pair<Int?, Int?>? = withContext(Dispatchers.IO) {
        val klient = Akte(context).bereit() ?: return@withContext null
        val bpm = versuche(TAG, "Puls fuer den Spiegel") { Trainingsanalyse.entstoert(rohpuls(klient, context, s)) }?.map { it.bpm }
            ?: return@withContext null
        if (bpm.isEmpty()) null to null else bpm.average().toInt() to bpm.max().toInt()
    }

    /**
     * So lange nach seinem Ende zeigt das Widget ein Training - EINE ZAHL fuer
     * das Lesen ([juengstes]) und das Zeichnen ([zeigtImWidget]).
     */
    val WIDGET_FRIST: Duration = Duration.ofHours(3)

    fun zeigtImWidget(ende: Instant, jetzt: Instant): Boolean = !zuAlt(ende, jetzt, WIDGET_FRIST)

    /** Endete es [grenze] oder laenger vor [jetzt]? Ohne Grenze (null) nie. */
    fun zuAlt(ende: Instant, jetzt: Instant, grenze: Duration?): Boolean =
        grenze != null && Duration.between(ende, jetzt) >= grenze

    /**
     * Der Puls waehrend des Trainings - aus der Akte, so bekommt auch ein
     * fremdes seine Kurve. Entstoert beim Lesen, nicht beim Schreiben: in
     * der Akte bleibt, was der Sensor mass.
     */
    private suspend fun puls(klient: HealthConnectClient, context: Context, s: ExerciseSessionRecord): List<Pulspunkt> =
        versuche(TAG, "Puls der Sitzung") { Trainingsanalyse.entstoert(rohpuls(klient, context, s)) }.orEmpty()

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
            rohpuls(klient, context, if (s.metadata.dataOrigin.packageName == context.packageName) Herkunft.EIGENE else Herkunft.ALLE, s.startTime, s.endTime),
            pausen(s),
        )

    /** Der Puls in [von, bis) - nur Boulders eigener oder der aller Apps ([herkunft]). Wirft, wenn die Akte nicht liest. */
    suspend fun rohpuls(klient: HealthConnectClient, context: Context, herkunft: Herkunft, von: Instant, bis: Instant): List<Pulspunkt> {
        val saetze = klient.lies(context, HeartRateRecord::class, TimeRangeFilter.between(von, bis), herkunft)
        return Trainingsanalyse.pulsInSitzung(saetze.flatMap { r -> r.samples.map { it.time to it.beatsPerMinute } }, von, bis)
    }

    /**
     * SpO2 waehrend des Trainings und zehn Minuten danach - die Messung am
     * Ende einer Yogastunde faellt oft kurz hinter den Schluss.
     */
    private suspend fun spo2(klient: HealthConnectClient, context: Context, s: ExerciseSessionRecord): List<Pair<Long, Double>> = versuche(TAG, "SpO2 der Sitzung") {
        klient.lies(context, OxygenSaturationRecord::class, TimeRangeFilter.between(s.startTime, s.endTime.plusSeconds(600)))
            .filter { it.percentage.value in 50.0..100.0 }
            .distinctBy { it.time.epochSecond / 60 }
            .sortedBy { it.time }
            .map { Duration.between(s.startTime, it.time).seconds to it.percentage.value }
    }.orEmpty()

    /** Die juengste HRV waehrend des Trainings und zehn Minuten danach - wie SpO2. */
    private suspend fun hrv(klient: HealthConnectClient, context: Context, s: ExerciseSessionRecord): Double? = versuche(TAG, "HRV der Sitzung") {
        klient.lies(context, HeartRateVariabilityRmssdRecord::class, TimeRangeFilter.between(s.startTime, s.endTime.plusSeconds(600)))
            .maxByOrNull { it.time }?.heartRateVariabilityMillis
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
            ohneKoerper(fehlendeAkteRechte(klient))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Lieber keine Warnung als eine falsche - aber im Log, warum keine kam.
            logger.w { "Rechte nicht lesbar: " + e.message }
            emptySet()
        }
    }

    fun ohneKoerper(fehlend: Set<String>): Set<String> = fehlend - KOERPER_RECHTE
}
