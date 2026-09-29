package coredevices.pebble.kiesel

import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import coredevices.pebble.health.nachtAlsSaetze
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class NachtSaetzeTest {
    private val b = 1_790_000_000L

    @Test
    fun nacht_wird_schlaf_mit_phasen_ruhepuls_und_hrv() {
        val n = NachtEntity(b, 7200, einschlafen = 60, aufwachen = 120, schlafMinuten = 55, ruhepuls = 51, hrv = 44)
        val saetze = nachtAlsSaetze(n, listOf(
            NachtAbschnittEntity(b, 60, 80, 1), NachtAbschnittEntity(b, 80, 85, 0),
            NachtAbschnittEntity(b, 85, 100, 2), NachtAbschnittEntity(b, 100, 120, 3),
        ))
        val schlaf = saetze.filterIsInstance<SleepSessionRecord>().single()
        assertEquals(Instant.ofEpochSecond(b + 3600), schlaf.startTime)
        assertEquals(
            listOf(SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_DEEP, SleepSessionRecord.STAGE_TYPE_REM),
            schlaf.stages.map { it.stage },
        )
        assertEquals(51L, saetze.filterIsInstance<RestingHeartRateRecord>().single().beatsPerMinute)
        assertEquals(44.0, saetze.filterIsInstance<HeartRateVariabilityRmssdRecord>().single().heartRateVariabilityMillis)
    }

    @Test
    fun ohne_messung_nur_der_schlaf() {
        val n = NachtEntity(b, 0, einschlafen = 10, aufwachen = 400, schlafMinuten = 380, ruhepuls = 0, hrv = 0)
        assertEquals(1, nachtAlsSaetze(n, listOf(NachtAbschnittEntity(b, 10, 400, 4))).size)
    }
}
