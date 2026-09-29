package coredevices.pebble.kiesel

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord

/**
 * Was Kieselsport in der Akte zusaetzlich braucht - ueber den Sync hinaus.
 *
 * SCHREIBEN: die HRV von Yoga und der Einzelmessung. LESEN: die Trainings,
 * ihre Pulskurve, SpO2 und HRV fuer die Trainingsansicht und die SpO2-Frage
 * der Uhr. Nur nach Moeglichkeit - fehlt eines, laeuft der Rest trotzdem.
 */
internal val KIESELSPORT_RECHTE: Set<String> = setOf(
    HealthPermission.getWritePermission(HeartRateVariabilityRmssdRecord::class),
    HealthPermission.getReadPermission(OxygenSaturationRecord::class),
    HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    HealthPermission.getReadPermission(HeartRateRecord::class),
    HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
)
