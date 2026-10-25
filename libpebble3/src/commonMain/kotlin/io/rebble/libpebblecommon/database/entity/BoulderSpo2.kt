package io.rebble.libpebblecommon.database.entity

import io.rebble.libpebblecommon.database.MillisecondInstant
import io.rebble.libpebblecommon.database.asMillisecond
import io.rebble.libpebblecommon.database.dao.ValueParams
import io.rebble.libpebblecommon.packets.blobdb.BlobResponse
import io.rebble.libpebblecommon.services.FirmwareVersion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant.Companion.DISTANT_PAST

/*
 * Boulder: die SpO2-Einstellungen der Uhr, die Core nicht synchronisiert. Seit PebbleOS v4.42 sind
 * sie ein Satz spo2Preferences mit drei Byte wie hrmPreferences (an, Intervall, im Sport); davor
 * trug spo2Preferences nur das Intervall, und die beiden Schalter hatten je einen eigenen
 * Schluessel. Die Uhr nimmt nur die Laenge an, die ihre Fassung kennt. Die App fuehrt darum alle
 * drei Zeilen mit demselben Stand und schickt jeder Fassung ihre.
 */

private const val SATZ = "spo2Preferences"
private const val AN_BIS_441 = "bloodOxygenPreferences"
private const val SPORT_BIS_441 = "bloodOxygenActivityPreferences"

/** Die Schluessel, die die Uhr ueber WatchPrefs schickt; die App fuehrt sie unter HealthParams. */
internal val BOULDER_SPO2_SCHLUESSEL = setOf(SATZ, AN_BIS_441, SPORT_BIS_441)

// Ab hier der Satz mit drei Byte (PebbleOS prefs.c, prv_migrate_activity_spo2_prefs).
private val FW_SPO2_EIN_SATZ = FirmwareVersion(
    stringVersion = "v4.42.0",
    timestamp = DISTANT_PAST,
    major = 4,
    minor = 42,
    patch = 0,
    suffix = null,
    gitHash = "",
    isRecovery = false,
    isDualSlot = false,
    isSlot0 = false,
)

private val json = Json { ignoreUnknownKeys = true }

/** Die SpO2-Einstellungen, wie die Einstellungsseite sie zeigt und setzt. */
data class Spo2Einstellungen(
    val an: Boolean = false,
    val intervall: HRMonitoringInterval = HRMonitoringInterval.TenMin,
    val imSport: Boolean = false,
)

// Bis 0.59 trug die Zeile nur das Intervall; an und imSport sind dann null und stehen in den
// beiden alten Zeilen.
@Serializable
private data class Spo2Satz(
    val measurementInterval: HRMonitoringInterval = HRMonitoringInterval.TenMin,
    val enabled: Boolean? = null,
    val activityTrackingEnabled: Boolean? = null,
)

@Serializable
private data class Spo2Schalter(val enabled: Boolean = false)

private fun Boolean.alsByte(): UByte = if (this) 1u else 0u

/** Der Wert einer SpO2-Zeile fuer die Uhr; null heisst: nicht senden. */
internal fun boulderSpo2Wert(id: String, value: String, params: ValueParams): UByteArray? {
    val einSatz = params.firmwareVersion >= FW_SPO2_EIN_SATZ
    return when (id) {
        SATZ -> {
            val satz = json.decodeFromString<Spo2Satz>(value)
            val intervall = satz.measurementInterval.value.toUByte()
            if (!einSatz) return ubyteArrayOf(intervall)
            // Eine Zeile von 0.59 kennt die Schalter nicht. Die Uhr hat sie beim Start aus ihren
            // alten Schluesseln uebernommen; ein geratener Wert wuerde sie ueberschreiben.
            val an = satz.enabled ?: return null
            val sport = satz.activityTrackingEnabled ?: return null
            ubyteArrayOf(an.alsByte(), intervall, sport.alsByte())
        }
        // v4.42 nimmt die alten Schluessel nicht mehr an.
        AN_BIS_441, SPORT_BIS_441 ->
            if (einSatz) null else ubyteArrayOf(json.decodeFromString<Spo2Schalter>(value).enabled.alsByte())
        else -> null
    }
}

private fun schalter(zeile: HealthSettingsEntry?): Boolean =
    zeile?.let { json.decodeFromString<Spo2Schalter>(it.value).enabled } ?: false

private fun stand(satz: HealthSettingsEntry?, an: HealthSettingsEntry?, sport: HealthSettingsEntry?): Spo2Einstellungen {
    val s = satz?.let { json.decodeFromString<Spo2Satz>(it.value) } ?: Spo2Satz()
    return Spo2Einstellungen(
        an = s.enabled ?: schalter(an),
        intervall = s.measurementInterval,
        imSport = s.activityTrackingEnabled ?: schalter(sport),
    )
}

private fun zeilen(e: Spo2Einstellungen, zeit: MillisecondInstant) = listOf(
    HealthSettingsEntry(SATZ, json.encodeToString(Spo2Satz(e.intervall, e.an, e.imSport)), zeit),
    HealthSettingsEntry(AN_BIS_441, json.encodeToString(Spo2Schalter(e.an)), zeit),
    HealthSettingsEntry(SPORT_BIS_441, json.encodeToString(Spo2Schalter(e.imSport)), zeit),
)

fun HealthSettingsEntryDao.spo2Einstellungen(): Flow<Spo2Einstellungen> =
    combine(getEntryFlow(SATZ), getEntryFlow(AN_BIS_441), getEntryFlow(SPORT_BIS_441), ::stand)

suspend fun HealthSettingsEntryDao.setzeSpo2Einstellungen(einstellungen: Spo2Einstellungen) {
    insertOrReplace(zeilen(einstellungen, Clock.System.now().asMillisecond()))
}

/**
 * Eine SpO2-Zeile von der Uhr. Sie aendert den Stand, und alle drei Zeilen bekommen ihn mit der
 * Zeit der Uhr. Fuer diese Uhr gelten sie danach als synchron: sie hat die Werte schon.
 */
internal suspend fun HealthSettingsEntryDao.schreibeSpo2VonDerUhr(
    key: String,
    bytes: ByteArray,
    zeit: MillisecondInstant,
    transport: String,
): BlobResponse.BlobStatus {
    val vorher = getEntry(key)
    if (vorher != null && zeit.instant <= vorher.timestamp.instant) return BlobResponse.BlobStatus.DataStale
    fun intervall(b: Byte) = HRMonitoringInterval.fromInt(b)
    val alt = stand(getEntry(SATZ), getEntry(AN_BIS_441), getEntry(SPORT_BIS_441))
    val neu = when {
        key == SATZ && bytes.size == 3 ->
            Spo2Einstellungen(an = bytes[0] != 0.toByte(), intervall = intervall(bytes[1]), imSport = bytes[2] != 0.toByte())
        key == SATZ && bytes.size == 1 -> alt.copy(intervall = intervall(bytes[0]))
        key == AN_BIS_441 && bytes.size == 1 -> alt.copy(an = bytes[0] != 0.toByte())
        key == SPORT_BIS_441 && bytes.size == 1 -> alt.copy(imSport = bytes[0] != 0.toByte())
        else -> return BlobResponse.BlobStatus.InvalidData
    }
    for (zeile in zeilen(neu, zeit)) {
        insertOrReplace(zeile)
        markSyncedToWatch(
            HealthSettingsEntrySyncEntity(recordId = zeile.id, transport = transport, watchSynchHashcode = zeile.recordHashCode())
        )
    }
    return BlobResponse.BlobStatus.Success
}
