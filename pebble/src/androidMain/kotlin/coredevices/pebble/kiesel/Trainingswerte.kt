package coredevices.pebble.kiesel

import android.content.Context
import co.touchlab.kermit.Logger
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Velocity
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.HealthGender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.mp.KoinPlatform
import java.time.Duration
import java.time.Instant

/**
 * Was ein Training neben der Sitzung in die Akte bringt: aktive Kalorien,
 * Distanz, Hoehenmeter und Tempo.
 *
 * EIGENE SAETZE, NICHT FELDER DER SITZUNG: die Sitzung der Akte kennt weder
 * Kalorien noch Strecke. Tagessummen und andere Apps lesen sie nur aus diesen
 * Satzarten.
 *
 * JEDER SATZ EINZELN: fehlt das Recht fuer einen, kommen die anderen trotzdem
 * an. Feste Kennungen je Training ersetzen beim zweiten Durchlauf, statt zu
 * verdoppeln.
 */
internal object Trainingswerte {
    private const val TAG = "Kieselsport"
    private val logger = Logger.withTag(TAG)

    // Die Akte behaelt bei gleicher Kennung die hoehere Fassung und uebergeht
    // eine niedrigere: die Kalorien aus dem Puls schlagen die der Uhr, egal was
    // zuerst ankommt. Die Uhr zaehlt nur Schritte und kennt auf dem Rad fast nichts.
    const val FASSUNG_UHR = 1L
    // Bis 0.40.0 trugen die Puls-Kalorien immer Fassung 2, auch aus einem
    // Teilstueck der Kurve. Jede Rechnung von jetzt an liegt darueber.
    private const val FASSUNG_PULS_BIS_0_40 = 2L

    /** Puls-Kalorien so lange nachholen - aeltere Trainings bleiben, wie sie sind. */
    internal const val NACHHOLEN_TAGE = 14L

    /** Hoechstens eine Tempoprobe so oft - jede Sekunde waeren tausende je Stunde. */
    const val TEMPO_TAKT_S = 10L
    private const val TEMPO_JE_EINTRAG = 900

    /**
     * Die Tempoproben fuer die Akte: Zeitpunkt (Epochensekunde) und m/s.
     *
     * DAS GEGLAETTETE TEMPO DER TRAININGSANSICHT, damit die Akte dasselbe
     * zeigt. Nur innerhalb der Sitzung, wie Route, Distanz und Hoehe
     * (Spur.inSitzung): abweisen wuerde die Akte nichts - ein Tempo-Eintrag
     * haengt an keiner Sitzung -, aber was vor dem Start lag, war kein Training.
     */
    fun tempoProben(punkte: List<Spur.Punkt>, von: Long, bis: Long): List<Pair<Long, Double>> {
        if (punkte.size < 2) return emptyList()
        val start = punkte.first().zeit
        val aus = mutableListOf<Pair<Long, Double>>()
        // Null statt Long.MIN_VALUE: t - Long.MIN_VALUE laeuft ueber, wird
        // negativ und verwarf so jede Probe.
        var letzte: Long? = null
        for (p in Trainingsanalyse.strecke(punkte)) {
            val t = start + p.sekunde
            if (t < von || t >= bis || (letzte != null && t - letzte < TEMPO_TAKT_S)) continue
            aus += t to p.tempo
            letzte = t
        }
        return aus
    }

    /** Distanz, Hoehenmeter und Tempo eines Trainings - [punkte] schon auf die Sitzung beschnitten. */
    suspend fun schreibeStrecke(context: Context, beginn: Long, anfang: Instant, ende: Instant, meter: Double, punkte: List<Spur.Punkt>) {
        val klient = Akte(context).bereit() ?: return
        if (meter > 0) {
            schreibe(klient, DistanceRecord(
                startTime = anfang, startZoneOffset = versatz(anfang), endTime = ende, endZoneOffset = versatz(ende),
                distance = Length.meters(meter),
                metadata = Kieselsport.vonDerUhr("kieselsport-distanz-$beginn"),
            ))
        }
        // Derselbe Aufstieg, den Sportseite und Detail zeigen (Trainingsanalyse.hoehen).
        val auf = Trainingsanalyse.aufstieg(punkte)
        if (auf > 0) {
            schreibe(klient, ElevationGainedRecord(
                startTime = anfang, startZoneOffset = versatz(anfang), endTime = ende, endZoneOffset = versatz(ende),
                elevation = Length.meters(auf),
                metadata = Kieselsport.vonDerUhr("kieselsport-hoehe-$beginn"),
            ))
        }
        tempoProben(punkte, anfang.epochSecond, ende.epochSecond).chunked(TEMPO_JE_EINTRAG).forEach { stueck ->
            val von = Instant.ofEpochSecond(stueck.first().first)
            val bis = Instant.ofEpochSecond(stueck.last().first + 1)
            schreibe(klient, SpeedRecord(
                startTime = von, startZoneOffset = versatz(von), endTime = bis, endZoneOffset = versatz(bis),
                samples = stueck.map { (t, v) -> SpeedRecord.Sample(Instant.ofEpochSecond(t), Velocity.metersPerSecond(v)) },
                // Nach der Sekunde des ersten Werts: so ersetzt ein zweiter Durchlauf jedes Stueck einzeln.
                metadata = Kieselsport.vonDerUhr("kieselsport-tempo-$beginn-${stueck.first().first - beginn}"),
            ))
        }
    }

    /** Die aktiven Kalorien, die die Uhr gezaehlt hat - nur bis der Puls besseres liefert. */
    suspend fun schreibeKalorienDerUhr(context: Context, beginn: Long, anfang: Instant, ende: Instant, kcal: Long) {
        if (kcal <= 0) return
        val klient = Akte(context).bereit() ?: return
        schreibe(klient, kalorien(beginn, anfang, ende, kcal.toDouble(), FASSUNG_UHR))
    }

    /**
     * Die aktiven Kalorien aus dem Puls der Sitzung - dieselbe Rechnung wie in
     * der Trainingsansicht (derselbe Puls, Ruhe- und Maximalpuls aus
     * [herzgrenzenVor], Koerper aus [koerperVor]), ohne den Ruheumsatz. Ohne
     * Gewicht oder Alter bleibt es bei den Kalorien der Uhr.
     *
     * AUS DER AKTE, NICHT AUS DER KURVENDATEI: die Uhr schickt eine Kurve auch
     * in Stuecken, und die Datei haelt nur das juengste. Aus ihr gerechnet,
     * ersetzte ein nachgeschicktes Stueck den Wert der ganzen Kurve.
     *
     * NUR MIT SITZUNG: erst sie gibt das Fenster. Kommt die Kurve vorher, rechnet
     * [Kieselsport] nach dem Eintragen der Sitzung.
     *
     * Gibt zurueck, was neu in der Akte steht - null, wenn nichts zu tun war.
     */
    suspend fun schreibeKalorienAusPuls(context: Context, beginn: Long): Double? {
        val klient = Akte(context).bereit() ?: return null
        val sitzung = try {
            klient.lies(context, ExerciseSessionRecord::class, TimeRangeFilter.between(Instant.ofEpochSecond(beginn), Instant.ofEpochSecond(beginn + 1)), Herkunft.EIGENE)
                .firstOrNull { it.metadata.clientRecordId == Kieselsport.kennung(beginn) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w { "Sitzung fuer die Kalorien: " + e.message }
            null
        } ?: return null
        return kalorienAusPuls(context, klient, sitzung)
    }

    /**
     * Die Puls-Kalorien der eigenen Trainings der letzten Wochen nachholen, wo
     * sie fehlen oder auf weniger Puls beruhen, als jetzt in der Akte steht -
     * oder ohne die Waage gerechnet wurden, die jetzt lesbar ist ([fassungAusPuls]).
     *
     * SCHEITERT DIE RECHNUNG (Recht fehlt, Gewicht fehlt, die Uhr antwortet
     * nicht), ist die Kurvendatei trotzdem weg - in der Akte steht die Kurve
     * aber, und aus ihr laesst sich jederzeit neu rechnen. Dieselbe Kennung
     * ersetzt, doppelt wird nichts; was schon vollstaendig ist, bleibt unberuehrt.
     */
    suspend fun holeKalorienNach(context: Context) {
        val klient = Akte(context).bereit() ?: return
        val jetzt = Instant.now()
        val sitzungen = try {
            klient.lies(context, ExerciseSessionRecord::class, TimeRangeFilter.between(jetzt.minus(Duration.ofDays(NACHHOLEN_TAGE)), jetzt), Herkunft.EIGENE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w { "Kalorien nachholen: " + e.message }
            return
        }
        sitzungen.filter { it.metadata.clientRecordId == Kieselsport.kennung(it.startTime.epochSecond) }
            .forEach { kalorienAusPuls(context, klient, it) }
    }

    /**
     * Die Fassung der Puls-Kalorien: je mehr Pulswerte, desto hoeher. So
     * uebergeht die Akte einen unvollstaendigeren Stand, statt mit ihm einen
     * vollstaendigeren zu ersetzen.
     *
     * BEI GLEICHEM PULS SCHLAEGT DIE WAAGE: im Hintergrund gibt die Akte ohne
     * Sonderrecht kein fremdes Gewicht heraus, die Rechnung nimmt dann das
     * eingestellte. Sonst bliebe dieser Stand fuer immer; so ersetzt ihn die
     * erste Rechnung, die die Waage lesen kann (spaetestens das Detail).
     * Ebenso der Ruhepuls der Naechte den Ersatz: die Uhr synct sie oft erst nach dem Training.
     * Vierfach gezaehlt, damit jede fruehere Fassung (2 + 2 * Pulswerte + Waage) darunter liegt.
     */
    fun fassungAusPuls(pulswerte: Int, waage: Boolean, ruheAusNaechten: Boolean): Long =
        FASSUNG_PULS_BIS_0_40 + 4L * pulswerte + (if (ruheAusNaechten) 2 else 0) + (if (waage) 1 else 0)

    /** Rechnete [Trainingsanalyse.kalorien] mit dem Ruhepuls der Naechte - nicht ohne sie, nicht mit einem verworfenen? */
    fun ruheAusNaechten(ruhepuls: Int?): Boolean = ruhepuls != null && ruhepuls in Trainingsanalyse.RUHEPULS_GUELTIG

    /** Lohnt es, neu zu schreiben? Nur, wenn die Akte nichts so Vollstaendiges hat. */
    fun lohntSich(vorhanden: Long?, fassung: Long): Boolean = vorhanden == null || vorhanden < fassung

    private suspend fun kalorienAusPuls(context: Context, klient: HealthConnectClient, s: ExerciseSessionRecord): Double? {
        val beginn = s.startTime.epochSecond
        try {
            // Roh gezaehlt: das Entstoeren wirft je nach Kurve andere Punkte weg,
            // die Zahl der Messungen waechst dagegen nur mit dem, was ankommt.
            val roh = TrainingsDaten.rohpuls(klient, context, s)
            if (roh.isEmpty()) return null
            val koerper = koerperVor(context, klient, s.startTime) ?: return null
            val vorhanden = try {
                klient.lies(context, ActiveCaloriesBurnedRecord::class, TimeRangeFilter.between(s.startTime, s.endTime), Herkunft.EIGENE)
                    .firstOrNull { it.metadata.clientRecordId == "kieselsport-kcal-$beginn" }?.metadata?.clientRecordVersion
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nicht lesbar heisst nur: neu schreiben - die Akte behaelt trotzdem die hoehere Fassung.
                logger.w { "Vorhandene Kalorien von $beginn nicht lesbar, schreibe neu: ${e.message}" }
                null
            }
            // ERST DIE OBERGRENZE, DANN DIE NAECHTE: haelt die Akte schon die beste Fassung,
            // liest der Start (holeKalorienNach) die Naechte gar nicht erst.
            if (!lohntSich(vorhanden, fassungAusPuls(roh.size, koerper.ausWaage, ruheAusNaechten = true))) return null
            val (ruhepuls, maxpuls) = herzgrenzenVor(context, KoinPlatform.getKoin().get<HealthDataApi>(), s.startTime)
            val fassung = fassungAusPuls(roh.size, koerper.ausWaage, ruheAusNaechten(ruhepuls))
            if (!lohntSich(vorhanden, fassung)) return null
            val kcal = Trainingsanalyse.aktiveKalorien(
                Trainingsanalyse.entstoert(roh), koerper.kg, koerper.alter, koerper.geschlecht, ruhepuls, maxpuls,
            ) ?: return null
            if (kcal <= 0) return null
            return kcal.takeIf { schreibe(klient, kalorien(beginn, s.startTime, s.endTime, kcal, fassung)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Meist fehlt ein Recht - der naechste Start holt es nach (holeKalorienNach).
            logger.w { "Kalorien aus dem Puls: " + e.message }
            return null
        }
    }

    /**
     * Ruhe- und Maximalpuls fuer ein Training, das so beginnt - fuer Kalorien
     * und Zonen in Akte und Trainingsansicht, siehe Herzgrenzen.
     *
     * DIE NAECHTE VOR DEM TRAINING, nicht die bis heute: sonst aenderten sich
     * die Kalorien eines alten Trainings mit jeder neuen Nacht.
     */
    suspend fun herzgrenzenVor(context: Context, api: HealthDataApi, start: Instant): Pair<Int?, Int> {
        val naechte = try {
            api.getNaechte(start.minus(Duration.ofDays(Fenster.HERZGRENZEN_TAGE)).epochSecond, start.epochSecond)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w { "Naechte vor $start nicht lesbar, Ruhepuls ersetzt: ${e.message}" }
            emptyList()
        }
        val ruhe = Herzgrenzen.ruhepuls(naechte.sortedBy { it.beginnUtc }.map { it.ruhepuls })
        return ruhe to Herzgrenzen.maxpuls(context, ruhe)
    }

    /** Gewicht, Alter und Geschlecht fuer die Kalorien eines Trainings; [ausWaage]: das Gewicht kam von ihr. */
    data class Koerper(val kg: Double, val alter: Int, val geschlecht: Trainingsanalyse.Geschlecht, val ausWaage: Boolean = false)

    /**
     * Der Koerper fuer ein Training, das so beginnt: Alter und Geschlecht aus
     * den Einstellungen, das Gewicht DER WAAGE VOR DEM TRAINING - so wie die
     * Body-Seite es zeigt. Nur ohne Waage das von Hand eingestellte. Vor dem
     * Training, damit sich die Kalorien eines alten Trainings nicht mit jeder
     * neuen Wiegung aendern (wie die Naechte in [herzgrenzenVor]).
     */
    suspend fun koerperVor(context: Context, klient: HealthConnectClient?, start: Instant): Koerper? {
        val e = withTimeoutOrNull(5_000) { KoinPlatform.getKoin().get<LibPebble>().healthSettings.first() } ?: return null
        val waage = klient?.let { waageVor(it, context, start) }
        return Koerper(waage ?: e.weightDag / 100.0, e.ageYears, geschlecht(e.gender), ausWaage = waage != null)
    }

    /** Die juengste Wiegung im Jahr vor [start]; im Hintergrund ohne Sonderrecht nur eigene, also keine. */
    private suspend fun waageVor(klient: HealthConnectClient, context: Context, start: Instant): Double? = try {
        klient.liesEinen(context, WeightRecord::class, TimeRangeFilter.between(start.minus(Duration.ofDays(365)), start), neuester = true)
            ?.weight?.inKilograms?.takeIf { it in 20.0..400.0 }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w { "Gewicht lesen: " + e.message }
        null
    }

    fun geschlecht(g: HealthGender): Trainingsanalyse.Geschlecht = when (g) {
        HealthGender.Male -> Trainingsanalyse.Geschlecht.Mann
        HealthGender.Female -> Trainingsanalyse.Geschlecht.Frau
        HealthGender.Other -> Trainingsanalyse.Geschlecht.Andere
    }

    private fun kalorien(beginn: Long, anfang: Instant, ende: Instant, kcal: Double, fassung: Long) = ActiveCaloriesBurnedRecord(
        startTime = anfang, startZoneOffset = versatz(anfang), endTime = ende, endZoneOffset = versatz(ende),
        energy = Energy.kilocalories(kcal),
        metadata = Metadata.autoRecorded(Device(type = Device.TYPE_WATCH), "kieselsport-kcal-$beginn", fassung),
    )

    private suspend fun schreibe(klient: HealthConnectClient, satz: Record): Boolean = try {
        klient.insertRecords(listOf(satz))
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Meist fehlt das Recht fuer diese eine Satzart.
        logger.w { satz::class.simpleName + " nicht eingetragen: " + e.message }
        false
    }
}
