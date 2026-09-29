package coredevices.pebble.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import java.time.Instant

internal actual fun supportsNaechteWriting(): Boolean = true

/** Die Phasen der Firmware (0 wach, 1 leicht, 2 tief, 3 REM, 4 Schlaf) als Stufen der Akte. */
internal fun schlafstufe(phase: Int): Int = when (phase) {
    0 -> SleepSessionRecord.STAGE_TYPE_AWAKE
    1 -> SleepSessionRecord.STAGE_TYPE_LIGHT
    2 -> SleepSessionRecord.STAGE_TYPE_DEEP
    3 -> SleepSessionRecord.STAGE_TYPE_REM
    else -> SleepSessionRecord.STAGE_TYPE_SLEEPING
}

internal fun nachtAlsSaetze(n: NachtEntity, abschnitte: List<NachtAbschnittEntity>): List<Record> {
    val geraet = Device(type = Device.TYPE_WATCH)
    fun zeit(min: Int): Instant = Instant.ofEpochSecond(n.beginnUtc + min * 60L)
    val aufwachen = zeit(n.aufwachen)
    val saetze = mutableListOf<Record>(
        SleepSessionRecord(
            startTime = zeit(n.einschlafen), startZoneOffset = null,
            endTime = aufwachen, endZoneOffset = null,
            metadata = Metadata.autoRecorded(geraet, "boulder-nacht-${n.beginnUtc}"),
            stages = abschnitte.filter { it.bis > it.von }
                .map { SleepSessionRecord.Stage(zeit(it.von), zeit(it.bis), schlafstufe(it.phase)) },
        )
    )
    if (n.ruhepuls in 20..200) {
        saetze += RestingHeartRateRecord(aufwachen, null, n.ruhepuls.toLong(), Metadata.autoRecorded(geraet, "boulder-ruhepuls-${n.beginnUtc}"))
    }
    if (n.hrv in 1..300) {
        saetze += HeartRateVariabilityRmssdRecord(aufwachen, null, n.hrv.toDouble(), Metadata.autoRecorded(geraet, "boulder-nacht-hrv-${n.beginnUtc}"))
    }
    return saetze
}

internal actual suspend fun writeNaechteToPlatform(naechte: List<Pair<NachtEntity, List<NachtAbschnittEntity>>>): Boolean {
    if (naechte.isEmpty()) return true
    return try {
        val klient = HealthConnectClient.getOrCreate(context())
        for ((n, abschnitte) in naechte) {
            if (n.aufwachen <= n.einschlafen) continue
            val spanne = n.schlafSpanne()
            // WAS DER SYNC VORHER AUS DEN AKTIVITAETSSITZUNGEN SCHRIEB, weicht
            // der Nacht: sonst stuende derselbe Schlaf zweimal da, einmal ohne
            // REM. Loeschen trifft nur Boulders eigene Eintraege.
            klient.deleteRecords(
                SleepSessionRecord::class,
                TimeRangeFilter.between(Instant.ofEpochSecond(spanne.first - 7200), Instant.ofEpochSecond(spanne.last + 7200)),
            )
            val saetze = nachtAlsSaetze(n, abschnitte)
            klient.insertRecords(saetze.take(1))
            // Ruhepuls und HRV einzeln: fehlt dafuer ein Recht, bleibt der Schlaf trotzdem stehen.
            saetze.drop(1).forEach { satz ->
                try {
                    klient.insertRecords(listOf(satz))
                } catch (e: Exception) {
                    logger.w { "Night extra not written: ${e.message}" }
                }
            }
        }
        logger.d { "Wrote ${naechte.size} nights to Health Connect" }
        true
    } catch (e: Exception) {
        logger.e(e) { "Failed writing nights to Health Connect" }
        false
    }
}
