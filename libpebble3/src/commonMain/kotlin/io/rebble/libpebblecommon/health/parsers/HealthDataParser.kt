package io.rebble.libpebblecommon.health.parsers

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.database.entity.HealthDataEntity
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import io.rebble.libpebblecommon.database.entity.Spo2ReadingEntity
import io.rebble.libpebblecommon.health.OverlayType
import io.rebble.libpebblecommon.util.DataBuffer
import io.rebble.libpebblecommon.util.Endian
import kotlinx.coroutines.CancellationException

private val logger = Logger.withTag("HealthDataParser")

/**
 * Result of parsing an ActivityMinuteData (tag 81) payload. Step/HR/overlay data goes into
 * [healthData]; any per-minute SpO2 readings (version 14+ only, where spo2_percent > 0) go into
 * [spo2Readings] keyed by their minute timestamp.
 */
data class ParsedHealthData(
    val healthData: List<HealthDataEntity>,
    val spo2Readings: List<Spo2ReadingEntity>,
)

/**
 * Parses step/movement data from the watch's health payload.
 *
 * Supports multiple firmware versions:
 * - Firmware 3.10 and below (version 5)
 * - Firmware 3.11 (version 6) - adds calorie and distance data
 * - Firmware 4.0 (version 7) - adds heart rate data
 * - Version 12 - adds heart rate weight
 * - Firmware 4.3 (version 13) - adds heart rate zone
 * - Firmware 4.4 (version 14) - adds SpO2 (spo2_percent + spo2_quality)
 *
 * Boulder: jedes Item und jede Minute wird aus einem eigenen Teilpuffer gelesen,
 * die Minute in der Laenge, die der Kopf nennt (sample_size). Ein Item, das sich
 * so nicht lesen laesst, faellt allein weg und wird an [verworfen] gemeldet.
 *
 * @param payload Raw byte array from the watch
 * @param itemSize Size of each data item in bytes
 * @param verworfen bekommt fuer jedes ganz oder teilweise verworfene Item den Grund
 * @return Parsed health data entities (steps/HR) plus any SpO2 readings, ready for database insertion
 */
fun parseStepsData(payload: ByteArray, itemSize: UShort, verworfen: (String) -> Unit = {}): ParsedHealthData {
    val groesse = itemSize.toInt()
    if (payload.isEmpty() || groesse == 0) {
        logger.w { "Cannot parse steps data: empty payload or zero item size" }
        return ParsedHealthData(emptyList(), emptyList())
    }

    val records = mutableListOf<HealthDataEntity>()
    val spo2Readings = mutableListOf<Spo2ReadingEntity>()

    if (payload.size % groesse != 0) {
        val grund = "Steps payload size (${payload.size}) is not a multiple of item size ($itemSize); parsing what we can"
        logger.w { grund }
        verworfen(grund)
    }

    val packetCount = payload.size / groesse
    for (i in 0 until packetCount) {
        val item = payload.copyOfRange(i * groesse, (i + 1) * groesse)
        // Erst nach dem ganzen Item uebernehmen: ein Fehler mittendrin laesst keine halben Minuten zurueck.
        val gelesen = try {
            leseSchrittItem(item, i, packetCount, verworfen)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val grund = "Steps item $i of $packetCount unreadable: ${e.message}"
            logger.w(e) { grund }
            verworfen(grund)
            null
        } ?: continue
        records += gelesen.healthData
        spo2Readings += gelesen.spo2Readings
    }

    return ParsedHealthData(records, spo2Readings)
}

// version (2), time_utc (4), time_local_offset_15_min (1), sample_size (1), num_samples (1)
private const val SCHRITT_KOPF_BYTES = 9

private fun leseSchrittItem(
    item: ByteArray,
    i: Int,
    packetCount: Int,
    verworfen: (String) -> Unit,
): ParsedHealthData? {
    val kopf = DataBuffer(item.toUByteArray())
    kopf.setEndian(Endian.Little)
    val version = kopf.getUShort()
    val timestamp = kopf.getUInt()
    kopf.getByte() // time_local_offset_15_min
    val recordLength = kopf.getUByte().toInt()
    val recordNum = kopf.getUByte().toInt()

    val mindestens = MINUTE_BYTES[version]
    if (mindestens == null) {
        val grund = "Unsupported health steps record version=$version, skipping packet $i of $packetCount"
        logger.w { grund }
        verworfen(grund)
        return null
    }
    // Kuerzer als die Felder dieser Fassung: jede Minute laege versetzt.
    if (recordLength < mindestens) {
        val grund = "Steps item $i: sample_size $recordLength < $mindestens for version $version"
        logger.w { grund }
        verworfen(grund)
        return null
    }
    val passen = (item.size - SCHRITT_KOPF_BYTES) / recordLength
    if (recordNum > passen) {
        val grund = "Steps item $i: num_samples $recordNum, only $passen fit into ${item.size} bytes"
        logger.w { grund }
        verworfen(grund)
    }

    val records = mutableListOf<HealthDataEntity>()
    val spo2Readings = mutableListOf<Spo2ReadingEntity>()
    var currentTimestamp = timestamp
    for (j in 0 until minOf(recordNum, passen)) {
        val von = SCHRITT_KOPF_BYTES + j * recordLength
        val buffer = DataBuffer(item.copyOfRange(von, von + recordLength).toUByteArray())
        buffer.setEndian(Endian.Little)

        val steps = buffer.getUByte().toInt()
        val orientation = buffer.getUByte().toInt()
        val intensity = buffer.getUShort().toInt()
        val lightIntensity = buffer.getUByte().toInt()

        val flags = if (version >= VERSION_FW_3_10_AND_BELOW) {
            buffer.getUByte().toInt()
        } else {
            0
        }

        var restingGramCalories = 0
        var activeGramCalories = 0
        var distanceCm = 0
        var heartRate = 0
        var heartRateWeight = 0
        var heartRateZone = 0
        var spo2Percent = 0
        var spo2Quality = 0

        if (version >= VERSION_FW_3_11) {
            restingGramCalories = buffer.getUShort().toInt()
            activeGramCalories = buffer.getUShort().toInt()
            distanceCm = buffer.getUShort().toInt()
        }

        if (version >= VERSION_FW_4_0) {
            heartRate = buffer.getUByte().toInt()
        }

        if (version >= VERSION_HR_GEWICHT) {
            heartRateWeight = buffer.getUShort().toInt()
        }

        if (version >= VERSION_FW_4_3) {
            heartRateZone = buffer.getUByte().toInt()
        }

        if (version >= VERSION_FW_4_4) {
            spo2Percent = buffer.getUByte().toInt()
            spo2Quality = buffer.getUByte().toInt()
        }

        records.add(
            HealthDataEntity(
                timestamp = currentTimestamp.toLong(),
                steps = steps,
                orientation = orientation,
                intensity = intensity,
                lightIntensity = lightIntensity,
                activeMinutes = if ((flags and 2) > 0) 1 else 0,
                restingGramCalories = restingGramCalories,
                activeGramCalories = activeGramCalories,
                distanceCm = distanceCm,
                heartRate = heartRate,
                heartRateZone = heartRateZone,
                heartRateWeight = heartRateWeight
            )
        )

        // Only persist SpO2 samples with an actual reading; most minutes carry 0.
        if (spo2Percent > 0) {
            spo2Readings.add(
                Spo2ReadingEntity(
                    timestamp = currentTimestamp.toLong(),
                    spo2Percent = spo2Percent,
                    quality = spo2Quality,
                )
            )
        }

        currentTimestamp += 60u
    }
    return ParsedHealthData(records, spo2Readings)
}

/**
 * Parses overlay data (sleep, activities) from the watch's health payload.
 *
 * Overlay types include:
 * - Sleep and nap sessions (deep sleep, light sleep, naps)
 * - Walk and run activities with step counts and distance
 *
 * @param payload Raw byte array from the watch
 * @param itemSize Size of each data item in bytes
 * @param verworfen bekommt fuer jedes verworfene Item den Grund (Boulder)
 * @return List of parsed overlay data entities ready for database insertion
 */
fun parseOverlayData(payload: ByteArray, itemSize: UShort, verworfen: (String) -> Unit = {}): List<OverlayDataEntity> {
    if (payload.isEmpty() || itemSize.toInt() == 0) {
        logger.w { "Cannot parse overlay data: empty payload or zero item size" }
        return emptyList()
    }

    val buffer = DataBuffer(payload.toUByteArray())
    buffer.setEndian(Endian.Little)
    val records = mutableListOf<OverlayDataEntity>()

    if (payload.size % itemSize.toInt() != 0) {
        val grund = "Overlay payload size (${payload.size}) is not a multiple of item size ($itemSize); parsing what we can"
        logger.w { grund }
        verworfen(grund)
    }

    val packetCount = payload.size / itemSize.toInt()
    logger.d { "Parsing $packetCount overlay packets" }

    for (i in 0 until packetCount) {
        val itemStart = buffer.readPosition

        val version = buffer.getUShort()
        // Boulder: das size-Feld des Satzes (sizeof(ActivitySessionDataLoggingRecord)).
        val satzGroesse = buffer.getUShort().toInt()
        val rawType = buffer.getUShort().toInt()
        val type = OverlayType.fromValue(rawType)

        if (type == null) {
            val remaining = itemSize.toInt() - 6 // already consumed 6 bytes
            if (remaining > 0) buffer.getBytes(remaining)
            val grund = "Unknown overlay type: $rawType, skipping packet $i"
            logger.w { grund }
            verworfen(grund)
            continue
        }

        val offsetUTC = buffer.getUInt()
        val startTime = buffer.getUInt()
        val duration = buffer.getUInt()

        var steps = 0
        var restingKiloCalories = 0
        var activeKiloCalories = 0
        var distanceM = 0

        // Schrittdaten tragen alle Schritt-Sitzungen, auch selbst gestartete
        // Workouts und die Bewegung der Boulder-Firmware - nicht nur Walk und Run.
        val mitSchritten = type == OverlayType.Walk || type == OverlayType.Run ||
            type == OverlayType.OpenWorkout || type == OverlayType.Movement
        if (version < 3.toUShort() || !mitSchritten) {
            if (version == 3.toUShort()) {
                // Firmware 3.x includes calorie/distance data even for non-walk/run types
                buffer.getBytes(8)
            }
        } else {
            // Reihenfolge wie ActivitySessionDataStepping in der Firmware: Schritte,
            // aktive kcal, Ruhe-kcal, Meter. Die Vorlage las aktiv und Ruhe vertauscht.
            steps = buffer.getUShort().toInt()
            activeKiloCalories = buffer.getUShort().toInt()
            restingKiloCalories = buffer.getUShort().toInt()
            distanceM = buffer.getUShort().toInt()
        }

        // BOULDER-FIRMWARE, FASSUNG 7: nach den acht Bytes Schrittdaten
        // (Byte 18..25) folgt an Byte 26 das Kennzeichen "selbst gestartet".
        // Nur genau diese Fassung mit passendem size-Feld: eine kuenftige
        // Upstream-Fassung mit Bit 2 traegt dort etwas anderes. Die Schrittdaten
        // wurden oben nicht bei jeder Art gelesen - deshalb vom Satzanfang aus hinspringen.
        var manual = false
        if (version.toInt() == BOULDER_SITZUNG_FASSUNG && satzGroesse >= MANUAL_OFFSET + 1 &&
            itemSize.toInt() >= MANUAL_OFFSET + 1
        ) {
            val bisManual = MANUAL_OFFSET - (buffer.readPosition - itemStart)
            if (bisManual >= 0 && buffer.remaining > bisManual) {
                if (bisManual > 0) buffer.getBytes(bisManual)
                manual = buffer.getUByte().toInt() != 0
            }
        }

        records.add(
            OverlayDataEntity(
                startTime = startTime.toLong(),
                duration = duration.toLong(),
                type = type.value,
                steps = steps,
                restingKiloCalories = restingKiloCalories,
                activeKiloCalories = activeKiloCalories,
                distanceM = distanceM,
                offsetUTC = offsetUTC.toInt(),
                manual = manual,
            )
        )

        val consumed = buffer.readPosition - itemStart
        val expected = itemSize.toInt()
        if (consumed < expected) {
            val skipAmount = expected - consumed
            if (buffer.remaining >= skipAmount) {
                buffer.getBytes(skipAmount)
            } else {
                val grund = "Buffer exhausted skipping overlay padding: consumed=$consumed, expected=$expected, remaining=${buffer.remaining}"
                logger.w { grund }
                verworfen(grund)
                break
            }
        } else if (consumed > expected) {
            logger.w { "Health overlay item over-read: consumed=$consumed, expected=$expected" }
        }
    }

    return records
}

// Lage des Kennzeichens "selbst gestartet" im Sitzungssatz der Boulder-Firmware.
private const val MANUAL_OFFSET = 26
// ACTIVITY_SESSION_LOGGING_VERSION der Boulder-Firmware (activity_private.h).
private const val BOULDER_SITZUNG_FASSUNG = 7

// Firmware version constants for health data parsing
private val VERSION_FW_3_10_AND_BELOW: UShort = 5u
private val VERSION_FW_3_11: UShort = 6u
private val VERSION_FW_4_0: UShort = 7u
// Boulder: die Firmware zaehlt 4, 5, 6, 7, 12, 13, 14 (activity_algorithm.h,
// "12: Added total heart rate weight"); eine 8 gibt es nicht.
private val VERSION_HR_GEWICHT: UShort = 12u
private val VERSION_FW_4_3: UShort = 13u
private val VERSION_FW_4_4: UShort = 14u

// Boulder: Bytes je Minute, die eine Fassung mindestens liest (AlgMinuteDLSSample).
private val MINUTE_BYTES: Map<UShort, Int> = mapOf(
    VERSION_FW_3_10_AND_BELOW to 6,
    VERSION_FW_3_11 to 12,
    VERSION_FW_4_0 to 13,
    VERSION_HR_GEWICHT to 15,
    VERSION_FW_4_3 to 16,
    VERSION_FW_4_4 to 18,
)
