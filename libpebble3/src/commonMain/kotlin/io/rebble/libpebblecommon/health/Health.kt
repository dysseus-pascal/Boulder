package io.rebble.libpebblecommon.health

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.HealthApi
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.connection.LatestHeartRate
import io.rebble.libpebblecommon.connection.LatestSpo2
import io.rebble.libpebblecommon.connection.Pulsreihe
import io.rebble.libpebblecommon.connection.WatchManager
import io.rebble.libpebblecommon.database.dao.HealthDao
import io.rebble.libpebblecommon.database.dao.NachtDao
import io.rebble.libpebblecommon.database.boulder.NachtUmzug
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtHrvEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import io.rebble.libpebblecommon.database.dao.Spo2Dao
import io.rebble.libpebblecommon.database.entity.HealthDataEntity
import io.rebble.libpebblecommon.database.entity.HRMonitoringInterval
import io.rebble.libpebblecommon.database.entity.HealthGender
import io.rebble.libpebblecommon.database.entity.HealthSettingsEntryDao
import io.rebble.libpebblecommon.database.entity.HealthStatDao
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import io.rebble.libpebblecommon.database.entity.Spo2ReadingEntity
import io.rebble.libpebblecommon.database.entity.getWatchSettings
import io.rebble.libpebblecommon.database.entity.setWatchSettings
import io.rebble.libpebblecommon.datalogging.HealthDataProcessor
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.services.DailySleep
import io.rebble.libpebblecommon.services.calculateHealthAverages
import io.rebble.libpebblecommon.services.computeAllWeekdayTypicalSleep
import io.rebble.libpebblecommon.services.computeAllWeekdayTypicalSteps
import io.rebble.libpebblecommon.services.decodeTypicalStepTotal
import io.rebble.libpebblecommon.services.groupSleepSessions
import io.rebble.libpebblecommon.services.fetchAndGroupDailySleep
import io.rebble.libpebblecommon.services.updateHealthStatsInDatabase
import io.rebble.libpebblecommon.util.datenbereich
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock.System

class Health(
    private val healthSettingsDao: HealthSettingsEntryDao,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
    private val healthDao: HealthDao,
    private val healthStatDao: HealthStatDao,
    private val spo2Dao: Spo2Dao,
    private val watchManager: WatchManager,
    private val healthDataProcessor: HealthDataProcessor,
    private val nachtDao: NachtDao,
    // Boulder: die Naechte ziehen beim Start nach boulder.db, nicht erst beim ersten Lesen.
    private val nachtUmzug: NachtUmzug,
) : HealthApi, HealthDataApi {
    private val logger = Logger.withTag("Health")

    // Boulder: Einstellungen und Tageszahlen laufen hier; ein Datenbankfehler beendete vorher die App.
    private val bereich = libPebbleCoroutineScope.datenbereich("Health")

    companion object {
        private val HEALTH_STATS_AVERAGE_DAYS = 30
        private val MORNING_WAKE_HOUR = 7 // 7 AM for daily stats update
        private const val REST_HR_MIN_READINGS = 10
        private const val REST_HR_LOWEST_MINUTES = 5
    }

    override val healthDataUpdated: SharedFlow<Unit> = healthDataProcessor.healthDataUpdated

    override val healthSettings: Flow<HealthSettings> = healthSettingsDao.getWatchSettings()

    fun init() {
        startPeriodicStatsUpdate()
        bereich.launch {
            try {
                nachtUmzug.sicherstellen()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // NachtUmzug hat es geloggt; der naechste Zugriff auf die Naechte versucht es erneut.
                logger.w { "Night move not done at start: ${e.message}" }
            }
        }
    }

    override fun updateHealthSettings(healthSettings: HealthSettings) {
        logger.d { "updateHealthSettings called: $healthSettings" }
        bereich.launch {
            healthSettingsDao.setWatchSettings(healthSettings)
            logger.d { "Health settings saved to database - will sync to watch via BlobDB" }
        }
    }

    override suspend fun getHealthDebugStats(): HealthDebugStats {
        // This function operates on the shared database, so it doesn't need a connection
        val timeZone = TimeZone.currentSystemDefault()
        val today = System.now().toLocalDateTime(timeZone).date
        val startDate = today.minus(DatePeriod(days = 30))

        val todayStart = today.atStartOfDayIn(timeZone).epochSeconds
        val todayEnd = today.plus(DatePeriod(days = 1)).atStartOfDayIn(timeZone).epochSeconds

        logger.d { "HEALTH_DEBUG: Getting health stats for today=$today, todayStart=$todayStart, todayEnd=$todayEnd" }

        val averages = calculateHealthAverages(healthDao, startDate, today, timeZone)
        val todaySteps = healthDao.getTotalStepsExclusiveEnd(todayStart, todayEnd) ?: 0L
        val latestTimestamp = healthDao.getLatestTimestamp()

        logger.d { "HEALTH_DEBUG: todaySteps=$todaySteps, latestTimestamp=$latestTimestamp, averageSteps=${averages.averageStepsPerDay}" }

        val daysOfData = maxOf(averages.stepDaysWithData, averages.sleepDaysWithData)

        val lastNightSleep = fetchAndGroupDailySleep(healthDao, todayStart, timeZone)
        val lastNightSleepSeconds = lastNightSleep?.totalSleep ?: 0L
        val lastNightSleepHours =
            if (lastNightSleepSeconds > 0) lastNightSleepSeconds / 3600f else null

        val weekdayTypicalSteps = computeAllWeekdayTypicalSteps(healthDao, today, timeZone)
            .mapValues { (_, payload) -> decodeTypicalStepTotal(payload) }
        val weekdayTypicalSleep = computeAllWeekdayTypicalSleep(healthDao, today, timeZone)

        return HealthDebugStats(
            totalSteps30Days = averages.totalSteps,
            averageStepsPerDay = averages.averageStepsPerDay,
            totalSleepSeconds30Days = averages.totalSleepSeconds,
            averageSleepSecondsPerDay = averages.averageSleepSecondsPerDay,
            todaySteps = todaySteps,
            lastNightSleepHours = lastNightSleepHours,
            latestDataTimestamp = latestTimestamp,
            daysOfData = daysOfData,
            weekdayTypicalSteps = weekdayTypicalSteps,
            weekdayTypicalSleep = weekdayTypicalSleep,
        )
    }

    override fun requestHealthData(fullSync: Boolean) {
        libPebbleCoroutineScope.launch {
            val device = watchManager.watches.value.filterIsInstance<ConnectedPebbleDevice>().firstOrNull()
            // Boulder: der Bereich hat keinen Fehlerfaenger; bricht die Verbindung
            // mitten in der Anfrage ab, fiele sonst die ganze App um.
            try {
                device?.requestHealthData(fullSync)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(e) { "requestHealthData failed" }
            }
        }
    }

    override fun sendHealthAveragesToWatch() {
        bereich.launch {
            updateHealthStats()
        }
    }

    private fun startPeriodicStatsUpdate() {
        bereich.launch {
            // Update health stats once daily at 7 AM
            while (true) {
                val timeZone = TimeZone.currentSystemDefault()
                val now = System.now().toLocalDateTime(timeZone)

                // Calculate next morning update time (7 AM tomorrow)
                val tomorrow = now.date.plus(DatePeriod(days = 1))
                val nextMorning =
                    LocalDateTime(
                        tomorrow.year,
                        tomorrow.month,
                        tomorrow.dayOfMonth,
                        MORNING_WAKE_HOUR,
                        0,
                        0
                    )
                val morningInstant = nextMorning.toInstant(timeZone)
                val delayUntilMorning = (morningInstant.toEpochMilliseconds() - System.now().toEpochMilliseconds()).coerceAtLeast(0L)

                logger.d { "HEALTH_STATS: Next scheduled update at $nextMorning (${delayUntilMorning / (60 * 60 * 1000)}h from now)" }
                delay(delayUntilMorning)

                logger.d { "HEALTH_STATS: Running scheduled daily stats update" }
                // Boulder: ein Fehler laesst nur diesen Morgen ausfallen, nicht alle folgenden.
                try {
                    updateHealthStats()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.e(e) { "HEALTH_STATS: scheduled update failed" }
                }
            }
        }
    }

    private suspend fun updateHealthStats() {
        val latestTimestamp = healthDao.getLatestTimestamp()
        if (latestTimestamp == null || latestTimestamp <= 0) {
            logger.d { "Skipping health stats update; no health data available" }
            return
        }

        val timeZone = TimeZone.currentSystemDefault()
        val today = kotlin.time.Clock.System.now().toLocalDateTime(timeZone).date
        val startDate = today.minus(DatePeriod(days = HEALTH_STATS_AVERAGE_DAYS))

        val updated = updateHealthStatsInDatabase(healthDao, healthStatDao, today, startDate, timeZone)
        if (!updated) {
            logger.d { "Health stats update attempt finished without any writes" }
        } else {
            logger.d { "Health stats updated (latestTimestamp=$latestTimestamp)" }
        }
    }

    override suspend fun getLatestTimestamp(): Long? = healthDao.getLatestTimestamp()

    override suspend fun getHealthDataAfter(afterTimestamp: Long): List<HealthDataEntity> =
        healthDao.getHealthDataAfter(afterTimestamp)

    override suspend fun getOverlayEntriesAfter(
        afterTimestamp: Long,
        types: List<Int>
    ): List<OverlayDataEntity> = healthDao.getOverlayEntriesAfter(afterTimestamp, types)

    override suspend fun getHealthDataForRange(start: Long, end: Long): List<HealthDataEntity> =
        healthDao.getHealthDataForRange(start, end)

    // Boulder: zwei Spalten statt zwoelf, gleich in Reihen - siehe Pulsreihe.
    override suspend fun getPulsMinuten(start: Long, end: Long): Pulsreihe {
        val zeilen = healthDao.getPulsMinuten(start, end)
        return Pulsreihe(LongArray(zeilen.size) { zeilen[it].timestamp }, IntArray(zeilen.size) { zeilen[it].heartRate })
    }

    override suspend fun getSchritteJeHalbstunde(start: Long, end: Long): List<Pair<Long, Long>> =
        healthDao.getSchritteJeHalbstunde(start, end).map { it.beginn to it.schritte }

    override suspend fun getDailyAggregates(start: Long, end: Long) =
        healthDao.getDailyMovementAggregates(start, end)

    override suspend fun getTotalHealthData(start: Long, end: Long) =
        healthDao.getAggregatedHealthData(start, end)

    override suspend fun getAverageHeartRate(start: Long, end: Long) =
        healthDao.getAverageHeartRate(start, end)

    override suspend fun getSleepEntries(start: Long, end: Long) =
        healthDao.getOverlayEntries(start, end, HealthConstants.SLEEP_TYPES)

    override suspend fun getDailySleepSession(dayStartEpochSec: Long): DailySleep? =
        fetchAndGroupDailySleep(healthDao, dayStartEpochSec, TimeZone.currentSystemDefault())

    override suspend fun getLatestHeartRateReading(): LatestHeartRate? {
        val entry = healthDao.getLatestHeartRateReading() ?: return null
        return LatestHeartRate(bpm = entry.heartRate, timestampEpochSec = entry.timestamp)
    }

    override suspend fun getRestingHeartRate(dayStartEpochSec: Long): Int? {
        val sleep = getDailySleepSession(dayStartEpochSec) ?: return null
        // Light-sleep containers (Sleep + Nap) cover the full sleep window; deep intervals are
        // nested inside them, so we'd double-count if we summed all intervals. Use containers only.
        val lightIntervals = sleep.intervals.filterNot { it.isDeep }
        val readings = mutableListOf<Int>()
        for (interval in lightIntervals) {
            healthDao.getHealthDataForRange(interval.start, interval.end).forEach { row ->
                if (row.heartRate > 0) readings.add(row.heartRate)
            }
        }
        if (readings.size < REST_HR_MIN_READINGS) return null
        val lowest = readings.sorted().take(REST_HR_LOWEST_MINUTES)
        return (lowest.sum().toDouble() / lowest.size).roundToInt()
    }

    override suspend fun getHRZoneMinutes(start: Long, end: Long): Map<Int, Long> =
        healthDao.getHeartRateZoneMinutes(start, end).associate { it.heartRateZone to it.minutes }

    override suspend fun getSpo2Readings(start: Long, end: Long): List<Spo2ReadingEntity> =
        spo2Dao.getSpo2Readings(start, end)

    override suspend fun getSpo2ReadingsAfter(afterTimestamp: Long): List<Spo2ReadingEntity> =
        spo2Dao.getSpo2ReadingsAfter(afterTimestamp)

    override suspend fun getNaechte(von: Long, bis: Long): List<NachtEntity> = nachtDao.naechte(von, bis)

    override suspend fun getNachtAbschnitte(beginnUtc: Long): List<NachtAbschnittEntity> = nachtDao.abschnitte(beginnUtc)

    override suspend fun getNachtAbschnitte(von: Long, bis: Long): List<NachtAbschnittEntity> = nachtDao.abschnitteZwischen(von, bis)

    override suspend fun getNachtHrv(beginnUtc: Long): List<NachtHrvEntity> = nachtDao.hrv(beginnUtc)

    override suspend fun setzeNachtHrvWennLeer(beginnUtc: Long, hrv: Int): Boolean = nachtDao.setzeHrvWennLeer(beginnUtc, hrv) > 0

    override suspend fun setzeZiele(schritte: Int, schlafMin: Int) {
        healthStatDao.insertOrReplace(BoulderZiele.zeile(schritte, schlafMin))
    }

    override suspend fun getAverageSpo2(start: Long, end: Long): Double? =
        spo2Dao.getAverageSpo2(start, end)

    override suspend fun getLatestSpo2Reading(): LatestSpo2? {
        val entry = spo2Dao.getLatestSpo2Reading() ?: return null
        return LatestSpo2(
            spo2Percent = entry.spo2Percent,
            quality = entry.quality,
            timestampEpochSec = entry.timestamp,
        )
    }

    override suspend fun getActivitySessions(start: Long, end: Long): List<OverlayDataEntity> =
        healthDao.getOverlayEntries(start, end, listOf(
            OverlayType.Walk.value, OverlayType.Run.value, OverlayType.OpenWorkout.value
        ))

    override suspend fun getTypicalSteps(dayOfWeek: Int): List<Long> {
        val timeZone = TimeZone.currentSystemDefault()
        val today = System.now().toLocalDateTime(timeZone).date
        // Find the most recent occurrence of this weekday (excluding today)
        var refDate = today.minus(DatePeriod(days = 1))
        while (refDate.dayOfWeek.ordinal != dayOfWeek) {
            refDate = refDate.minus(DatePeriod(days = 1))
        }
        val oldest = refDate.minus(DatePeriod(days = 7 * 7))
        val rangeStart = oldest.atStartOfDayIn(timeZone).epochSeconds
        val rangeEnd = refDate.plus(DatePeriod(days = 1)).atStartOfDayIn(timeZone).epochSeconds

        val allData = healthDao.getHealthDataForRange(rangeStart, rangeEnd)
        if (allData.isEmpty()) return emptyList()

        val hourlyTotals = LongArray(24)
        val matchingDays = mutableSetOf<Long>()
        for (entry in allData) {
            val entryDate = kotlinx.datetime.Instant.fromEpochSeconds(entry.timestamp)
                .toLocalDateTime(timeZone).date
            if (entryDate.dayOfWeek.ordinal != dayOfWeek) continue
            val dayStart = entryDate.atStartOfDayIn(timeZone).epochSeconds
            matchingDays.add(dayStart)
            val hour = ((entry.timestamp - dayStart) / 3600).toInt().coerceIn(0, 23)
            hourlyTotals[hour] += entry.steps
        }
        if (matchingDays.isEmpty()) return emptyList()
        return hourlyTotals.map { it / matchingDays.size }
    }

    override suspend fun getTypicalSleepSeconds(): Long {
        val timeZone = TimeZone.currentSystemDefault()
        val today = System.now().toLocalDateTime(timeZone).date
        val rangeStart = today.minus(DatePeriod(days = 30)).atStartOfDayIn(timeZone).epochSeconds
        val rangeEnd = today.atStartOfDayIn(timeZone).epochSeconds
        val allEntries = healthDao.getOverlayEntries(rangeStart, rangeEnd, HealthConstants.SLEEP_TYPES)
        if (allEntries.isEmpty()) return 0L

        val sessions = groupSleepSessions(allEntries)
        val validSessions = sessions.filter { it.totalSleep > 1800 }
        return if (validSessions.isNotEmpty()) validSessions.sumOf { it.totalSleep } / validSessions.size else 0L
    }
}

data class HealthSettings(
    val heightMm: Short,
    val weightDag: Short,
    val trackingEnabled: Boolean,
    val activityInsightsEnabled: Boolean,
    val sleepInsightsEnabled: Boolean,
    val ageYears: Int,
    val gender: HealthGender,
    val imperialUnits: Boolean,
    // ActivityHRMSettings
    val hrmEnabled: Boolean,
    val hrmMeasurementInterval: HRMonitoringInterval,
    val hrmActivityTrackingEnabled: Boolean,
    // HeartRatePreferences
    val restingHr: Short,
    val elevatedHr: Short,
    val maxHr: Short,
    val hrZone1Threshold: Short,
    val hrZone2Threshold: Short,
    val hrZone3Threshold: Short,
    // BloodOxygenPreferences
    val bloodOxygenEnabled: Boolean,
    // BloodOxygenActivityPreferences (SpO2 during activities; gated by HR During Activities)
    val bloodOxygenActivityEnabled: Boolean,
    // Spo2Preferences — SpO2 monitoring rate; sampling runs only when bloodOxygenEnabled && rate != Disabled
    val spo2MeasurementInterval: HRMonitoringInterval,
)

/** Time range for displaying health data */
enum class HealthTimeRange {
    Daily,
    Weekly,
    Monthly
}

/** Data structure for stacked sleep charts (weekly/monthly views). */
data class StackedSleepData(
    val label: String,
    val lightSleepHours: Float,
    val deepSleepHours: Float
)

/** Data structure for weekly aggregated data (for monthly charts broken into weeks). */
data class WeeklyAggregatedData(
    val label: String, // e.g., "Mar 27 - Apr 4"
    val value: Float?, // null when there's no data for this week
    val weekIndex: Int // Position in the overall sequence
)

/** Represents a segment of sleep in the daily view. */
data class SleepSegment(
    val startHour: Float, // Hour of day (0-24)
    val durationHours: Float,
    val type: OverlayType // Sleep or DeepSleep
)

/** Daily sleep data with all segments and timing information. */
data class DailySleepData(
    val segments: List<SleepSegment>,
    val bedtime: Float, // Start hour
    val wakeTime: Float, // End hour
    val totalSleepHours: Float
)
