package coredevices.pebble.kiesel

import android.content.Context
import android.content.SharedPreferences
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalBodyTemperatureRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.CervicalMucusRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.IntermenstrualBleedingRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OvulationTestRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PlannedExerciseSessionRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SexualActivityRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import boulder.austausch.Akteneintrag
import co.touchlab.kermit.Logger
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass

/**
 * Der Spiegel der ganzen Akte: jeder Satz aus Health Connect, aus jeder App, als [Akteneintrag].
 *
 * JE SATZART EIN STAND: zuerst eine Marke der Aenderungen holen, dann lesen, was die Akte hat
 * (die Erstlesung, aufsteigend nach Beginn), danach nur noch Aenderungen samt Loeschungen. Die
 * Marke kommt vor dem Lesen, damit nichts verloren geht, was waehrend der Erstlesung dazukommt;
 * doppelt Gesendetes ersetzt der Server nach Kennung.
 *
 * GESTUECKELT UND FORTSETZBAR: hoechstens [HOECHSTENS_SAETZE] Saetze oder etwa [HOECHSTENS_BYTES]
 * je Lieferung. Der Stand rueckt erst nach der Quittung vor; ein Lauf arbeitet hoechstens [FRIST_MS]
 * an der Akte, der naechste macht dort weiter - ein Jahr Puls einer fremden App sind viele Lieferungen.
 */
internal object Aktenspiegel {
    private val logger = Logger.withTag("Aktenspiegel")

    const val HOECHSTENS_SAETZE = 2_000
    const val HOECHSTENS_BYTES = 4_000_000

    /** WorkManager beendet eine Arbeit nach 10 Minuten; Tage, Naechte und Minuten brauchen ihren Teil davon. */
    const val FRIST_MS = 4 * 60_000L

    private const val SEITE = 1_000

    /** Alle Schluessel des Akte-Stands beginnen so; [vergiss] raeumt sie gemeinsam ab. */
    private const val P = "akte_"
    private const val VERGANGENHEIT = P + "vergangenheit"

    /** Eine Satzart der Akte; [funktion]: die Funktion, ohne die eine Akte sie nicht kennt. */
    class Satzart(val name: String, val klasse: KClass<out Record>, val funktion: Int? = null) {
        val recht: String get() = HealthPermission.getReadPermission(klasse)
    }

    @OptIn(ExperimentalMindfulnessSessionApi::class)
    val ARTEN: List<Satzart> = listOf(
        Satzart("ActiveCaloriesBurned", ActiveCaloriesBurnedRecord::class),
        Satzart("BasalBodyTemperature", BasalBodyTemperatureRecord::class),
        Satzart("BasalMetabolicRate", BasalMetabolicRateRecord::class),
        Satzart("BloodGlucose", BloodGlucoseRecord::class),
        Satzart("BloodPressure", BloodPressureRecord::class),
        Satzart("BodyFat", BodyFatRecord::class),
        Satzart("BodyTemperature", BodyTemperatureRecord::class),
        Satzart("BodyWaterMass", BodyWaterMassRecord::class),
        Satzart("BoneMass", BoneMassRecord::class),
        Satzart("CervicalMucus", CervicalMucusRecord::class),
        Satzart("CyclingPedalingCadence", CyclingPedalingCadenceRecord::class),
        Satzart("Distance", DistanceRecord::class),
        Satzart("ElevationGained", ElevationGainedRecord::class),
        Satzart("ExerciseSession", ExerciseSessionRecord::class),
        Satzart("FloorsClimbed", FloorsClimbedRecord::class),
        Satzart("HeartRate", HeartRateRecord::class),
        Satzart("HeartRateVariabilityRmssd", HeartRateVariabilityRmssdRecord::class),
        Satzart("Height", HeightRecord::class),
        Satzart("Hydration", HydrationRecord::class),
        Satzart("IntermenstrualBleeding", IntermenstrualBleedingRecord::class),
        Satzart("LeanBodyMass", LeanBodyMassRecord::class),
        Satzart("MenstruationFlow", MenstruationFlowRecord::class),
        Satzart("MenstruationPeriod", MenstruationPeriodRecord::class),
        Satzart("MindfulnessSession", MindfulnessSessionRecord::class, HealthConnectFeatures.FEATURE_MINDFULNESS_SESSION),
        Satzart("Nutrition", NutritionRecord::class),
        Satzart("OvulationTest", OvulationTestRecord::class),
        Satzart("OxygenSaturation", OxygenSaturationRecord::class),
        Satzart("PlannedExerciseSession", PlannedExerciseSessionRecord::class, HealthConnectFeatures.FEATURE_PLANNED_EXERCISE),
        Satzart("Power", PowerRecord::class),
        Satzart("RespiratoryRate", RespiratoryRateRecord::class),
        Satzart("RestingHeartRate", RestingHeartRateRecord::class),
        Satzart("SexualActivity", SexualActivityRecord::class),
        Satzart("SkinTemperature", SkinTemperatureRecord::class, HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE),
        Satzart("SleepSession", SleepSessionRecord::class),
        Satzart("Speed", SpeedRecord::class),
        Satzart("StepsCadence", StepsCadenceRecord::class),
        Satzart("Steps", StepsRecord::class),
        Satzart("TotalCaloriesBurned", TotalCaloriesBurnedRecord::class),
        Satzart("Vo2Max", Vo2MaxRecord::class),
        Satzart("Weight", WeightRecord::class),
        Satzart("WheelchairPushes", WheelchairPushesRecord::class),
    )

    /** Die Leserechte aller Satzarten; mehrere Arten teilen sich eines (Schritte und Schrittfrequenz). */
    val LESERECHTE: Set<String> = ARTEN.mapTo(linkedSetOf()) { it.recht }

    /** Die Leserechte, die es nur mit einer Funktion der Akte gibt (siehe FUNKTIONSRECHTE). */
    val FUNKTIONEN: Map<String, Int> = ARTEN.mapNotNull { a -> a.funktion?.let { a.recht to it } }.toMap()

    /** Wie der Spiegel liefert und seinen Stand schreibt. */
    interface Bote {
        suspend fun schicke(akte: List<Akteneintrag>, entfernt: List<String>): Spiegelpost.Antwort

        /** Schreibt den Stand, solange der Server noch gilt; false: das Senden ist ueberholt. */
        fun merke(e: SharedPreferences.Editor): Boolean
    }

    /** Was ein Lauf schickte, oder warum er aufhoerte. */
    sealed interface Ende {
        data class Gesendet(val saetze: Int, val entfernt: Int) : Ende

        /** Der Server lehnte ab oder war nicht da. */
        data class Abgelehnt(val antwort: Spiegelpost.Antwort) : Ende

        /** Ein Server vor 0.57.0: er nimmt die Lieferung an, uebergeht aber die Akte. */
        data object ServerZuAlt : Ende

        /** Waehrend des Sendens auf einen anderen Server umgestellt. */
        data object Ueberholt : Ende
    }

    /** Wie viele Satzarten die Akte freigibt, von wie vielen, die sie kennt, und ob sie im Hintergrund liest. */
    data class Zugang(val frei: Int, val alle: Int, val hintergrund: Boolean)

    /** Null ohne Akte oder wenn sie nicht antwortet. */
    suspend fun zugang(context: Context): Zugang? {
        val klient = Akte(context).bereit() ?: return null
        val erteilt = versuche("Aktenspiegel", "Rechte nicht lesbar") { klient.permissionController.getGrantedPermissions() } ?: return null
        val bekannt = nurVerfuegbare(LESERECHTE) { f ->
            versuche("Aktenspiegel", "Funktion $f nicht abfragbar") {
                klient.features.getFeatureStatus(f) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
            } ?: false
        }
        return Zugang(bekannt.count { it in erteilt }, bekannt.size, HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in erteilt)
    }

    fun vergiss(p: SharedPreferences, e: SharedPreferences.Editor) {
        p.all.keys.filter { it.startsWith(P) }.forEach { e.remove(it) }
    }

    /**
     * Sendet, was sich in der Akte geaendert hat, beim ersten Mal alles. NUR VON HAND ODER MIT DEM
     * RECHT, IM HINTERGRUND ZU LESEN: ohne es weist die Akte fremde Saetze ab oder laesst sie still
     * weg, und eine Erstlesung hielte die Akte dann fuer leer.
     */
    suspend fun sende(
        klient: HealthConnectClient,
        p: SharedPreferences,
        bote: Bote,
        vonHand: Boolean,
        uhrMs: () -> Long = System::currentTimeMillis,
    ): Ende {
        // Antwortet die Akte nicht, bleibt der Rest des Spiegels gesendet; der naechste Lauf versucht es wieder.
        val erteilt = versuche("Aktenspiegel", "Rechte nicht lesbar") { klient.permissionController.getGrantedPermissions() } ?: return Ende.Gesendet(0, 0)
        if (!vonHand && HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in erteilt) return Ende.Gesendet(0, 0)
        // Kam das Recht auf die Vergangenheit nach der Erstlesung, fehlt ihr alles vor ihren 30 Tagen.
        if (HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in erteilt && !p.getBoolean(VERGANGENHEIT, false)) {
            val e = p.edit().putBoolean(VERGANGENHEIT, true)
            for (a in ARTEN) if (p.contains(marke(a))) e.putLong(ab(a), 0)
            if (!bote.merke(e)) return Ende.Ueberholt
        }
        val frist = uhrMs() + FRIST_MS
        var saetze = 0
        var entfernt = 0
        for (art in ARTEN) {
            if (art.recht !in erteilt) continue
            if (uhrMs() > frist) break
            val ende = try {
                sendeArt(klient, art, p, bote) { uhrMs() > frist }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Eine Art, die die Akte gerade nicht liest (Recht entzogen, Rate-Limit), haelt die anderen nicht auf.
                logger.w { "Akte ${art.name} nicht gelesen: ${e.javaClass.simpleName}: ${e.message}" }
                continue
            }
            when (ende) {
                is Ende.Gesendet -> {
                    saetze += ende.saetze
                    entfernt += ende.entfernt
                }
                else -> return ende
            }
        }
        return Ende.Gesendet(saetze, entfernt)
    }

    private fun marke(a: Satzart) = P + "marke_" + a.name

    /** Die Erstlesung steht bei diesem Beginn (Millisekunden); fehlt der Schluessel, ist sie fertig. */
    private fun ab(a: Satzart) = P + "ab_" + a.name

    private suspend fun sendeArt(klient: HealthConnectClient, art: Satzart, p: SharedPreferences, bote: Bote, vorbei: () -> Boolean): Ende {
        val marke = p.getString(marke(art), null) ?: run {
            val neu = klient.getChangesToken(ChangesTokenRequest(setOf(art.klasse)))
            if (!bote.merke(p.edit().putString(marke(art), neu).putLong(ab(art), 0))) return Ende.Ueberholt
            neu
        }
        return if (p.contains(ab(art))) erstlesung(klient, art, p, p.getLong(ab(art), 0), bote, vorbei)
        else aenderungen(klient, art, p, marke, bote, vorbei)
    }

    private suspend fun erstlesung(klient: HealthConnectClient, art: Satzart, p: SharedPreferences, abMs: Long, bote: Bote, vorbei: () -> Boolean): Ende {
        val stapel = Stapel()
        var seite: String? = null
        var bisMs = abMs
        do {
            val antwort = klient.readRecords(
                ReadRecordsRequest(art.klasse, TimeRangeFilter.after(Instant.ofEpochMilli(abMs)), ascendingOrder = true, pageSize = SEITE, pageToken = seite),
            )
            for (r in antwort.records) {
                val e = Aktenwandler.wandle(r) ?: continue
                stapel.nimm(e)
                if (stapel.voll()) {
                    // Gleicher Beginn kann ueber die Grenze reichen: der naechste Lauf liest ab diesem Beginn noch einmal.
                    bisMs = e.beginn * 1000
                    stapel.sende(bote)?.let { return it }
                    if (!bote.merke(p.edit().putLong(ab(art), bisMs))) return Ende.Ueberholt
                }
            }
            seite = antwort.pageToken
        } while (seite != null && !vorbei())
        stapel.sende(bote)?.let { return it }
        val e = p.edit()
        if (seite == null) e.remove(ab(art)) else e.putLong(ab(art), stapel.letzterBeginnMs ?: bisMs)
        if (!bote.merke(e)) return Ende.Ueberholt
        return stapel.ergebnis()
    }

    private suspend fun aenderungen(klient: HealthConnectClient, art: Satzart, p: SharedPreferences, marke: String, bote: Bote, vorbei: () -> Boolean): Ende {
        val stapel = Stapel()
        var m = marke
        while (true) {
            val r = klient.getChanges(m)
            if (r.changesTokenExpired) {
                // Ungenutzt verfaellt eine Marke nach 30 Tagen: dann noch einmal alles, sonst fehlte, was dazwischen kam.
                logger.i { "Akte ${art.name}: Marke verfallen, lese neu" }
                return if (bote.merke(p.edit().remove(marke(art)).remove(ab(art)))) stapel.ergebnis() else Ende.Ueberholt
            }
            for (c in r.changes) when (c) {
                is UpsertionChange -> Aktenwandler.wandle(c.record)?.let { e ->
                    stapel.nimm(e)
                    if (stapel.voll()) stapel.sende(bote)?.let { return it }
                }
                is DeletionChange -> {
                    stapel.entferne(c.recordId)
                    if (stapel.voll()) stapel.sende(bote)?.let { return it }
                }
            }
            stapel.sende(bote)?.let { return it }
            // Die neue Marke erst, wenn alles bis zu ihr quittiert ist; brach es vorher ab, kommt es noch einmal.
            if (!bote.merke(p.edit().putString(marke(art), r.nextChangesToken))) return Ende.Ueberholt
            if (!r.hasMore || vorbei()) return stapel.ergebnis()
            m = r.nextChangesToken
        }
    }

    /** Sammelt Saetze und Loeschungen bis zu einer Lieferung und zaehlt, was quittiert ist. */
    private class Stapel {
        private val saetze = mutableListOf<Akteneintrag>()
        private val weg = mutableListOf<String>()
        private var bytes = 0
        private var gesendet = 0
        private var entfernt = 0
        var letzterBeginnMs: Long? = null
            private set

        fun nimm(e: Akteneintrag) {
            saetze += e
            bytes += groesse(e)
            letzterBeginnMs = e.beginn * 1000
        }

        fun entferne(id: String) {
            weg += id
            bytes += id.length + 4
        }

        fun voll() = saetze.size + weg.size >= HOECHSTENS_SAETZE || bytes >= HOECHSTENS_BYTES

        /** Null, wenn quittiert (oder nichts da war); sonst warum nicht. */
        suspend fun sende(bote: Bote): Ende? {
            if (saetze.isEmpty() && weg.isEmpty()) return null
            val a = bote.schicke(saetze.toList(), weg.toList())
            if (a !is Spiegelpost.Antwort.Angenommen) return Ende.Abgelehnt(a)
            if (a.quittung.akte == null) return Ende.ServerZuAlt
            gesendet += saetze.size
            entfernt += weg.size
            saetze.clear()
            weg.clear()
            bytes = 0
            return null
        }

        fun ergebnis() = Ende.Gesendet(gesendet, entfernt)
    }

    /** Ungefaehr die Laenge im JSON; genau muss es nicht sein, nur weit unter den 20 MB des Servers bleiben. */
    internal fun groesse(e: Akteneintrag): Int =
        160 + e.id.length + e.quelle.length + e.werte.size * 40 + e.texte.entries.sumOf { it.key.length + it.value.length + 8 } +
            e.proben.size * 30 + e.abschnitte.size * 70
}
