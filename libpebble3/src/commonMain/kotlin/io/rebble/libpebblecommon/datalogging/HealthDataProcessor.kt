package io.rebble.libpebblecommon.datalogging

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.SystemAppIDs.SYSTEM_APP_UUID
import io.rebble.libpebblecommon.database.dao.HealthDao
import io.rebble.libpebblecommon.database.dao.Spo2Dao
import io.rebble.libpebblecommon.database.dao.insertHealthDataWithPriority
import io.rebble.libpebblecommon.database.dao.insertOverlayDataWithDeduplication
import io.rebble.libpebblecommon.database.entity.HealthStatDao
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.health.parsers.parseOverlayData
import io.rebble.libpebblecommon.health.parsers.parseNachtData
import io.rebble.libpebblecommon.health.parsers.parseNachtHrvData
import io.rebble.libpebblecommon.database.dao.NachtDao
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import io.rebble.libpebblecommon.health.parsers.NachtHrvTeil
import io.rebble.libpebblecommon.health.parsers.NachtTeil
import io.rebble.libpebblecommon.health.parsers.ParsedHealthData
import io.rebble.libpebblecommon.health.parsers.parseStepsData
import io.rebble.libpebblecommon.services.updateHealthStatsInDatabase
import io.rebble.libpebblecommon.util.datenbereich
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * Processes health data from DataLogging sessions.
 *
 * This class is called by Datalogging when health tags (81-85) are received,
 * and handles session tracking, data parsing, and database storage.
 *
 * All received health data is processed immediately to prevent data loss.
 * Battery optimization is handled by throttling sync REQUESTS, not data reception.
 */
class HealthDataProcessor(
    private val scope: LibPebbleCoroutineScope,
    private val healthDao: HealthDao,
    private val healthStatDao: HealthStatDao,
    private val spo2Dao: Spo2Dao,
    private val nachtDao: NachtDao,
    private val clock: Clock,
) {
    private val logger = Logger.withTag("HealthDataProcessor")

    private val healthSessions = mutableMapOf<UByte, HealthSession>()
    private val lastTodayUpdateDate = MutableStateFlow<LocalDate?>(null)
    private val lastTodayUpdateTime = MutableStateFlow(0L)
    // Boulder: ohne Puffer wartete emit auf den langsamsten Sammler (den
    // Health-Connect-Sync). Mit einem Platz, der den aelteren Wert verdraengt,
    // wartet niemand, und der juengste Anstoss bleibt fuer den Nachzuegler liegen.
    private val _healthDataUpdated = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val healthDataUpdated: SharedFlow<Unit> = _healthDataUpdated

    // Boulder: ein Schub der Uhr sind Dutzende Pakete; gemeldet wird er einmal,
    // nach den Tageszahlen, damit die Meldung nicht vor ihnen ankommt.
    private val schub = Schubsammler(scope.datenbereich("HealthDataProcessor")) {
        aktualisiereHeute()
        _healthDataUpdated.tryEmit(Unit)
    }

    // Boulder: ein Paket nach dem anderen. Teil 0 einer Nacht raeumt die Abschnitte
    // der vorigen Fassung weg; liefe Teil 1 daneben, verschwaenden dessen Abschnitte mit.
    private val schreibSperre = Mutex()

    companion object {
        private const val HEALTH_STEPS_TAG: UInt = 81u
        private const val HEALTH_SLEEP_TAG: UInt = 83u
        private const val HEALTH_OVERLAY_TAG: UInt = 84u
        private const val HEALTH_HR_TAG: UInt = 85u
        // Boulder-Firmware: die fertige Nacht mit allen Phasen (NachtDlsRecord)
        private const val BOULDER_NACHT_TAG: UInt = 88u
        // Boulder-Firmware: die HRV-Fenster dieser Nacht (NachtHrvDlsRecord). Eine
        // aeltere Firmware schickt ihn nicht; ein aelteres Boulder quittiert ihn
        // als unbekannt und wirft ihn weg.
        private const val BOULDER_NACHT_HRV_TAG: UInt = 89u

        val HEALTH_TAGS = setOf(HEALTH_STEPS_TAG, HEALTH_SLEEP_TAG, HEALTH_OVERLAY_TAG, HEALTH_HR_TAG,
            BOULDER_NACHT_TAG, BOULDER_NACHT_HRV_TAG)

        // Boulder: so oft rechnet ein Schub die Tageszahlen hoechstens neu.
        private val TAGESZAHLEN_ABSTAND = 1.hours
    }

    fun handleSessionOpen(sessionId: UByte, tag: UInt, applicationUuid: Uuid, itemSize: UShort) {
        if (tag !in HEALTH_TAGS) return

        val session = HealthSession(tag, applicationUuid, itemSize)
        healthSessions[sessionId] = session
        logger.d {
            "HEALTH_SESSION: Opened session $sessionId for ${tagName(tag)} (tag=$tag, itemSize=$itemSize bytes)"
        }
    }

    /**
     * Boulder: liest und speichert ein Paket sofort, der Reihe nach, und sagt, ob
     * es gesichert ist. Erst danach darf die Uhr es freigeben (DataLoggingService).
     */
    suspend fun speichere(sessionId: UByte, tag: UInt, appUuid: Uuid, itemSize: UShort, payload: ByteArray): Ablage {
        // Only process health data from the system app
        if (appUuid != SYSTEM_APP_UUID) {
            logger.d { "Ignoring health data from non-system app: $appUuid" }
            return Ablage.Gesichert
        }
        val verworfen = mutableListOf<String>()
        val schreibe = try {
            lies(tag, payload, itemSize) { verworfen += it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "HEALTH_DATA: ${tagName(tag)} packet unreadable (session=$sessionId, ${payload.size} bytes)" }
            return Ablage.Quarantaene("unreadable: ${e.message}")
        }
        val summary = try {
            schreibSperre.withLock { schreibe() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "HEALTH_DATA: ${tagName(tag)} not stored, not acknowledging (session=$sessionId, ${payload.size} bytes)" }
            return Ablage.NichtGespeichert
        }
        logger.d { "HEALTH_SESSION: Received data for ${tagName(tag)} (session=$sessionId, ${payload.size} bytes) - $summary" }
        return if (verworfen.isEmpty()) Ablage.Gesichert else Ablage.Quarantaene(verworfen.joinToString("; "))
    }

    // Erst alles lesen, dann schreiben: eine Ausnahme beim Lesen ist ein unlesbares
    // Paket, eine beim Schreiben ein Speicherfehler - die Uhr bekommt verschiedene Antworten.
    private fun lies(tag: UInt, payload: ByteArray, itemSize: UShort, verworfen: (String) -> Unit): suspend () -> String? =
        when (tag) {
            HEALTH_STEPS_TAG -> parseStepsData(payload, itemSize, verworfen).let { { processStepsData(it) } }
            HEALTH_OVERLAY_TAG -> parseOverlayData(payload, itemSize, verworfen).let { { processOverlayData(it) } }
            HEALTH_SLEEP_TAG -> {
                // Sleep data is sent as overlay data with sleep types
                logger.d { "Received sleep tag data, processing as overlay" }
                parseOverlayData(payload, itemSize, verworfen).let { { processOverlayData(it) } }
            }
            BOULDER_NACHT_TAG -> parseNachtData(payload, itemSize, verworfen).let { { processNachtData(it) } }
            BOULDER_NACHT_HRV_TAG -> parseNachtHrvData(payload, itemSize, verworfen).let { { processNachtHrvData(it) } }
            HEALTH_HR_TAG -> {
                // Heart rate data is embedded in steps data for newer firmware
                logger.d { "Received standalone HR data (tag 85), currently handled in steps data" };
                { null }
            }
            else -> {
                logger.w { "Unknown health data tag: $tag" };
                { null }
            }
        }

    // Boulder: am Ende eines Schubs statt bei itemsLeft == 0 - die Firmware schickt
    // dort immer 0xffff (dls_endpoint.c), der alte Zweig lief nie. Auch am selben Tag
    // wieder, wenn eine Stunde vergangen ist, fuer spaet nachgelieferte Vortagsdaten.
    private suspend fun aktualisiereHeute() {
        val timeZone = TimeZone.currentSystemDefault()
        val jetzt = clock.now()
        val today = jetzt.toLocalDateTime(timeZone).date
        val now = jetzt.toEpochMilliseconds()
        val timeSinceLastUpdate = now - lastTodayUpdateTime.value
        val shouldUpdate = lastTodayUpdateDate.value != today ||
            timeSinceLastUpdate >= TAGESZAHLEN_ABSTAND.inWholeMilliseconds
        if (!shouldUpdate) {
            logger.d { "HEALTH_DATA: Skipping today update - last update ${timeSinceLastUpdate / 60_000}min ago" }
            return
        }
        logger.d {
            "HEALTH_DATA: Received new data, updating today's movement and recent sleep data (last update ${timeSinceLastUpdate / 60_000}min ago)"
        }
        try {
            // Today's data is included in the weekly update, no separate call needed
            updateHealthStatsInDatabase(healthDao, healthStatDao, today, today.minus(DatePeriod(days = 29)), timeZone)
            lastTodayUpdateDate.value = today
            lastTodayUpdateTime.value = now
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "HEALTH_DATA: today's stats update failed" }
        }
    }

    fun handleSessionClose(sessionId: UByte) {
        healthSessions.remove(sessionId)?.let { session ->
            logger.d { "HEALTH_SESSION: Closed session $sessionId for ${tagName(session.tag)}" }
        }
    }

    private fun tagName(tag: UInt): String =
        when (tag) {
            HEALTH_STEPS_TAG -> "STEPS"
            HEALTH_SLEEP_TAG -> "SLEEP"
            HEALTH_OVERLAY_TAG -> "OVERLAY"
            HEALTH_HR_TAG -> "HEART_RATE"
            BOULDER_NACHT_TAG -> "NACHT"
            BOULDER_NACHT_HRV_TAG -> "NACHT_HRV"
            else -> "UNKNOWN($tag)"
        }

    private suspend fun processNachtData(teile: List<NachtTeil>): String? {
        if (teile.isEmpty()) return null
        teile.forEach { t ->
            // Der erste Teil einer Nacht raeumt die Abschnitte der vorigen Fassung weg.
            nachtDao.speichereNachtTeil(t.nacht, ersterTeil = t.teil == 0, t.abschnitte)
        }
        schub.stossAn()
        val n = teile.first().nacht
        return "Nacht ab ${n.beginnUtc}: ${n.schlafMinuten} min Schlaf, Teil ${teile.first().teil + 1}/${teile.first().teile}"
    }

    private suspend fun processNachtHrvData(teile: List<NachtHrvTeil>): String? {
        if (teile.isEmpty()) return null
        teile.forEach { t ->
            // Der erste Teil raeumt die Fenster der vorigen Fassung dieser Nacht weg.
            nachtDao.speichereHrvTeil(t.beginnUtc, ersterTeil = t.teil == 0, t.fenster)
        }
        schub.stossAn()
        val t = teile.first()
        return "HRV der Nacht ab ${t.beginnUtc}: ${t.fenster.size} Fenster, Teil ${t.teil + 1}/${t.teile}"
    }

    private suspend fun processStepsData(parsed: ParsedHealthData): String? {
        val records = parsed.healthData
        logger.d {
            "HEALTH_DATA: Parsed ${records.size} step records and ${parsed.spo2Readings.size} SpO2 readings from payload"
        }
        if (records.isEmpty() && parsed.spo2Readings.isEmpty()) {
            return null
        }

        val totalSteps = records.sumOf { it.steps }
        val totalActiveKcal = records.sumOf { it.activeGramCalories } / 1000
        val totalRestingKcal = records.sumOf { it.restingGramCalories } / 1000
        val totalDistanceKm = records.sumOf { it.distanceCm } / 100000.0
        val totalActiveMin = records.sumOf { it.activeMinutes }
        val heartRateRecords = records.filter { it.heartRate > 0 }
        val avgHeartRate =
            if (heartRateRecords.isNotEmpty()) {
                heartRateRecords.map { it.heartRate }.average().toInt()
            } else 0

        val hrSummary =
            if (heartRateRecords.isNotEmpty()) {
                "avgHR=$avgHeartRate"
            } else "no HR"

        logger.d {
            "HEALTH_DATA: About to insert ${records.size} records into database (steps=$totalSteps, active=${totalActiveKcal}kcal, resting=${totalRestingKcal}kcal, distance=${totalDistanceKm}km, activeMin=$totalActiveMin, $hrSummary)"
        }

        if (records.isNotEmpty()) {
            healthDao.insertHealthDataWithPriority(records)
        }
        if (parsed.spo2Readings.isNotEmpty()) {
            spo2Dao.insertSpo2Readings(parsed.spo2Readings)
            logger.d { "HEALTH_DATA: Inserted ${parsed.spo2Readings.size} SpO2 readings" }
        }
        schub.stossAn()

        return "${records.size} records (${totalSteps} steps)" +
            if (parsed.spo2Readings.isNotEmpty()) ", ${parsed.spo2Readings.size} SpO2" else ""
    }

    private suspend fun processOverlayData(records: List<OverlayDataEntity>): String? {
        logger.d { "HEALTH_DATA: Parsed ${records.size} overlay records from payload" }
        if (records.isEmpty()) return null

        healthDao.insertOverlayDataWithDeduplication(records)
        schub.stossAn()

        val totalDurationHours = kotlin.math.round(records.sumOf { it.duration } / 360.0) / 10.0
        return "${records.size} overlay records (${totalDurationHours}h total)"
    }
}

data class HealthSession(
    val tag: UInt,
    val appUuid: Uuid,
    val itemSize: UShort,
)

/** Boulder: was aus einem Datalogging-Paket wurde; davon haengt die Antwort an die Uhr ab. */
sealed interface Ablage {
    /** Gespeichert, oder es gab nichts zu speichern. */
    data object Gesichert : Ablage

    /** Nicht oder nur zum Teil lesbar; was sich lesen liess, ist gespeichert. Das Paket gehoert roh in die Quarantaene. */
    data class Quarantaene(val grund: String) : Ablage

    /** Nicht gespeichert (Datenbank): nicht bestaetigen, die Uhr schickt es noch einmal. */
    data object NichtGespeichert : Ablage
}
