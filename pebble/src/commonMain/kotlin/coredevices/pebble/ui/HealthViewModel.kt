package coredevices.pebble.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.dao.DailyMovementAggregate
import io.rebble.libpebblecommon.health.HealthConstants
import io.rebble.libpebblecommon.health.HealthTimeRange
import io.rebble.libpebblecommon.health.OverlayType
import io.rebble.libpebblecommon.services.SleepSession
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Instant

data class ActivityUiState(
    val totalSteps: Long = 0,
    val averageSteps: Long = 0,
    val totalCaloriesKcal: Long = 0,
    val totalDistanceM: Long = 0,
    val totalActiveMinutes: Long = 0,
    val barValues: List<Long> = emptyList(),
    val barLabels: List<String> = emptyList(),
    val typicalSteps: List<Long> = emptyList(),
    val typicalTotal: Long = 0,
    val activitySessions: List<ActivitySessionUi> = emptyList(),
    val isLoading: Boolean = true,
)

data class ActivitySessionUi(
    val startIndex: Int,
    val endIndex: Int,
    val type: OverlayType,
    val label: String,
)

data class StackedSleepEntry(
    val label: String,
    val lightHours: Float,
    val deepHours: Float,
)

data class SleepUiState(
    val segments: List<SleepSegmentUi> = emptyList(),
    val stackedData: List<StackedSleepEntry> = emptyList(),
    val totalSleepHours: Float = 0f,
    val deepSleepHours: Float = 0f,
    val avgDeepSleepMins: Long = 0,
    val avgFallAsleep: String = "",
    val avgWakeUp: String = "",
    val typicalSleepHours: Float = 0f,
    val isLoading: Boolean = true,
)

data class SleepSegmentUi(
    val startFraction: Float,
    val widthFraction: Float,
    val isDeep: Boolean,
)

data class HeartRateUiState(
    val averageHR: Int? = null,
    val latestHR: Int? = null,
    val hourlyHR: List<Double?> = emptyList(),
    val zoneMinutes: Map<Int, Long> = emptyMap(),
    val isLoading: Boolean = true,
)

class HealthViewModel(
    private val libPebble: LibPebble,
) : ViewModel() {
    var selectedTimeRange by mutableStateOf(HealthTimeRange.Daily)
    var dateOffset by mutableStateOf(0)

    val imperialUnits = libPebble.healthSettings
        .map { it.imperialUnits }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _activity = MutableStateFlow(ActivityUiState())
    val activity: StateFlow<ActivityUiState> = _activity.asStateFlow()
    private val _sleep = MutableStateFlow(SleepUiState())
    val sleep: StateFlow<SleepUiState> = _sleep.asStateFlow()
    private val _heartRate = MutableStateFlow(HeartRateUiState())
    val heartRate: StateFlow<HeartRateUiState> = _heartRate.asStateFlow()
    private val _dateLabel = MutableStateFlow("")
    val dateLabel: StateFlow<String> = _dateLabel.asStateFlow()

    init {
        viewModelScope.launch {
            merge(
                snapshotFlow { selectedTimeRange to dateOffset },
                libPebble.healthDataUpdated.map { selectedTimeRange to dateOffset },
            ).collectLatest { (range, offset) ->
                loadData(range, offset)
            }
        }
    }

    fun onTimeRangeChanged(range: HealthTimeRange) {
        selectedTimeRange = range
        dateOffset = 0
    }

    fun navigateBack() { dateOffset-- }
    fun navigateForward() { if (dateOffset < 0) dateOffset++ }

    private suspend fun loadData(range: HealthTimeRange, offset: Int) {
        val tz = TimeZone.currentSystemDefault()
        val today = Clock.System.now().toLocalDateTime(tz).date
        when (range) {
            HealthTimeRange.Daily -> {
                val target = today.plus(DatePeriod(days = offset))
                _dateLabel.value = formatDayLabel(target, today)
                val ds = target.atStartOfDayIn(tz).epochSeconds
                val de = target.plus(DatePeriod(days = 1)).atStartOfDayIn(tz).epochSeconds
                loadDaily(ds, de, target, tz)
            }
            HealthTimeRange.Weekly -> {
                val end = today.plus(DatePeriod(days = offset * 7))
                val start = end.minus(DatePeriod(days = 6))
                _dateLabel.value = "${start.dayOfWeek.shortName()} ${start.dayOfMonth} ${start.month.shortName()} - ${end.dayOfWeek.shortName()} ${end.dayOfMonth} ${end.month.shortName()}"
                loadWeekly(start, end, tz)
            }
            HealthTimeRange.Monthly -> {
                val target = today.plus(DatePeriod(months = offset))
                val ms = LocalDate(target.year, target.month, 1)
                val me = if (offset == 0) today else ms.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))
                _dateLabel.value = "${ms.month.fullName()} ${ms.year}"
                loadMonthly(ms, me, tz)
            }
        }
    }

    private suspend fun loadDaily(dayStart: Long, dayEnd: Long, targetDate: LocalDate, tz: TimeZone) = coroutineScope {
        val healthDataD = async { libPebble.getHealthDataForRange(dayStart, dayEnd) }
        val aggregatesD = async { libPebble.getTotalHealthData(dayStart, dayEnd) }
        val sleepSessionD = async { libPebble.getDailySleepSession(dayStart) }
        val typicalStepsD = async { libPebble.getTypicalSteps(targetDate.dayOfWeek.ordinal) }
        val sessionsD = async { libPebble.getActivitySessions(dayStart, dayEnd) }
        val typicalSleepD = async { libPebble.getTypicalSleepSeconds() }
        val avgHRD = async { libPebble.getAverageHeartRate(dayStart, dayEnd) }
        val zonesD = async { libPebble.getHRZoneMinutes(dayStart, dayEnd) }
        val latestHRD = async { libPebble.getLatestHeartRateReading() }

        val healthData = healthDataD.await()
        val aggregates = aggregatesD.await()
        val sleepSession = sleepSessionD.await()
        val typicalSteps = typicalStepsD.await()
        val sessions = sessionsD.await()

        val hourlySteps = LongArray(24)
        val hourlyHR = Array<MutableList<Int>>(24) { mutableListOf() }
        for (entry in healthData) {
            val hour = ((entry.timestamp - dayStart) / 3600).toInt().coerceIn(0, 23)
            hourlySteps[hour] += entry.steps
            if (entry.heartRate > 0) hourlyHR[hour].add(entry.heartRate)
        }

        val sessionUis = sessions.map { ov ->
            val sH = ((ov.startTime - dayStart).toFloat() / 3600).toInt().coerceIn(0, 23)
            val eH = ((ov.startTime + ov.duration - dayStart).toFloat() / 3600).toInt().coerceIn(0, 23)
            val type = OverlayType.fromValue(ov.type) ?: OverlayType.Walk
            val durMin = ov.duration / 60
            val label = "${type.name} · ${durMin}min"
            ActivitySessionUi(sH, eH, type, label)
        }

        _activity.value = ActivityUiState(
            totalSteps = aggregates?.steps ?: 0,
            totalCaloriesKcal = (aggregates?.activeGramCalories ?: 0) / 1000,
            totalDistanceM = (aggregates?.distanceCm ?: 0) / 100,
            totalActiveMinutes = aggregates?.activeMinutes ?: 0,
            barValues = hourlySteps.toList(),
            barLabels = (0..23).map { "$it" },
            typicalSteps = typicalSteps,
            typicalTotal = if (typicalSteps.isNotEmpty()) typicalSteps.sum() else 0,
            activitySessions = sessionUis,
            isLoading = false,
        )

        val segments = buildDailySleepSegments(dayStart, sleepSession)
        val bedtimeStr = sleepSession?.let { formatTimeOfDay(it.start, tz) } ?: ""
        val wakeStr = sleepSession?.let { formatTimeOfDay(it.end, tz) } ?: ""

        _sleep.value = SleepUiState(
            segments = segments,
            totalSleepHours = (sleepSession?.totalSleep ?: 0L) / 3600f,
            deepSleepHours = (sleepSession?.deepSleep ?: 0L) / 3600f,
            avgFallAsleep = bedtimeStr,
            avgWakeUp = wakeStr,
            avgDeepSleepMins = (sleepSession?.deepSleep ?: 0L) / 60,
            typicalSleepHours = typicalSleepD.await() / 3600f,
            isLoading = false,
        )

        _heartRate.value = HeartRateUiState(
            averageHR = avgHRD.await()?.roundToInt(),
            latestHR = latestHRD.await()?.bpm,
            hourlyHR = hourlyHR.map { if (it.isEmpty()) null else it.average() },
            zoneMinutes = zonesD.await(),
            isLoading = false,
        )
    }

    private suspend fun loadWeekly(startDate: LocalDate, endDate: LocalDate, tz: TimeZone) {
        val startEpoch = startDate.atStartOfDayIn(tz).epochSeconds
        val endEpoch = endDate.plus(DatePeriod(days = 1)).atStartOfDayIn(tz).epochSeconds
        val dailyAggs = libPebble.getDailyAggregates(startEpoch, endEpoch)
        val aggsByDay = dailyAggs.associateBy { it.day }

        val labels = mutableListOf<String>()
        val ordered = mutableListOf<DailyMovementAggregate?>()
        for (i in 0..6) {
            val d = startDate.plus(DatePeriod(days = i))
            labels.add("${d.dayOfWeek.shortName()} ${d.dayOfMonth}")
            ordered.add(aggsByDay[d.toString()])
        }
        loadAggregated(ordered, startEpoch, endEpoch, tz, labels)
    }

    private suspend fun loadMonthly(monthStart: LocalDate, monthEnd: LocalDate, tz: TimeZone) {
        val startEpoch = monthStart.atStartOfDayIn(tz).epochSeconds
        val endEpoch = monthEnd.plus(DatePeriod(days = 1)).atStartOfDayIn(tz).epochSeconds
        val dailyAggs = libPebble.getDailyAggregates(startEpoch, endEpoch)
        val aggsByDay = dailyAggs.associateBy { it.day }

        // Split month into exactly 4 groups
        val lastDay = monthStart.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))
        val totalDays = (lastDay.toEpochDays() - monthStart.toEpochDays() + 1).toInt()
        val baseDays = totalDays / 4
        val extraDays = totalDays % 4
        val weeks = mutableListOf<Pair<String, List<DailyMovementAggregate?>>>()
        var cursor = monthStart
        for (w in 0 until 4) {
            val groupSize = baseDays + if (w < extraDays) 1 else 0
            val groupEnd = cursor.plus(DatePeriod(days = groupSize - 1))
            val label = "${cursor.month.shortName()} ${cursor.dayOfMonth} - ${groupEnd.dayOfMonth}"
            val weekDays = mutableListOf<DailyMovementAggregate?>()
            var d = cursor
            while (d <= groupEnd) {
                weekDays.add(aggsByDay[d.toString()])
                d = d.plus(DatePeriod(days = 1))
            }
            weeks.add(label to weekDays)
            cursor = groupEnd.plus(DatePeriod(days = 1))
        }

        val labels = weeks.map { it.first }
        val weeklySteps = weeks.map { (_, days) ->
            val steps = days.filterNotNull().sumOf { it.steps ?: 0L }
            steps
        }

        // For sleep we still need per-day data
        loadAggregatedMonthly(weeklySteps, labels, startEpoch, endEpoch, tz, weeks)
    }

    private suspend fun buildActivityState(
        barValues: List<Long>, barLabels: List<String>, daysWithData: Int, start: Long, end: Long,
    ): ActivityUiState {
        val agg = libPebble.getTotalHealthData(start, end)
        return ActivityUiState(
            totalSteps = agg?.steps ?: 0,
            averageSteps = (agg?.steps ?: 0) / daysWithData,
            totalCaloriesKcal = (agg?.activeGramCalories ?: 0) / 1000 / daysWithData,
            totalDistanceM = (agg?.distanceCm ?: 0) / 100 / daysWithData,
            totalActiveMinutes = (agg?.activeMinutes ?: 0) / daysWithData,
            barValues = barValues, barLabels = barLabels,
            isLoading = false,
        )
    }

    private suspend fun buildHeartRateState(start: Long, end: Long): HeartRateUiState {
        return HeartRateUiState(
            averageHR = libPebble.getAverageHeartRate(start, end)?.roundToInt(),
            zoneMinutes = libPebble.getHRZoneMinutes(start, end),
            isLoading = false,
        )
    }

    private suspend fun buildSleepState(
        stackedData: List<StackedSleepEntry>, daysWithData: Int,
        sleepEntries: List<io.rebble.libpebblecommon.database.entity.OverlayDataEntity>, tz: TimeZone,
    ): SleepUiState {
        val totalSleep = sleepEntries.filter { OverlayType.fromValue(it.type) == OverlayType.Sleep }.sumOf { it.duration }
        val totalDeep = sleepEntries.filter { OverlayType.fromValue(it.type) == OverlayType.DeepSleep }.sumOf { it.duration }

        val sleepOverlays = sleepEntries.filter { OverlayType.fromValue(it.type) == OverlayType.Sleep && it.duration > 1800 }
        val avgFallAsleep = if (sleepOverlays.isNotEmpty()) {
            val secs = sleepOverlays.map { Instant.fromEpochSeconds(it.startTime).toLocalDateTime(tz).let { t -> t.hour * 3600L + t.minute * 60 } }
            formatTimeFromSeconds(averageTimeOfDay(secs))
        } else ""
        val avgWakeUp = if (sleepOverlays.isNotEmpty()) {
            val secs = sleepOverlays.map { Instant.fromEpochSeconds(it.startTime + it.duration).toLocalDateTime(tz).let { t -> t.hour * 3600L + t.minute * 60 } }
            formatTimeFromSeconds(averageTimeOfDay(secs))
        } else ""

        return SleepUiState(
            stackedData = stackedData,
            totalSleepHours = totalSleep / 3600f / daysWithData,
            deepSleepHours = totalDeep / 3600f / daysWithData,
            avgDeepSleepMins = totalDeep / 60 / daysWithData,
            avgFallAsleep = avgFallAsleep,
            avgWakeUp = avgWakeUp,
            typicalSleepHours = libPebble.getTypicalSleepSeconds() / 3600f,
            isLoading = false,
        )
    }

    private suspend fun loadAggregated(
        ordered: List<DailyMovementAggregate?>, start: Long, end: Long, tz: TimeZone,
        labels: List<String>,
    ) {
        val daysWithData = ordered.count { it != null }.coerceAtLeast(1)
        _activity.value = buildActivityState(ordered.map { it?.steps ?: 0L }, labels, daysWithData, start, end)

        val sleepEntries = libPebble.getSleepEntries(start, end)
        val entriesByDay = sleepEntries.groupBy { Instant.fromEpochSeconds(it.startTime).toLocalDateTime(tz).date.toString() }
        val stackedSleep = ordered.mapIndexed { i, agg ->
            val de = if (agg != null) entriesByDay[agg.day] ?: emptyList() else emptyList()
            StackedSleepEntry(
                label = labels.getOrElse(i) { "$i" },
                lightHours = de.filter { OverlayType.fromValue(it.type) == OverlayType.Sleep }.sumOf { it.duration } / 3600f,
                deepHours = de.filter { OverlayType.fromValue(it.type) == OverlayType.DeepSleep }.sumOf { it.duration } / 3600f,
            )
        }
        _sleep.value = buildSleepState(stackedSleep, daysWithData, sleepEntries, tz)
        _heartRate.value = buildHeartRateState(start, end)
    }

    private suspend fun loadAggregatedMonthly(
        weeklySteps: List<Long>, labels: List<String>,
        start: Long, end: Long, tz: TimeZone,
        weeks: List<Pair<String, List<DailyMovementAggregate?>>>,
    ) {
        val daysWithData = weeks.flatMap { it.second }.count { it != null }.coerceAtLeast(1)
        _activity.value = buildActivityState(weeklySteps, labels, daysWithData, start, end)

        val sleepEntries = libPebble.getSleepEntries(start, end)
        val entriesByDay = sleepEntries.groupBy { Instant.fromEpochSeconds(it.startTime).toLocalDateTime(tz).date.toString() }
        val stackedSleep = weeks.map { (label, days) ->
            var light = 0f; var deep = 0f
            for (d in days) {
                if (d == null) continue
                val de = entriesByDay[d.day] ?: continue
                light += de.filter { OverlayType.fromValue(it.type) == OverlayType.Sleep }.sumOf { it.duration } / 3600f
                deep += de.filter { OverlayType.fromValue(it.type) == OverlayType.DeepSleep }.sumOf { it.duration } / 3600f
            }
            val count = days.count { it != null }.coerceAtLeast(1)
            StackedSleepEntry(label, light / count, deep / count)
        }
        _sleep.value = buildSleepState(stackedSleep, daysWithData, sleepEntries, tz)
        _heartRate.value = buildHeartRateState(start, end)
    }

}

internal fun buildDailySleepSegments(dayStart: Long, session: SleepSession?): List<SleepSegmentUi> {
    if (session == null) return emptyList()
    val ws = dayStart - (HealthConstants.SLEEP_WINDOW_START_OFFSET_HOURS * 3600L)
    val we = dayStart + (HealthConstants.SLEEP_WINDOW_END_OFFSET_HOURS * 3600L)
    val wd = (we - ws).toFloat()
    if (wd <= 0) return emptyList()
    val sf = ((session.start - ws) / wd).coerceIn(0f, 1f)
    val tw = ((session.end - session.start) / wd).coerceIn(0f, 1f - sf)
    val df = if (session.totalSleep > 0) session.deepSleep.toFloat() / session.totalSleep else 0f
    return listOf(
        SleepSegmentUi(sf, tw * (1f - df), false),
        SleepSegmentUi(sf + tw * (1f - df), tw * df, true),
    ).filter { it.widthFraction > 0f }
}

private fun formatTimeOfDay(epochSec: Long, tz: TimeZone): String {
    val dt = Instant.fromEpochSeconds(epochSec).toLocalDateTime(tz)
    val h = dt.hour; val m = dt.minute
    val ampm = if (h < 12) "AM" else "PM"
    val h12 = if (h == 0) 12 else if (h > 12) h - 12 else h
    return "$h12:${m.toString().padStart(2, '0')} $ampm"
}

internal fun averageTimeOfDay(secondsOfDay: List<Long>): Long {
    if (secondsOfDay.isEmpty()) return 0L
    val halfDay = 43200L
    val fullDay = 86400L
    val ref = secondsOfDay.first()
    val normalized = secondsOfDay.map { s ->
        val diff = s - ref
        when {
            diff > halfDay -> s - fullDay
            diff < -halfDay -> s + fullDay
            else -> s
        }
    }
    return ((normalized.average().toLong()) % fullDay + fullDay) % fullDay
}

private fun formatTimeFromSeconds(secOfDay: Long): String {
    val h = ((secOfDay / 3600) % 24).toInt()
    val m = ((secOfDay % 3600) / 60).toInt()
    val ampm = if (h < 12) "AM" else "PM"
    val h12 = if (h == 0) 12 else if (h > 12) h - 12 else h
    return "$h12:${m.toString().padStart(2, '0')} $ampm"
}
