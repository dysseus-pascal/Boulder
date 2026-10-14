package io.rebble.libpebblecommon.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "health_data")
data class HealthDataEntity(
    @PrimaryKey
    val timestamp: Long,
    val steps: Int,
    val orientation: Int,
    val intensity: Int,
    val lightIntensity: Int,
    val activeMinutes: Int,
    val restingGramCalories: Int,
    val activeGramCalories: Int,
    val distanceCm: Int,
    val heartRate: Int = 0,
    val heartRateZone: Int = 0,
    val heartRateWeight: Int = 0
)

@Entity(
    tableName = "overlay_data",
    primaryKeys = ["startTime", "type"]
)
data class OverlayDataEntity(
    val startTime: Long,
    val duration: Long,
    val type: Int,
    val steps: Int,
    val restingKiloCalories: Int,
    val activeKiloCalories: Int,
    // Boulder: die Firmware schickt Meter (ActivitySessionDataStepping.distance_meters);
    // die Spalte behaelt ihren alten Namen, damit kein Schema wechselt.
    @ColumnInfo(name = "distanceCm")
    val distanceM: Int,
    val offsetUTC: Int,
    /** Boulder: selbst gestartet (Training) statt von der Uhr erkannt. Nur die Boulder-Firmware liefert es. */
    @ColumnInfo(defaultValue = "0")
    val manual: Boolean = false,
)
