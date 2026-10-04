package coredevices.pebble.health

import android.content.Context
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
import coredevices.pebble.kiesel.Herkunft
import coredevices.pebble.kiesel.lies
import coredevices.pebble.kiesel.versuche
import kotlin.coroutines.cancellation.CancellationException
import java.time.Instant
import java.time.ZoneOffset

internal actual fun supportsNaechteWriting(): Boolean = true

/** Die Phasen der Firmware (0 wach, 1 leicht, 2 tief, 3 REM, 4 Schlaf) als Stufen der Akte. */
internal fun schlafstufe(phase: Int): Int = when (phase) {
    0 -> SleepSessionRecord.STAGE_TYPE_AWAKE
    1 -> SleepSessionRecord.STAGE_TYPE_LIGHT
    2 -> SleepSessionRecord.STAGE_TYPE_DEEP
    3 -> SleepSessionRecord.STAGE_TYPE_REM
    else -> SleepSessionRecord.STAGE_TYPE_SLEEPING
}

/** Die Kennung des Schlafs einer Nacht in der Akte - an ihr ersetzt die Akte ihn. */
internal fun nachtKennung(n: NachtEntity): String = NACHT_PRAEFIX + n.beginnUtc

/**
 * Die Saetze einer Nacht fuer die Akte. Was die Akte schon im Konstruktor
 * abweist, kommt gar nicht erst hinein: Phasen ausserhalb des Schlafs werden
 * beschnitten, ueberlappende verworfen, eine HRV ueber 200 ms faellt weg.
 */
internal fun nachtAlsSaetze(n: NachtEntity, abschnitte: List<NachtAbschnittEntity>): List<Record> {
    val geraet = Device(type = Device.TYPE_WATCH)
    fun zeit(min: Int): Instant = Instant.ofEpochSecond(n.beginnUtc + min * 60L)
    val aufwachen = zeit(n.aufwachen)
    // Der Versatz, den die Uhr zur Nacht gemeldet hat - nicht der des
    // Telefons von heute: die Uhr war dabei, das Telefon vielleicht woanders.
    val versatzUhr = versuche("Naechte", "Versatz der Uhr ${n.utcZuLokal} s unbrauchbar, nehme den des Telefons") { ZoneOffset.ofTotalSeconds(n.utcZuLokal) }
    fun versatz(t: Instant): ZoneOffset = versatzUhr ?: coredevices.pebble.kiesel.versatz(t)
    val saetze = mutableListOf<Record>(
        SleepSessionRecord(
            startTime = zeit(n.einschlafen), startZoneOffset = versatz(zeit(n.einschlafen)),
            endTime = aufwachen, endZoneOffset = versatz(aufwachen),
            metadata = Metadata.autoRecorded(geraet, nachtKennung(n)),
            stages = phasenImSchlaf(n, abschnitte)
                .map { SleepSessionRecord.Stage(zeit(it.von), zeit(it.bis), schlafstufe(it.phase)) },
        )
    )
    if (n.ruhepuls in 20..200) {
        saetze += RestingHeartRateRecord(aufwachen, versatz(aufwachen), n.ruhepuls.toLong(), Metadata.autoRecorded(geraet, "boulder-ruhepuls-${n.beginnUtc}"))
    }
    if (n.hrv in 1..200) {
        saetze += HeartRateVariabilityRmssdRecord(aufwachen, versatz(aufwachen), n.hrv.toDouble(), Metadata.autoRecorded(geraet, "boulder-nacht-hrv-${n.beginnUtc}"))
    } else if (n.hrv > 200) {
        logger.i { "Night ${n.beginnUtc}: HRV ${n.hrv} ms above what Health Connect takes, not written" }
    }
    return saetze
}

/** Die Phasen in [einschlafen, aufwachen], ohne Ueberlappung - nur solche nimmt die Akte an. */
private fun phasenImSchlaf(n: NachtEntity, abschnitte: List<NachtAbschnittEntity>): List<NachtAbschnittEntity> {
    val aus = mutableListOf<NachtAbschnittEntity>()
    for (a in abschnitte.sortedBy { it.von }) {
        val von = maxOf(a.von, n.einschlafen)
        val bis = minOf(a.bis, n.aufwachen)
        if (bis <= von || (aus.isNotEmpty() && von < aus.last().bis)) continue
        aus += a.copy(von = von, bis = bis)
    }
    return aus
}

internal actual suspend fun writeNaechteToPlatform(naechte: List<Pair<NachtEntity, List<NachtAbschnittEntity>>>): Boolean {
    if (naechte.isEmpty()) return true
    val klient = try {
        HealthConnectClient.getOrCreate(context())
    } catch (e: Exception) {
        logger.e(e) { "Health Connect not available for nights" }
        return false
    }
    return schreibeNaechte(context(), klient, naechte)
}

/**
 * Die Naechte in die Akte, jede fuer sich: eine, die nicht geht, haelt die
 * anderen nicht auf. False, wenn die Akte bei einer abwies - dann rueckt der
 * Sync nicht vor und versucht es wieder (die Akte ersetzt ueber die Kennung).
 * Eine Nacht, aus der sich kein Satz bauen laesst, zaehlt nicht als Fehler:
 * sie kaeme sonst bei jedem Sync wieder und hielte alle spaeteren auf.
 */
internal suspend fun schreibeNaechte(
    context: Context,
    klient: HealthConnectClient,
    naechte: List<Pair<NachtEntity, List<NachtAbschnittEntity>>>,
): Boolean {
    var alle = true
    for ((n, abschnitte) in naechte) {
        if (n.aufwachen <= n.einschlafen) continue
        // ERST BAUEN: wirft der Bau, ist noch nichts geloescht.
        val saetze = try {
            nachtAlsSaetze(n, abschnitte)
        } catch (e: IllegalArgumentException) {
            logger.e(e) { "Night ${n.beginnUtc} not valid for Health Connect, skipped" }
            continue
        }
        try {
            schreibeNacht(context, klient, n, saetze)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "Failed writing night ${n.beginnUtc} to Health Connect" }
            alle = false
        }
    }
    logger.d { "Wrote ${naechte.size} nights to Health Connect, all: $alle" }
    return alle
}

/** Eine Nacht: erst den Schlaf einfuegen, dann gezielt den alten eigenen weg, dann Ruhepuls und HRV. */
private suspend fun schreibeNacht(context: Context, klient: HealthConnectClient, n: NachtEntity, saetze: List<Record>) {
    // ERST EINFUEGEN: nimmt die Akte den Schlaf nicht (etwa ohne Recht), bleibt der alte stehen.
    klient.insertRecords(saetze.take(1))
    // WAS DER SYNC VORHER AUS DEN AKTIVITAETSSITZUNGEN SCHRIEB, weicht der
    // Nacht: sonst stuende derselbe Schlaf zweimal da, einmal ohne REM. Nur
    // Boulders eigene Saetze, und keine Nacht der Firmware - die ersetzt die
    // Akte ueber ihre Kennung, und eine benachbarte Nacht bleibt stehen.
    val spanne = n.schlafSpanne()
    val fenster = TimeRangeFilter.between(Instant.ofEpochSecond(spanne.first - 7200), Instant.ofEpochSecond(spanne.last + 7200))
    val alt = klient.lies(context, SleepSessionRecord::class, fenster, Herkunft.EIGENE)
        .filter { it.metadata.clientRecordId?.startsWith(NACHT_PRAEFIX) != true }
        .map { it.metadata.id }
    if (alt.isNotEmpty()) klient.deleteRecords(SleepSessionRecord::class, recordIdsList = alt, clientRecordIdsList = emptyList())
    // Ruhepuls und HRV einzeln: fehlt dafuer ein Recht, bleibt der Schlaf trotzdem stehen.
    saetze.drop(1).forEach { satz ->
        try {
            klient.insertRecords(listOf(satz))
        } catch (e: Exception) {
            logger.w { "Night extra not written: ${e.message}" }
        }
    }
}

private const val NACHT_PRAEFIX = "boulder-nacht-"
