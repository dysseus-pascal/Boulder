package coredevices.pebble.kiesel

import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SpeedRecord

/**
 * Was die Kiesel-Aufgaben in der Akte zusaetzlich brauchen - ueber den Sync
 * hinaus.
 *
 * SCHREIBEN: die HRV von Yoga und der Einzelmessung, das Wasser von
 * Drinktervall, die Praeparate von SupCycle, Kalorien, Distanz, Hoehe und
 * Tempo der Trainings. LESEN: den Blutdruck einer Manschetten-App, Gewicht und
 * Zusammensetzung einer Waagen-App ([KOERPER_RECHTE]), die Distanz fremder
 * Trainings ohne Route, das Wasser (ob schon
 * jemand dasselbe Glas eintrug), die Trainings,
 * ihre Pulskurve, SpO2 und HRV fuer die Trainingsansicht und die SpO2-Frage
 * der Uhr. Nur nach Moeglichkeit - fehlt eines, laeuft der Rest trotzdem.
 * Dazu das Lesen im Hintergrund (das Widget sieht so auch fremde Eintraege,
 * siehe [liesOderEigene]) und das Lesen ueber 30 Tage zurueck - beide nur, wo
 * die Akte sie kennt (siehe [nurVerfuegbare]).
 */
internal val KIESEL_RECHTE: Set<String> = setOf(
    HealthPermission.getWritePermission(HeartRateVariabilityRmssdRecord::class),
    HealthPermission.getReadPermission(OxygenSaturationRecord::class),
    HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    HealthPermission.getReadPermission(HeartRateRecord::class),
    HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
    HealthPermission.getWritePermission(HydrationRecord::class),
    HealthPermission.getReadPermission(HydrationRecord::class),
    HealthPermission.getWritePermission(NutritionRecord::class),
    HealthPermission.getReadPermission(NutritionRecord::class),
    // Die Naechte der Firmware: Ruhepuls neben Schlaf und HRV.
    HealthPermission.getWritePermission(RestingHeartRateRecord::class),
    HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
    HealthPermission.getWritePermission(DistanceRecord::class),
    // Die Strecke fremder Trainings ohne Route (Strava-Lauf, Laufband).
    HealthPermission.getReadPermission(DistanceRecord::class),
    HealthPermission.getWritePermission(ElevationGainedRecord::class),
    HealthPermission.getWritePermission(SpeedRecord::class),
    HealthPermission.getReadPermission(BloodPressureRecord::class),
    *KOERPER_RECHTE.toTypedArray(),
    HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
    HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
)

/**
 * Die Rechte, die es nur mit einer Funktion der Akte gibt. Eine Akte ohne die
 * Funktion kennt das Recht nicht und erteilt es nie - gefragt und gezaehlt
 * stuende es dort fuer immer als fehlend da.
 */
internal val FUNKTIONSRECHTE: Map<String, Int> = mapOf(
    HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND to HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND,
    HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY to HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY,
)

/** Die Rechte ohne die, deren Funktion diese Akte nicht hat; alle anderen bleiben. */
internal fun nurVerfuegbare(rechte: Set<String>, hatFunktion: (Int) -> Boolean): Set<String> =
    rechte.filterTo(mutableSetOf()) { recht -> FUNKTIONSRECHTE[recht]?.let(hatFunktion) ?: true }
