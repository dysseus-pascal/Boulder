package coredevices.pebble.health

import co.touchlab.kermit.Logger
import com.viktormykhailiv.kmp.health.HealthDataType
import com.viktormykhailiv.kmp.health.HealthManager
import com.viktormykhailiv.kmp.health.HealthRecord
import com.viktormykhailiv.kmp.health.records.ExerciseSessionRecord
import com.viktormykhailiv.kmp.health.records.ExerciseType
import com.viktormykhailiv.kmp.health.records.HeartRateRecord
import com.viktormykhailiv.kmp.health.records.SleepSessionRecord
import com.viktormykhailiv.kmp.health.records.SleepStageType
import com.viktormykhailiv.kmp.health.records.StepsRecord
import com.viktormykhailiv.kmp.health.records.metadata.Device
import com.viktormykhailiv.kmp.health.records.metadata.DeviceType
import com.viktormykhailiv.kmp.health.records.metadata.Metadata
import coredevices.util.AppResumed
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.HealthDataEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import io.rebble.libpebblecommon.health.OverlayType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal expect fun exerciseWriteTypes(): List<HealthDataType>

internal expect fun supportsSleepWriting(): Boolean

/**
 * Requests the platform's health read/write permissions in a single prompt where the platform
 * allows it. On Android this bundles the standard types together with blood oxygen (SpO2), which
 * health-kmp can't model, so they don't surface as two separate prompts. Returns true if the
 * standard types were granted (SpO2 is treated as best-effort on Android).
 */
internal expect suspend fun requestPlatformHealthPermissions(
    healthManager: HealthManager,
    readTypes: List<HealthDataType>,
    writeTypes: List<HealthDataType>,
): Boolean

class PlatformHealthSync(
    private val libPebble: LibPebble,
    private val tracker: HealthSyncTracker,
    private val appResumed: AppResumed,
    private val healthManager: HealthManager,
    private val healthDataApi: HealthDataApi,
) {
    private val logger = Logger.withTag("PlatformHealthSync")

    // Boulder: nie zwei Syncs parallel, und ein Anstoss waehrend eines Syncs
    // (etwa die eine Meldung eines Schubs) holt der laufende nach.
    private val einzellauf = Einzellauf(beiFehler = { logger.e(it) { "Health platform sync failed" } })
    val syncing: StateFlow<Boolean> = einzellauf.laeuft
    private val rechteanlass = Rechteanlass()

    /** Start observing health data updates and app foreground events, auto-syncing to the platform. */
    fun startAutoSync(scope: CoroutineScope) {
        scope.launch {
            libPebble.healthDataUpdated.collect {
                sync()
            }
        }
        scope.launch {
            sync()
            appResumed.appResumed.collect {
                // Boulder: beim Zurueckkehren fragt der Sync die Akte auch ohne Neues.
                rechteanlass.merke()
                sync()
            }
        }
    }

    companion object {
        val RequestedReadTypes = emptyList<HealthDataType>()
        val RequestedWriteTypes = listOf(
            HealthDataType.Steps,
            HealthDataType.HeartRate,
            HealthDataType.Sleep,
        ) + exerciseWriteTypes()
    }

    /** Check if the health platform is available on this device. */
    fun isAvailable(): Boolean {
        return healthManager.isAvailable().getOrDefault(false)
    }

    /**
     * Request write permissions. Returns true if granted.
     *
     * Boulder: [nurEinschalten] fuer den Dialog beim Start - er darf den Sync
     * einschalten, nie aus: wer ihn wegwischt, hat den Sync nicht abgewaehlt.
     */
    suspend fun requestPermissions(nurEinschalten: Boolean = false): Boolean {
        val success = try {
            requestPlatformHealthPermissions(healthManager, RequestedReadTypes, RequestedWriteTypes)
        } catch (e: CancellationException) {
            // Boulder: abgeloest von einer neueren Anfrage - keine Antwort, keine Ablehnung.
            throw e
        } catch (e: Exception) {
            logger.w(e) { "Health platform doesn't support requested types" }
            syncNachFrage(false, nurEinschalten)?.let(tracker::setEnabled)
            return false
        }
        logger.v { "requestPermissions success=$success" }
        syncNachFrage(success, nurEinschalten)?.let(tracker::setEnabled)
        GlobalScope.launch {
            sync()
        }
        return success
    }

    suspend fun hasPermission(): Boolean {
        val result = try {
            healthManager.isAuthorized(
                readTypes = RequestedReadTypes,
                writeTypes = RequestedWriteTypes,
            )
        } catch (e: Exception) {
            logger.w(e) { "Health platform doesn't support requested types" }
            return false
        }
        logger.v { "hasPermission: result=$result" }
        return result.getOrDefault(false)
    }

    /** Boulder: wie [hasPermission], aber null, wenn die Akte nicht antwortet. */
    private suspend fun berechtigt(): Boolean? = try {
        healthManager.isAuthorized(readTypes = RequestedReadTypes, writeTypes = RequestedWriteTypes)
            .onFailure { logger.w(it) { "Health platform permission check failed" } }
            .getOrNull()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w(e) { "Health platform permission check failed" }
        null
    }

    /** Run a sync: query new data from Room DB, map to HealthKMP records, write. */
    suspend fun sync() {
        if (!tracker.enabled.value) return
        einzellauf.lauf {
            // Ein Nachlauf nach entzogener Berechtigung soll nicht erneut fragen.
            if (!tracker.enabled.value) return@lauf
            // Boulder: die Rechtefrage ist IPC (10-30 ms); ohne Neues nur nach Start und
            // Rueckkehr in die App - so fallen die ~24 Stundenlaeufe am Tag ohne Neues weg.
            if (!rechteanlass.fragen { gibtNeues() }) return@lauf
            // Boulder: nur ein klares Nein der Akte schaltet aus. Antwortet sie
            // gerade nicht (Update, Dienst startet), wartet der Sync auf den
            // naechsten Anstoss, statt sich ohne den Schalter abzuschalten.
            val berechtigt = berechtigt() ?: return@lauf
            if (!berechtigt) {
                logger.w { "No health sync permission during sync attempt!" }
                tracker.setEnabled(false)
                return@lauf
            }
            syncStepsAndHeartRate()
            syncOverlays()
            syncOxygenSaturation()
            syncNaechte()
            logger.d { "Health platform sync completed" }
        }
    }

    /** Boulder: genau dann, wenn einer der vier Syncs unten etwas schreiben wuerde. */
    private suspend fun gibtNeues(): Boolean =
        (healthDataApi.getLatestTimestamp() ?: 0L) > tracker.lastSyncedStepsTimestamp ||
            healthDataApi.getOverlayEntriesAfter(tracker.lastSyncedOverlayTimestamp, SYNC_OVERLAYS).isNotEmpty() ||
            (supportsOxygenSaturationWriting() &&
                (healthDataApi.getLatestSpo2Reading()?.timestampEpochSec ?: 0L) > tracker.lastSyncedSpo2Timestamp) ||
            (supportsNaechteWriting() && naechteOderNull("new nights") { neueNaechte() }?.isNotEmpty() == true)

    private suspend fun syncStepsAndHeartRate() {
        val lastTimestamp = tracker.lastSyncedStepsTimestamp
        val latestTimestamp = healthDataApi.getLatestTimestamp() ?: return
        if (latestTimestamp <= lastTimestamp) return
        // Boulder: tageweise, Cursor je Block; im Alltag (~15 Minuten) ein Block wie bisher.
        inTagesbloecken(
            nach = lastTimestamp,
            letzte = latestTimestamp,
            hatZeilen = { von, bis -> healthDataApi.getTotalHealthData(von, bis)?.steps != null },
            lies = { von, bis -> healthDataApi.getHealthDataForRange(von, bis) },
            verarbeite = { schreibeMinuten(it) },
        )
    }

    private suspend fun schreibeMinuten(records: List<HealthDataEntity>): Boolean {
        val healthRecords = mutableListOf<HealthRecord>()

        for (entity in records) {
            val startTime = Instant.fromEpochSeconds(entity.timestamp)
            val endTime = startTime + 1.minutes

            // Steps
            if (entity.steps > 0) {
                healthRecords += StepsRecord(
                    startTime = startTime,
                    endTime = endTime,
                    count = entity.steps,
                    metadata = createMetadata(entity.timestamp, "steps"),
                )
            }

            // Heart rate
            if (entity.heartRate in 1..300) {
                healthRecords += HeartRateRecord(
                    startTime = startTime,
                    endTime = endTime,
                    samples = listOf(
                        HeartRateRecord.Sample(
                            time = startTime,
                            beatsPerMinute = entity.heartRate,
                        )
                    ),
                    metadata = createMetadata(entity.timestamp, "hr"),
                )
            }
        }

        if (healthRecords.isNotEmpty()) {
            val result = healthManager.writeData(healthRecords)
            if (result.isSuccess) {
                tracker.lastSyncedStepsTimestamp = records.last().timestamp
                logger.d { "Synced ${healthRecords.size} step/HR records" }
            } else {
                logger.e { "Failed to write step/HR records: ${result.exceptionOrNull()}" }
                return false
            }
        } else {
            tracker.lastSyncedStepsTimestamp = records.last().timestamp
        }
        return true
    }

    private suspend fun syncNaechte() {
        if (!supportsNaechteWriting()) return
        // AUCH DIE ZULETZT GESCHRIEBENE NACHT NOCH EINMAL, wenn sie sich
        // geaendert hat: die Uhr schickt eine Nacht nach weiterem Schlaf mit
        // demselben Beginn erneut. Die Akte ersetzt sie (gleiche Kennung).
        val naechte = naechteOderNull("new nights") { neueNaechte() } ?: return
        if (naechte.isEmpty()) return
        val mitPhasen = naechteOderNull("phases of new nights") {
            naechte.map { it to healthDataApi.getNachtAbschnitte(it.beginnUtc) }
        } ?: return
        if (writeNaechteToPlatform(mitPhasen)) {
            val juengste = naechte.maxBy { it.beginnUtc }
            tracker.lastSyncedNachtBeginn = juengste.beginnUtc
            tracker.lastSyncedNachtStand = nachtStand(juengste)
            logger.d { "Synced ${naechte.size} nights" }
        }
    }

    private suspend fun neueNaechte(): List<NachtEntity> {
        val letzter = tracker.lastSyncedNachtBeginn
        return healthDataApi.getNaechte(letzter, Long.MAX_VALUE)
            .filter { it.beginnUtc != letzter || nachtStand(it) != tracker.lastSyncedNachtStand }
    }

    private fun nachtStand(n: NachtEntity): String = "${n.aufwachen}:${n.schlafMinuten}"

    /**
     * Boulder: ein Zugriff auf Boulders Naechte, oder null mit Log. Sie liegen in
     * boulder.db; scheitert der Umzug dorthin, wirft jeder Zugriff, bis er gelingt.
     * Das darf Schritte, Puls, Trainings und SpO2 nicht mit aufhalten.
     */
    private suspend fun <T> naechteOderNull(was: String, lies: suspend () -> T): T? = try {
        lies()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w(e) { "Boulder nights unreadable ($was); trying again on the next sync" }
        null
    }

    private suspend fun syncOxygenSaturation() {
        if (!supportsOxygenSaturationWriting()) return
        val lastTimestamp = tracker.lastSyncedSpo2Timestamp
        val juengste = healthDataApi.getLatestSpo2Reading()?.timestampEpochSec ?: return
        if (juengste <= lastTimestamp) return
        inTagesbloecken(
            nach = lastTimestamp,
            letzte = juengste,
            hatZeilen = { von, bis -> healthDataApi.getAverageSpo2(von, bis) != null },
            lies = { von, bis -> healthDataApi.getSpo2Readings(von, bis) },
        ) { readings ->
            val result = writeOxygenSaturationToPlatform(readings)
            if (result) {
                tracker.lastSyncedSpo2Timestamp = readings.last().timestamp
                logger.d { "Synced ${readings.size} SpO2 records" }
            } else {
                logger.e { "Failed to write SpO2 records: keeping last-synced cursor" }
            }
            result
        }
    }

    // Boulder: ohne Bloecke - wenige je Tag, und eine Grenze zerschnitte die Schlafgruppen.
    private suspend fun syncOverlays() {
        val lastTimestamp = tracker.lastSyncedOverlayTimestamp
        val overlays = healthDataApi.getOverlayEntriesAfter(lastTimestamp, SYNC_OVERLAYS)
        if (overlays.isEmpty()) return

        val sleepOverlays = overlays.filter { it.type in SCHLAF_OVERLAYS }
        val exerciseOverlays = overlays.filter { it.type in TRAINING_OVERLAYS }

        var maxSyncedTimestamp = lastTimestamp

        // Write sleep sessions separately so exercise failures don't block sleep
        // Boulder: wo die Firmware eine Nacht gerechnet hat, schreibt syncNaechte den Schlaf
        // mit allen Phasen; die Sitzungen aus den Aktivitaetsdaten wuerden ihn verdoppeln.
        val nachtSpannen = if (supportsNaechteWriting() && sleepOverlays.isNotEmpty()) {
            naechteOderNull("nights around sleep overlays") {
                healthDataApi.getNaechte(sleepOverlays.minOf { it.startTime } - 86_400, sleepOverlays.maxOf { it.startTime } + 86_400)
                    .map { it.schlafSpanne() }
            }
        } else emptyList()
        // Boulder: ohne die Naechte kein Schlaf (er koennte eine Nacht verdoppeln). Trainings
        // davor gehen trotzdem; ab dem ersten Schlaf wartet alles, denn es gibt nur einen
        // Zeiger, und ein zweites Mal geschrieben stuende ein Training doppelt in der Akte
        // (health-kmp gibt die Kennung nicht als clientRecordId weiter).
        val schlafWartet = nachtSpannen == null
        val trainings = if (schlafWartet) {
            val ersterSchlaf = sleepOverlays.minOf { it.startTime }
            exerciseOverlays.filter { it.startTime < ersterSchlaf }
        } else exerciseOverlays
        val sleepRecords = if (supportsSleepWriting() && nachtSpannen != null) buildSleepSessions(ohneFirmwareNacht(sleepOverlays, nachtSpannen)) else emptyList()
        if (schlafWartet) {
            logger.w { "Holding back ${sleepOverlays.size} sleep overlays until Boulder's nights are readable" }
        } else if (sleepRecords.isNotEmpty()) {
            logger.v { "Writing ${sleepRecords.size} sleep sessions to health platform" }
            val result = healthManager.writeData(sleepRecords)
            if (result.isSuccess) {
                maxSyncedTimestamp = maxOf(maxSyncedTimestamp, sleepOverlays.maxOf { it.startTime })
                logger.d { "Synced ${sleepRecords.size} sleep records" }
            } else {
                logger.e { "Failed to write sleep records: ${result.exceptionOrNull()}" }
            }
        } else if (sleepOverlays.isNotEmpty()) {
            maxSyncedTimestamp = maxOf(maxSyncedTimestamp, sleepOverlays.maxOf { it.startTime })
        }

        // Write exercise records separately
        val exerciseRecords = mutableListOf<HealthRecord>()
        for (overlay in trainings) {
            if (overlay.duration <= 0) continue
            // Boulder: selbst gestartete Trainings (Kieselsport in der
            // Boulder-Firmware) traegt der Kieselsport-Empfang ein - mit Art,
            // Route, Saetzen und Pulskurve. Hier noch einmal eingetragen,
            // stuende jedes Training doppelt in der Akte.
            if (overlay.manual) continue
            val startTime = Instant.fromEpochSeconds(overlay.startTime)
            val endTime = startTime + overlay.duration.seconds

            val overlayType = OverlayType.fromValue(overlay.type) ?: continue
            val exerciseType = when (overlayType) {
                OverlayType.Walk -> ExerciseType.Walking
                OverlayType.Run -> ExerciseType.Running
                OverlayType.OpenWorkout -> ExerciseType.OtherWorkout
                else -> continue
            }

            exerciseRecords += ExerciseSessionRecord(
                startTime = startTime,
                endTime = endTime,
                exerciseType = exerciseType,
                title = when (overlayType) {
                    OverlayType.Walk -> "Walk"
                    OverlayType.Run -> "Run"
                    OverlayType.OpenWorkout -> "Workout"
                    else -> null
                },
                exerciseRoute = null,
                metadata = createMetadata(overlay.startTime, "exercise"),
            )
        }
        if (exerciseRecords.isNotEmpty()) {
            val result = healthManager.writeData(exerciseRecords)
            if (result.isSuccess) {
                maxSyncedTimestamp = maxOf(maxSyncedTimestamp, trainings.maxOf { it.startTime })
                logger.d { "Synced ${exerciseRecords.size} exercise records" }
            } else {
                logger.e { "Failed to write exercise records: ${result.exceptionOrNull()}" }
            }
        } else if (trainings.isNotEmpty()) {
            maxSyncedTimestamp = maxOf(maxSyncedTimestamp, trainings.maxOf { it.startTime })
        }

        if (maxSyncedTimestamp > lastTimestamp) {
            tracker.lastSyncedOverlayTimestamp = maxSyncedTimestamp
        }
    }

    private fun buildSleepSessions(overlays: List<OverlayDataEntity>): List<SleepSessionRecord> {
        if (overlays.isEmpty()) return emptyList()

        // Filter to only overlays with positive duration before grouping
        val valid = overlays.filter { it.duration > 0 }.sortedBy { it.startTime }
        if (valid.isEmpty()) return emptyList()

        val groups = mutableListOf<MutableList<OverlayDataEntity>>()
        var currentGroup = mutableListOf(valid.first())

        for (i in 1 until valid.size) {
            val prev = currentGroup.last()
            val prevEnd = prev.startTime + prev.duration
            val curr = valid[i]

            // Group overlays within 2 hours of each other into one session
            if (curr.startTime - prevEnd <= 2 * 3600) {
                currentGroup.add(curr)
            } else {
                groups += currentGroup
                currentGroup = mutableListOf(curr)
            }
        }
        groups += currentGroup

        return groups.mapNotNull { group ->
            try {
                createSleepSession(group)
            } catch (e: Exception) {
                logger.e(e) {
                    "Failed to create sleep session from ${group.size} overlays: " +
                            group.joinToString { "type=${it.type},start=${it.startTime},dur=${it.duration}" }
                }
                null
            }
        }
    }

    private fun createSleepSession(overlays: List<OverlayDataEntity>): SleepSessionRecord {
        val sessionStart = Instant.fromEpochSeconds(overlays.minOf { it.startTime })
        val sessionEnd = Instant.fromEpochSeconds(overlays.maxOf { it.startTime + it.duration })
        val stages = computeSleepStageIntervals(overlays).map { interval ->
            SleepSessionRecord.Stage(
                startTime = Instant.fromEpochSeconds(interval.startSec),
                endTime = Instant.fromEpochSeconds(interval.endSec),
                type = if (interval.isDeep) SleepStageType.Deep else SleepStageType.Light,
            )
        }
        logger.d { "Sleep session: ${stages.size} stages, start=$sessionStart, end=$sessionEnd" }
        return SleepSessionRecord(
            startTime = sessionStart,
            endTime = sessionEnd,
            stages = stages,
            metadata = createMetadata(overlays.first().startTime, "sleep"),
        )
    }

    private fun createMetadata(timestamp: Long, prefix: String): Metadata {
        return Metadata.autoRecorded(
            id = "pebble-$prefix-$timestamp",
            device = Device(type = DeviceType.Watch),
        )
    }
}

// Boulder: aus syncOverlays heraufgezogen, weil gibtNeues dieselben Arten braucht.
private val SCHLAF_OVERLAYS = listOf(
    OverlayType.Sleep.value,
    OverlayType.DeepSleep.value,
    OverlayType.Nap.value,
    OverlayType.DeepNap.value,
)

// Boulder: kein Walk/Run. Erkannte Bewegung ist kein Training - aus
// jedem Weg zum Bus wurde sonst eines in der Akte (Firmware vor b1).
private val TRAINING_OVERLAYS = listOf(
    OverlayType.OpenWorkout.value,
)

private val SYNC_OVERLAYS = SCHLAF_OVERLAYS + TRAINING_OVERLAYS

/**
 * Boulder: der Stand des Syncs nach einer Rechtefrage, null fuer unveraendert.
 * Der Schalter setzt wie bei Gravel beides; der Dialog beim Start nur ein.
 */
internal fun syncNachFrage(erteilt: Boolean, nurEinschalten: Boolean): Boolean? =
    if (erteilt || !nurEinschalten) erteilt else null

/** Boulder: Schlaf-Overlays, die in keine Nacht der Firmware fallen. */
internal fun ohneFirmwareNacht(overlays: List<OverlayDataEntity>, naechte: List<LongRange>): List<OverlayDataEntity> =
    overlays.filter { o -> naechte.none { n -> o.startTime < n.last && o.startTime + o.duration > n.first } }

internal data class SleepStageInterval(val startSec: Long, val endSec: Long, val isDeep: Boolean)

// Pebble's overlay model: Sleep/Nap are container overlays spanning the whole session with
// DeepSleep/DeepNap sub-overlays nested inside them. Carve the Deep periods out of each Light
// container so both stage types reach Health Connect.
internal fun computeSleepStageIntervals(overlays: List<OverlayDataEntity>): List<SleepStageInterval> {
    val (deepOverlays, lightOverlays) = overlays.partition {
        when (OverlayType.fromValue(it.type)) {
            OverlayType.DeepSleep, OverlayType.DeepNap -> true
            else -> false
        }
    }
    val deepRanges = deepOverlays
        .map { it.startTime to it.startTime + it.duration }
        .sortedBy { it.first }

    val intervals = mutableListOf<SleepStageInterval>()
    deepRanges.forEach { (s, e) ->
        intervals += SleepStageInterval(s, e, isDeep = true)
    }
    lightOverlays.forEach { container ->
        val containerEnd = container.startTime + container.duration
        var cursor = container.startTime
        for ((deepStart, deepEnd) in deepRanges) {
            if (deepEnd <= cursor) continue
            if (deepStart >= containerEnd) break
            if (cursor < deepStart) {
                intervals += SleepStageInterval(cursor, deepStart, isDeep = false)
            }
            cursor = maxOf(cursor, deepEnd)
        }
        if (cursor < containerEnd) {
            intervals += SleepStageInterval(cursor, containerEnd, isDeep = false)
        }
    }
    intervals.sortBy { it.startSec }
    return intervals
}
