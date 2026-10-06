package io.rebble.libpebblecommon.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.rebble.libpebblecommon.database.entity.HealthDataEntity
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HealthDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHealthData(data: List<HealthDataEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOverlayData(data: List<OverlayDataEntity>)

    @Query("SELECT * FROM health_data WHERE timestamp >= :start AND timestamp <= :end ORDER BY timestamp ASC")
    fun getHealthData(start: Long, end: Long): Flow<List<HealthDataEntity>>

    @Query("SELECT * FROM overlay_data WHERE startTime >= :start AND startTime <= :end ORDER BY startTime ASC")
    fun getOverlayData(start: Long, end: Long): Flow<List<OverlayDataEntity>>

    @Query("SELECT SUM(steps) FROM health_data WHERE timestamp >= :start AND timestamp <= :end")
    suspend fun getTotalSteps(start: Long, end: Long): Int?

    @Query("""
        SELECT 
            SUM(steps) AS steps,
            SUM(activeGramCalories) AS activeGramCalories,
            SUM(restingGramCalories) AS restingGramCalories,
            SUM(activeMinutes) AS activeMinutes,
            SUM(distanceCm) AS distanceCm
        FROM health_data
        WHERE timestamp >= :start AND timestamp < :end
        """)
    suspend fun getAggregatedHealthData(start: Long, end: Long): HealthAggregates?

    @Query("SELECT SUM(steps) FROM health_data WHERE timestamp >= :start AND timestamp < :end")
    suspend fun getTotalStepsExclusiveEnd(start: Long, end: Long): Long?

    @Query("SELECT AVG(steps) FROM health_data WHERE timestamp >= :start AND timestamp <= :end")
    suspend fun getAverageSteps(start: Long, end: Long): Double?

    @Query("SELECT AVG(heartRate) FROM health_data WHERE timestamp >= :start AND timestamp < :end AND heartRate > 0")
    suspend fun getAverageHeartRate(start: Long, end: Long): Double?

    @Query("SELECT COUNT(*) FROM health_data WHERE timestamp >= :start AND timestamp <= :end")
    suspend fun hasDataForRange(start: Long, end: Long): Int

    @Query("SELECT COUNT(*) FROM health_data") suspend fun hasAnyHealthData(): Int

    @Query("SELECT MAX(timestamp) FROM health_data") suspend fun getLatestTimestamp(): Long?

    @Query("SELECT * FROM health_data WHERE timestamp = :timestamp")
    suspend fun getDataAtTimestamp(timestamp: Long): HealthDataEntity?

    @Query("SELECT * FROM health_data WHERE heartRate > 0 ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestHeartRateReading(): HealthDataEntity?

    @Query("""
        SELECT heartRateZone, COUNT(*) as minutes
        FROM health_data
        WHERE timestamp >= :start AND timestamp < :end AND heartRate > 0
        GROUP BY heartRateZone
        """)
    suspend fun getHeartRateZoneMinutes(start: Long, end: Long): List<HRZoneCount>

    @Query("SELECT SUM(duration) FROM overlay_data WHERE startTime >= :start AND startTime < :end AND type = :type")
    suspend fun getOverlayDuration(start: Long, end: Long, type: Int): Long?

    @Query("""
        SELECT * FROM overlay_data
        WHERE startTime >= :start AND startTime < :end AND type IN (:types)
        """)
    suspend fun getOverlayEntries(
            start: Long,
            end: Long,
            types: List<Int>
    ): List<OverlayDataEntity>

    @Query("SELECT * FROM overlay_data ORDER BY startTime ASC")
    suspend fun getAllOverlayEntries(): List<OverlayDataEntity>

    @Query("SELECT * FROM overlay_data WHERE startTime = :startTime AND type = :type")
    suspend fun getOverlayAtStartTimeAndType(startTime: Long, type: Int): OverlayDataEntity?

    @Query("SELECT * FROM health_data WHERE timestamp >= :start AND timestamp < :end ORDER BY timestamp ASC")
    suspend fun getHealthDataForRange(start: Long, end: Long): List<HealthDataEntity>

    /**
     * Boulder: nur Zeit und Puls der Minuten mit Puls. Kurven und Tagesherz
     * brauchen 2 der 12 Spalten; ueber 14 Tage sind das bis zu 20'160 Zeilen,
     * die SELECT * sonst ganz als HealthDataEntity baute.
     */
    @Query("SELECT timestamp, heartRate FROM health_data WHERE timestamp >= :start AND timestamp < :end AND heartRate > 0 ORDER BY timestamp ASC")
    suspend fun getPulsMinuten(start: Long, end: Long): List<PulsMinute>

    /**
     * Boulder: die Schritte je halbe Stunde fuer das Tagesband der
     * Gesundheitsseite - gezaehlt in der Datenbank, statt bis zu 1'600
     * Minutenzeilen ganz zu holen. Die Fenster liegen auf vollen halben
     * Stunden der Weltzeit; das sind volle halbe Stunden jeder Ortszeit mit
     * einem Versatz in halben Stunden (in Nepal laegen sie eine Viertelstunde daneben).
     */
    @Query("""
        SELECT (timestamp / 1800) * 1800 AS beginn, SUM(steps) AS schritte
        FROM health_data
        WHERE timestamp >= :start AND timestamp < :end AND steps > 0
        GROUP BY timestamp / 1800
        ORDER BY beginn ASC
        """)
    suspend fun getSchritteJeHalbstunde(start: Long, end: Long): List<Schritthalbstunde>

    @Query("SELECT * FROM health_data WHERE timestamp > :afterTimestamp ORDER BY timestamp ASC")
    suspend fun getHealthDataAfter(afterTimestamp: Long): List<HealthDataEntity>

    @Query("SELECT * FROM overlay_data WHERE startTime > :afterTimestamp AND type IN (:types) ORDER BY startTime ASC")
    suspend fun getOverlayEntriesAfter(afterTimestamp: Long, types: List<Int>): List<OverlayDataEntity>

    @Query("""
        SELECT SUM(duration) / 60 FROM overlay_data
        WHERE startTime >= :start AND startTime < :end AND type = 1
        """)
    suspend fun getTotalSleepMinutes(start: Long, end: Long): Long?

    @Query("""
        SELECT SUM(duration) / 60 FROM overlay_data
        WHERE startTime >= :start AND startTime < :end AND type = 2
        """)
    suspend fun getDeepSleepMinutes(start: Long, end: Long): Long?

    @Query("""
        SELECT COUNT(DISTINCT DATE(startTime, 'unixepoch', 'localtime')) FROM overlay_data
        WHERE startTime >= :start AND startTime < :end AND type IN (1, 2) AND duration > 0
        """)
    suspend fun getDaysWithSleepData(start: Long, end: Long): Int

    @Query("""
        SELECT COUNT(DISTINCT DATE(timestamp, 'unixepoch', 'localtime')) FROM health_data
        WHERE timestamp >= :start AND timestamp < :end AND steps > 0
        """)
    suspend fun getDaysWithStepsData(start: Long, end: Long): Int

    @Query("""
        SELECT
            date(timestamp, 'unixepoch', 'localtime') as day,
            SUM(steps) AS steps,
            SUM(activeGramCalories) AS activeGramCalories,
            SUM(restingGramCalories) AS restingGramCalories,
            SUM(activeMinutes) AS activeMinutes,
            SUM(distanceCm) AS distanceCm
        FROM health_data
        WHERE timestamp >= :start AND timestamp < :end
        GROUP BY day
        """)
    suspend fun getDailyMovementAggregates(start: Long, end: Long): List<DailyMovementAggregate>

    @Query("DELETE FROM health_data WHERE timestamp < :expirationTimestamp")
    suspend fun deleteExpiredHealthData(expirationTimestamp: Long): Int

    @Query("DELETE FROM overlay_data WHERE startTime < :expirationTimestamp")
    suspend fun deleteExpiredOverlayData(expirationTimestamp: Long): Int
}

data class HealthAggregates(
    val steps: Long?,
    val activeGramCalories: Long?,
    val restingGramCalories: Long?,
    val activeMinutes: Long?,
    val distanceCm: Long?,
)

data class DailyMovementAggregate(
    val day: String, // YYYY-MM-DD
    val steps: Long?,
    val activeGramCalories: Long?,
    val restingGramCalories: Long?,
    val activeMinutes: Long?,
    val distanceCm: Long?
)

data class HRZoneCount(
    val heartRateZone: Int,
    val minutes: Long,
)

/** Boulder: eine Minute mit Puls, siehe [HealthDao.getPulsMinuten]. */
data class PulsMinute(
    val timestamp: Long,
    val heartRate: Int,
)

/** Boulder: die Schritte einer halben Stunde ab [beginn] (Sekunde), siehe [HealthDao.getSchritteJeHalbstunde]. */
data class Schritthalbstunde(
    val beginn: Long,
    val schritte: Long,
)
