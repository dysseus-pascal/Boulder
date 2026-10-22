package coredevices.pebble.kiesel

import androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalBodyTemperatureRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureMeasurementLocation
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.CervicalMucusRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseLap
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.IntermenstrualBleedingRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MealType
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
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.Pressure
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.TemperatureDelta
import androidx.health.connect.client.units.Velocity
import androidx.health.connect.client.units.Volume
import boulder.austausch.Probe
import boulder.austausch.Spanne
import coredevices.pebble.kiesel.ablauf.FakeAkte
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Boulder: jeder Satz aus Health Connect als Akteneintrag ([Aktenwandler]) - alle Satzarten, die der
 * Spiegel liest, mit Einheiten und den Namen, mit denen Health Connect selbst exportiert.
 */
@OptIn(ExperimentalMindfulnessSessionApi::class)
class AktenwandlerTest {
    private val t = Instant.ofEpochSecond(1_792_152_000L)
    private val bis = t.plusSeconds(600)
    private val z = ZoneOffset.ofHours(2)
    private val m: Metadata get() = Metadata.manualEntry()

    /** Je Satzart ein Satz, wie ihn eine fremde App schreibt. */
    private val saetze: List<Record> = listOf(
        ActiveCaloriesBurnedRecord(t, z, bis, z, Energy.kilocalories(50.0), m),
        BasalBodyTemperatureRecord(t, z, m, Temperature.celsius(36.4), BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_MOUTH),
        BasalMetabolicRateRecord(t, z, Power.kilocaloriesPerDay(1600.0), m),
        BloodGlucoseRecord(t, z, m, BloodGlucose.millimolesPerLiter(5.4), BloodGlucoseRecord.SPECIMEN_SOURCE_CAPILLARY_BLOOD,
            MealType.MEAL_TYPE_BREAKFAST, BloodGlucoseRecord.RELATION_TO_MEAL_FASTING),
        BloodPressureRecord(t, z, m, Pressure.millimetersOfMercury(121.0), Pressure.millimetersOfMercury(79.0),
            BloodPressureRecord.BODY_POSITION_SITTING_DOWN, BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_UPPER_ARM),
        BodyFatRecord(t, z, Percentage(18.5), m),
        BodyTemperatureRecord(t, z, m, Temperature.celsius(37.1), BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_FOREHEAD),
        BodyWaterMassRecord(t, z, Mass.kilograms(40.0), m),
        BoneMassRecord(t, z, Mass.kilograms(3.1), m),
        CervicalMucusRecord(t, z, m, CervicalMucusRecord.APPEARANCE_CREAMY, CervicalMucusRecord.SENSATION_LIGHT),
        CyclingPedalingCadenceRecord(t, z, bis, z, listOf(CyclingPedalingCadenceRecord.Sample(t, 85.0)), m),
        DistanceRecord(t, z, bis, z, Length.meters(1200.0), m),
        ElevationGainedRecord(t, z, bis, z, Length.meters(30.0), m),
        ExerciseSessionRecord(t, z, bis, z, m, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, "Lauf, kurz", "gut",
            segments = listOf(ExerciseSegment(t, t.plusSeconds(300), ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING, 0)),
            laps = listOf(ExerciseLap(t, t.plusSeconds(300), Length.meters(1000.0)))),
        FloorsClimbedRecord(t, z, bis, z, 3.0, m),
        HeartRateRecord(t, z, bis, z, listOf(HeartRateRecord.Sample(t, 70), HeartRateRecord.Sample(t.plusSeconds(60), 74)), m),
        HeartRateVariabilityRmssdRecord(t, z, 55.0, m),
        HeightRecord(t, z, Length.meters(1.8), m),
        HydrationRecord(t, z, bis, z, Volume.milliliters(250.0), m),
        IntermenstrualBleedingRecord(t, z, m),
        LeanBodyMassRecord(t, z, Mass.kilograms(60.0), m),
        MenstruationFlowRecord(t, z, m, MenstruationFlowRecord.FLOW_MEDIUM),
        MenstruationPeriodRecord(t, z, bis, z, m),
        MindfulnessSessionRecord(t, z, bis, z, m, MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_BREATHING, null, null),
        NutritionRecord(t, z, bis, z, m, caffeine = Mass.milligrams(63.0), energy = Energy.kilocalories(2.0), protein = Mass.grams(0.3),
            vitaminD = Mass.micrograms(5.0), name = "Espresso", mealType = MealType.MEAL_TYPE_SNACK),
        OvulationTestRecord(t, z, OvulationTestRecord.RESULT_NEGATIVE, m),
        OxygenSaturationRecord(t, z, Percentage(97.0), m),
        PlannedExerciseSessionRecord(t, z, bis, z, m, emptyList(), ExerciseSessionRecord.EXERCISE_TYPE_BIKING, "Plan", null),
        PowerRecord(t, z, bis, z, listOf(PowerRecord.Sample(t, Power.watts(210.0))), m),
        RespiratoryRateRecord(t, z, 14.0, m),
        RestingHeartRateRecord(t, z, 52, m),
        SexualActivityRecord(t, z, m, SexualActivityRecord.PROTECTION_USED_PROTECTED),
        SkinTemperatureRecord(t, z, bis, z, m, listOf(SkinTemperatureRecord.Delta(t, TemperatureDelta.celsius(0.3))),
            Temperature.celsius(33.0), SkinTemperatureRecord.MEASUREMENT_LOCATION_WRIST),
        SleepSessionRecord(t, z, bis, z, m, "Nacht", null, listOf(SleepSessionRecord.Stage(t, t.plusSeconds(300), SleepSessionRecord.STAGE_TYPE_DEEP))),
        SpeedRecord(t, z, bis, z, listOf(SpeedRecord.Sample(t, Velocity.metersPerSecond(3.25))), m),
        StepsCadenceRecord(t, z, bis, z, listOf(StepsCadenceRecord.Sample(t, 160.0)), m),
        StepsRecord(t, z, bis, z, 1200, m),
        TotalCaloriesBurnedRecord(t, z, bis, z, Energy.kilocalories(2100.0), m),
        Vo2MaxRecord(t, z, m, 48.0, Vo2MaxRecord.MEASUREMENT_METHOD_COOPER_TEST),
        WeightRecord(t, z, Mass.kilograms(72.4), m),
        WheelchairPushesRecord(t, z, bis, z, 300, m),
    )

    /** Die Saetze mit Kennung und Herkunft, wie die Akte sie herausgibt. */
    private fun ausDerAkte(): List<Record> {
        val akte = FakeAkte("ch.dysseus.boulder")
        saetze.forEach { akte.legeAb(it, "com.example.app") }
        return akte.alle().map { it.satz }
    }

    private fun eintrag(art: String) = ausDerAkte().map { assertNotNull(Aktenwandler.wandle(it)) }.single { it.art == art }

    @Test
    fun jedeSatzartDesSpiegelsWirdGewandelt() {
        val saetze = ausDerAkte()
        // Ohne Zweig im Wandler ginge eine Satzart still verloren, obwohl ihr Recht gefragt wird.
        for (a in Aktenspiegel.ARTEN) {
            val r = saetze.single { it::class == a.klasse }
            val e = assertNotNull(Aktenwandler.wandle(r), a.name)
            assertEquals(a.name, e.art)
            assertEquals("com.example.app", e.quelle)
            assertEquals(r.metadata.id, e.id)
            assertEquals(t.epochSecond, e.beginn)
            assertEquals(7200, e.versatz)
        }
        assertEquals(41, Aktenspiegel.ARTEN.size)
    }

    @Test
    fun einheitenUndNamenDerAkte() {
        assertEquals(mapOf("kg" to 72.4), eintrag("Weight").werte)
        assertEquals(null, eintrag("Weight").ende)
        val druck = eintrag("BloodPressure")
        assertEquals(mapOf("systolic_mmhg" to 121.0, "diastolic_mmhg" to 79.0), druck.werte)
        assertEquals("sitting_down", druck.texte["body_position"])
        assertEquals("left_upper_arm", druck.texte["measurement_location"])
        assertEquals("manual entry", druck.texte["recording"])
        val schlaf = eintrag("SleepSession")
        assertEquals(listOf(Spanne(t.epochSecond, t.epochSecond + 300, "deep")), schlaf.abschnitte)
        assertEquals(bis.epochSecond, schlaf.ende)
        val lauf = eintrag("ExerciseSession")
        assertEquals("running", lauf.texte["exercise_type"])
        assertEquals("Lauf, kurz", lauf.texte["title"])
        assertEquals(listOf(Spanne(t.epochSecond, t.epochSecond + 300, "segment running"), Spanne(t.epochSecond, t.epochSecond + 300, "lap", 1000.0)), lauf.abschnitte)
        assertEquals(listOf(Probe(t.epochSecond, 70.0), Probe(t.epochSecond + 60, 74.0)), eintrag("HeartRate").proben)
        // Koffein in Milligramm, Vitamin D in Mikrogramm: in Gramm blieben bei zwei Stellen nur Nullen.
        val espresso = eintrag("Nutrition")
        assertEquals(63.0, espresso.werte["caffeine_mg"]!!, 1e-9)
        assertEquals(0.3, espresso.werte["protein_g"]!!, 1e-9)
        assertEquals(5.0, espresso.werte["vitamin_d_ug"]!!, 1e-9)
        assertEquals(2.0, espresso.werte["energy_kcal"]!!, 1e-9)
        assertEquals(mapOf("name" to "Espresso", "meal_type" to "snack", "recording" to "manual entry"), espresso.texte)
        assertEquals(mapOf("steps" to 1200.0), eintrag("Steps").werte)
        assertEquals("breathing", eintrag("MindfulnessSession").texte["mindfulness_type"])
        assertEquals(mapOf("baseline_celsius" to 33.0), eintrag("SkinTemperature").werte)
    }

    @Test
    fun geraetUndUnbekannteZahl() {
        val akte = FakeAkte("ch.dysseus.boulder")
        akte.legeAb(
            WeightRecord(t, z, Mass.kilograms(70.0), Metadata.autoRecorded(Device(manufacturer = "Withings", model = "Body+", type = Device.TYPE_SCALE))),
            "com.withings.wiscale2",
        )
        // Eine Zahl, die die Tabelle (noch) nicht kennt, geht als Zahl weiter, statt den Satz zu verwerfen.
        akte.legeAb(BloodPressureRecord(t, z, m, Pressure.millimetersOfMercury(120.0), Pressure.millimetersOfMercury(80.0), 99, 0), "com.example.app")
        val (waage, druck) = akte.alle().map { assertNotNull(Aktenwandler.wandle(it.satz)) }
        assertEquals("Withings Body+ (scale)", waage.texte["device"])
        assertEquals("automatic", waage.texte["recording"])
        assertEquals("99", druck.texte["body_position"])
    }
}
