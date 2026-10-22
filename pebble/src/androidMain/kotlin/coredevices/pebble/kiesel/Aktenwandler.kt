package coredevices.pebble.kiesel

import android.annotation.SuppressLint
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
import androidx.health.connect.client.units.Mass
import boulder.austausch.Akteneintrag
import boulder.austausch.Probe
import boulder.austausch.Spanne
import java.time.Instant
import java.time.ZoneOffset

/**
 * Jeder Satz aus Health Connect als [Akteneintrag] fuer den Spiegel. Die Namen sind englisch und
 * nennen die Einheit (kg, mmhg, kcal), weil der Server sie Claude so zeigt.
 *
 * DIE NAMEN DER KONSTANTEN (Trainingsart, Schlafphase, Messort ...) stammen aus den Tabellen der
 * Bibliothek, mit denen Health Connect selbst exportiert. Sie sind als RestrictTo markiert, aber die
 * Zahlen dahinter stehen in jeder Akte gespeichert und aendern sich nicht. Unbekannte Zahlen gehen
 * als Zahl weiter, statt den Satz zu verwerfen.
 */
@SuppressLint("RestrictedApi")
@OptIn(ExperimentalMindfulnessSessionApi::class)
internal object Aktenwandler {

    /** Null fuer eine Satzart, die der Spiegel nicht kennt - eine neuere Bibliothek kann mehr liefern. */
    fun wandle(r: Record): Akteneintrag? = when (r) {
        is ActiveCaloriesBurnedRecord -> spanne(r, "ActiveCaloriesBurned", r.startTime, r.endTime, r.startZoneOffset, werte = mapOf("kcal" to r.energy.inKilocalories))
        is BasalBodyTemperatureRecord -> punkt(r, "BasalBodyTemperature", r.time, r.zoneOffset, werte = mapOf("celsius" to r.temperature.inCelsius),
            texte = mapOf("measurement_location" to name(BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_INT_TO_STRING_MAP, r.measurementLocation)))
        is BasalMetabolicRateRecord -> punkt(r, "BasalMetabolicRate", r.time, r.zoneOffset, werte = mapOf("kcal_per_day" to r.basalMetabolicRate.inKilocaloriesPerDay))
        is BloodGlucoseRecord -> punkt(r, "BloodGlucose", r.time, r.zoneOffset, werte = mapOf("mmol_per_l" to r.level.inMillimolesPerLiter),
            texte = mapOf(
                "specimen_source" to name(BloodGlucoseRecord.SPECIMEN_SOURCE_INT_TO_STRING_MAP, r.specimenSource),
                "meal_type" to name(MealType.MEAL_TYPE_INT_TO_STRING_MAP, r.mealType),
                "relation_to_meal" to name(BloodGlucoseRecord.RELATION_TO_MEAL_INT_TO_STRING_MAP, r.relationToMeal),
            ))
        is BloodPressureRecord -> punkt(r, "BloodPressure", r.time, r.zoneOffset,
            werte = mapOf("systolic_mmhg" to r.systolic.inMillimetersOfMercury, "diastolic_mmhg" to r.diastolic.inMillimetersOfMercury),
            texte = mapOf(
                "body_position" to name(BloodPressureRecord.BODY_POSITION_INT_TO_STRING_MAP, r.bodyPosition),
                "measurement_location" to name(BloodPressureRecord.MEASUREMENT_LOCATION_INT_TO_STRING_MAP, r.measurementLocation),
            ))
        is BodyFatRecord -> punkt(r, "BodyFat", r.time, r.zoneOffset, werte = mapOf("percent" to r.percentage.value))
        is BodyTemperatureRecord -> punkt(r, "BodyTemperature", r.time, r.zoneOffset, werte = mapOf("celsius" to r.temperature.inCelsius),
            texte = mapOf("measurement_location" to name(BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_INT_TO_STRING_MAP, r.measurementLocation)))
        is BodyWaterMassRecord -> punkt(r, "BodyWaterMass", r.time, r.zoneOffset, werte = mapOf("kg" to r.mass.inKilograms))
        is BoneMassRecord -> punkt(r, "BoneMass", r.time, r.zoneOffset, werte = mapOf("kg" to r.mass.inKilograms))
        is CervicalMucusRecord -> punkt(r, "CervicalMucus", r.time, r.zoneOffset, texte = mapOf(
            "appearance" to name(CervicalMucusRecord.APPEARANCE_INT_TO_STRING_MAP, r.appearance),
            "sensation" to name(CervicalMucusRecord.SENSATION_INT_TO_STRING_MAP, r.sensation),
        ))
        is CyclingPedalingCadenceRecord -> spanne(r, "CyclingPedalingCadence", r.startTime, r.endTime, r.startZoneOffset,
            proben = r.samples.map { Probe(it.time.epochSecond, it.revolutionsPerMinute) })
        is DistanceRecord -> spanne(r, "Distance", r.startTime, r.endTime, r.startZoneOffset, werte = mapOf("m" to r.distance.inMeters))
        is ElevationGainedRecord -> spanne(r, "ElevationGained", r.startTime, r.endTime, r.startZoneOffset, werte = mapOf("m" to r.elevation.inMeters))
        is ExerciseSessionRecord -> spanne(r, "ExerciseSession", r.startTime, r.endTime, r.startZoneOffset,
            texte = mapOf(
                "exercise_type" to name(ExerciseSessionRecord.EXERCISE_TYPE_INT_TO_STRING_MAP, r.exerciseType),
                "title" to r.title.orEmpty(), "notes" to r.notes.orEmpty(),
            ),
            // Die Route bleibt draussen: fremde Routen gibt Health Connect nur je Training mit Zustimmung heraus.
            abschnitte = r.segments.map { Spanne(it.startTime.epochSecond, it.endTime.epochSecond, "segment " + name(SEGMENTE, it.segmentType), it.repetitions.toDouble().takeIf { n -> n > 0 }) } +
                r.laps.map { Spanne(it.startTime.epochSecond, it.endTime.epochSecond, "lap", it.length?.inMeters) })
        is FloorsClimbedRecord -> spanne(r, "FloorsClimbed", r.startTime, r.endTime, r.startZoneOffset, werte = mapOf("floors" to r.floors))
        is HeartRateRecord -> spanne(r, "HeartRate", r.startTime, r.endTime, r.startZoneOffset,
            proben = r.samples.map { Probe(it.time.epochSecond, it.beatsPerMinute.toDouble()) })
        is HeartRateVariabilityRmssdRecord -> punkt(r, "HeartRateVariabilityRmssd", r.time, r.zoneOffset, werte = mapOf("ms" to r.heartRateVariabilityMillis))
        is HeightRecord -> punkt(r, "Height", r.time, r.zoneOffset, werte = mapOf("m" to r.height.inMeters))
        is HydrationRecord -> spanne(r, "Hydration", r.startTime, r.endTime, r.startZoneOffset, werte = mapOf("ml" to r.volume.inMilliliters))
        is IntermenstrualBleedingRecord -> punkt(r, "IntermenstrualBleeding", r.time, r.zoneOffset)
        is LeanBodyMassRecord -> punkt(r, "LeanBodyMass", r.time, r.zoneOffset, werte = mapOf("kg" to r.mass.inKilograms))
        is MenstruationFlowRecord -> punkt(r, "MenstruationFlow", r.time, r.zoneOffset,
            texte = mapOf("flow" to name(MenstruationFlowRecord.FLOW_TYPE_INT_TO_STRING_MAP, r.flow)))
        is MenstruationPeriodRecord -> spanne(r, "MenstruationPeriod", r.startTime, r.endTime, r.startZoneOffset)
        is MindfulnessSessionRecord -> spanne(r, "MindfulnessSession", r.startTime, r.endTime, r.startZoneOffset, texte = mapOf(
            "mindfulness_type" to name(MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_INT_TO_STRING_MAP, r.mindfulnessSessionType),
            "title" to r.title.orEmpty(), "notes" to r.notes.orEmpty(),
        ))
        is NutritionRecord -> spanne(r, "Nutrition", r.startTime, r.endTime, r.startZoneOffset,
            werte = naehrwerte(r), texte = mapOf("name" to r.name.orEmpty(), "meal_type" to name(MealType.MEAL_TYPE_INT_TO_STRING_MAP, r.mealType)))
        is OvulationTestRecord -> punkt(r, "OvulationTest", r.time, r.zoneOffset,
            texte = mapOf("result" to name(OvulationTestRecord.RESULT_INT_TO_STRING_MAP, r.result)))
        is OxygenSaturationRecord -> punkt(r, "OxygenSaturation", r.time, r.zoneOffset, werte = mapOf("percent" to r.percentage.value))
        is PlannedExerciseSessionRecord -> spanne(r, "PlannedExerciseSession", r.startTime, r.endTime, r.startZoneOffset, texte = mapOf(
            "exercise_type" to name(ExerciseSessionRecord.EXERCISE_TYPE_INT_TO_STRING_MAP, r.exerciseType),
            "title" to r.title.orEmpty(), "notes" to r.notes.orEmpty(),
            "completed_session_id" to r.completedExerciseSessionId.orEmpty(),
        ), werte = mapOf("blocks" to r.blocks.size.toDouble()))
        is PowerRecord -> spanne(r, "Power", r.startTime, r.endTime, r.startZoneOffset, proben = r.samples.map { Probe(it.time.epochSecond, it.power.inWatts) })
        is RespiratoryRateRecord -> punkt(r, "RespiratoryRate", r.time, r.zoneOffset, werte = mapOf("per_min" to r.rate))
        is RestingHeartRateRecord -> punkt(r, "RestingHeartRate", r.time, r.zoneOffset, werte = mapOf("bpm" to r.beatsPerMinute.toDouble()))
        is SexualActivityRecord -> punkt(r, "SexualActivity", r.time, r.zoneOffset,
            texte = mapOf("protection_used" to name(SexualActivityRecord.PROTECTION_USED_INT_TO_STRING_MAP, r.protectionUsed)))
        is SkinTemperatureRecord -> spanne(r, "SkinTemperature", r.startTime, r.endTime, r.startZoneOffset,
            werte = r.baseline?.let { mapOf("baseline_celsius" to it.inCelsius) }.orEmpty(),
            texte = mapOf("measurement_location" to name(SkinTemperatureRecord.MEASUREMENT_LOCATION_INT_TO_STRING_MAP, r.measurementLocation)),
            proben = r.deltas.map { Probe(it.time.epochSecond, it.delta.inCelsius) })
        is SleepSessionRecord -> spanne(r, "SleepSession", r.startTime, r.endTime, r.startZoneOffset,
            texte = mapOf("title" to r.title.orEmpty(), "notes" to r.notes.orEmpty()),
            abschnitte = r.stages.map { Spanne(it.startTime.epochSecond, it.endTime.epochSecond, name(SleepSessionRecord.STAGE_TYPE_INT_TO_STRING_MAP, it.stage)) })
        is SpeedRecord -> spanne(r, "Speed", r.startTime, r.endTime, r.startZoneOffset, proben = r.samples.map { Probe(it.time.epochSecond, it.speed.inMetersPerSecond) })
        is StepsCadenceRecord -> spanne(r, "StepsCadence", r.startTime, r.endTime, r.startZoneOffset, proben = r.samples.map { Probe(it.time.epochSecond, it.rate) })
        is StepsRecord -> spanne(r, "Steps", r.startTime, r.endTime, r.startZoneOffset, werte = mapOf("steps" to r.count.toDouble()))
        is TotalCaloriesBurnedRecord -> spanne(r, "TotalCaloriesBurned", r.startTime, r.endTime, r.startZoneOffset, werte = mapOf("kcal" to r.energy.inKilocalories))
        is Vo2MaxRecord -> punkt(r, "Vo2Max", r.time, r.zoneOffset, werte = mapOf("ml_per_kg_min" to r.vo2MillilitersPerMinuteKilogram),
            texte = mapOf("measurement_method" to name(Vo2MaxRecord.MEASUREMENT_METHOD_INT_TO_STRING_MAP, r.measurementMethod)))
        is WeightRecord -> punkt(r, "Weight", r.time, r.zoneOffset, werte = mapOf("kg" to r.weight.inKilograms))
        is WheelchairPushesRecord -> spanne(r, "WheelchairPushes", r.startTime, r.endTime, r.startZoneOffset, werte = mapOf("pushes" to r.count.toDouble()))
        else -> null
    }

    /**
     * Gramm fuer die Hauptnaehrstoffe, Milligramm und Mikrogramm fuer die kleinen, wie auf einer
     * Packung: Koffein in Gramm verloere bei zwei Nachkommastellen seine Stellen.
     */
    private fun naehrwerte(r: NutritionRecord): Map<String, Double> = buildMap {
        fun g(name: String, m: Mass?) = m?.let { put("${name}_g", it.inGrams) }
        fun mg(name: String, m: Mass?) = m?.let { put("${name}_mg", it.inMilligrams) }
        fun ug(name: String, m: Mass?) = m?.let { put("${name}_ug", it.inMicrograms) }
        r.energy?.let { put("energy_kcal", it.inKilocalories) }
        r.energyFromFat?.let { put("energy_from_fat_kcal", it.inKilocalories) }
        g("protein", r.protein); g("total_fat", r.totalFat); g("saturated_fat", r.saturatedFat)
        g("unsaturated_fat", r.unsaturatedFat); g("monounsaturated_fat", r.monounsaturatedFat)
        g("polyunsaturated_fat", r.polyunsaturatedFat); g("trans_fat", r.transFat)
        g("total_carbohydrate", r.totalCarbohydrate); g("sugar", r.sugar); g("dietary_fiber", r.dietaryFiber)
        mg("caffeine", r.caffeine); mg("cholesterol", r.cholesterol); mg("sodium", r.sodium); mg("potassium", r.potassium)
        mg("calcium", r.calcium); mg("chloride", r.chloride); mg("iron", r.iron); mg("magnesium", r.magnesium)
        mg("manganese", r.manganese); mg("phosphorus", r.phosphorus); mg("zinc", r.zinc); mg("copper", r.copper)
        mg("niacin", r.niacin); mg("pantothenic_acid", r.pantothenicAcid); mg("riboflavin", r.riboflavin)
        mg("thiamin", r.thiamin); mg("vitamin_b6", r.vitaminB6); mg("vitamin_c", r.vitaminC); mg("vitamin_e", r.vitaminE)
        ug("vitamin_a", r.vitaminA); ug("vitamin_b12", r.vitaminB12); ug("vitamin_d", r.vitaminD); ug("vitamin_k", r.vitaminK)
        ug("folate", r.folate); ug("folic_acid", r.folicAcid); ug("biotin", r.biotin); ug("iodine", r.iodine)
        ug("selenium", r.selenium); ug("chromium", r.chromium); ug("molybdenum", r.molybdenum)
    }

    private fun punkt(
        r: Record, art: String, zeit: Instant, versatz: ZoneOffset?,
        werte: Map<String, Double> = emptyMap(), texte: Map<String, String> = emptyMap(),
    ) = eintrag(r, art, zeit, null, versatz, werte, texte, emptyList(), emptyList())

    private fun spanne(
        r: Record, art: String, beginn: Instant, ende: Instant, versatz: ZoneOffset?,
        werte: Map<String, Double> = emptyMap(), texte: Map<String, String> = emptyMap(),
        proben: List<Probe> = emptyList(), abschnitte: List<Spanne> = emptyList(),
    ) = eintrag(r, art, beginn, ende, versatz, werte, texte, proben, abschnitte)

    private fun eintrag(
        r: Record, art: String, beginn: Instant, ende: Instant?, versatz: ZoneOffset?,
        werte: Map<String, Double>, texte: Map<String, String>, proben: List<Probe>, abschnitte: List<Spanne>,
    ): Akteneintrag {
        val m = r.metadata
        // Leere Texte fallen weg; die Herkunft der Messung (Geraet, von Hand) zeigt Claude, wie verlaesslich ein Wert ist.
        val alle = texte.filterValues { it.isNotBlank() } + listOfNotNull(
            geraet(m.device)?.let { "device" to it },
            AUFNAHME[m.recordingMethod]?.let { "recording" to it },
        )
        return Akteneintrag(
            id = m.id, art = art, beginn = beginn.epochSecond, ende = ende?.epochSecond, versatz = versatz?.totalSeconds,
            quelle = m.dataOrigin.packageName, werte = werte.filterValues { it.isFinite() }, texte = alle,
            proben = proben.filter { it.w.isFinite() }, abschnitte = abschnitte,
        )
    }

    private fun geraet(d: Device?): String? {
        if (d == null) return null
        val teile = listOfNotNull(d.manufacturer, d.model, GERAETEART[d.type]).filter { it.isNotBlank() }
        return teile.joinToString(" ").ifBlank { null }
    }

    private fun name(tabelle: Map<Int, String>, wert: Int): String = tabelle[wert] ?: wert.toString()

    private val AUFNAHME = mapOf(
        Metadata.RECORDING_METHOD_MANUAL_ENTRY to "manual entry",
        Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED to "automatic",
        Metadata.RECORDING_METHOD_ACTIVELY_RECORDED to "active recording",
    )

    private val GERAETEART = mapOf(
        Device.TYPE_WATCH to "(watch)", Device.TYPE_PHONE to "(phone)", Device.TYPE_SCALE to "(scale)",
        Device.TYPE_RING to "(ring)", Device.TYPE_HEAD_MOUNTED to "(head-mounted)", Device.TYPE_FITNESS_BAND to "(fitness band)",
        Device.TYPE_CHEST_STRAP to "(chest strap)", Device.TYPE_SMART_DISPLAY to "(smart display)",
    )

    /** Die Abschnitte eines Trainings; die Bibliothek fuehrt dafuer keine Namenstabelle. Erzeugt aus ihren Konstanten. */
    private val SEGMENTE = mapOf(
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_UNKNOWN to "unknown",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL to "arm_curl",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_BACK_EXTENSION to "back_extension",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_BALL_SLAM to "ball_slam",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_BARBELL_SHOULDER_PRESS to "barbell_shoulder_press",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS to "bench_press",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_SIT_UP to "bench_sit_up",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING to "biking",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING_STATIONARY to "biking_stationary",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_BURPEE to "burpee",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_CRUNCH to "crunch",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_DEADLIFT to "deadlift",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_DOUBLE_ARM_TRICEPS_EXTENSION to "double_arm_triceps_extension",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_CURL_LEFT_ARM to "dumbbell_curl_left_arm",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_CURL_RIGHT_ARM to "dumbbell_curl_right_arm",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_FRONT_RAISE to "dumbbell_front_raise",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_LATERAL_RAISE to "dumbbell_lateral_raise",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_ROW to "dumbbell_row",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_TRICEPS_EXTENSION_LEFT_ARM to "dumbbell_triceps_extension_left_arm",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_TRICEPS_EXTENSION_RIGHT_ARM to "dumbbell_triceps_extension_right_arm",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_TRICEPS_EXTENSION_TWO_ARM to "dumbbell_triceps_extension_two_arm",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_ELLIPTICAL to "elliptical",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_FORWARD_TWIST to "forward_twist",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_FRONT_RAISE to "front_raise",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to "high_intensity_interval_training",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_HIP_THRUST to "hip_thrust",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_HULA_HOOP to "hula_hoop",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_JUMPING_JACK to "jumping_jack",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_JUMP_ROPE to "jump_rope",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_KETTLEBELL_SWING to "kettlebell_swing",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_LATERAL_RAISE to "lateral_raise",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_LAT_PULL_DOWN to "lat_pull_down",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_CURL to "leg_curl",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_EXTENSION to "leg_extension",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_PRESS to "leg_press",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_RAISE to "leg_raise",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_LUNGE to "lunge",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_MOUNTAIN_CLIMBER to "mountain_climber",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT to "other_workout",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE to "pause",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_PILATES to "pilates",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_PLANK to "plank",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_PULL_UP to "pull_up",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_PUNCH to "punch",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_REST to "rest",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_ROWING_MACHINE to "rowing_machine",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING to "running",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING_TREADMILL to "running_treadmill",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_SHOULDER_PRESS to "shoulder_press",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_SINGLE_ARM_TRICEPS_EXTENSION to "single_arm_triceps_extension",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_SIT_UP to "sit_up",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT to "squat",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_STAIR_CLIMBING to "stair_climbing",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_STAIR_CLIMBING_MACHINE to "stair_climbing_machine",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING to "stretching",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_BACKSTROKE to "swimming_backstroke",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_BREASTSTROKE to "swimming_breaststroke",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_BUTTERFLY to "swimming_butterfly",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_FREESTYLE to "swimming_freestyle",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_MIXED to "swimming_mixed",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_OPEN_WATER to "swimming_open_water",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_OTHER to "swimming_other",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_POOL to "swimming_pool",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_UPPER_TWIST to "upper_twist",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_WALKING to "walking",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_WEIGHTLIFTING to "weightlifting",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_WHEELCHAIR to "wheelchair",
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_YOGA to "yoga",
    )
}
