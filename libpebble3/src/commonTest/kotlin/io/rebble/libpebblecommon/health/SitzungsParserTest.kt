package io.rebble.libpebblecommon.health

import io.rebble.libpebblecommon.health.parsers.parseOverlayData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Sitzungssaetze (Datalogging-Tag 84) der Boulder-Firmware, Version 7.
 *
 * Aufbau wie ActivitySessionDataLoggingRecord in der Firmware: version, size,
 * activity (je u16), utc_to_local (i32), start, elapsed (u32), acht Bytes
 * Schrittdaten, dann das neue Byte `manual`. 27 Byte je Satz.
 */
class SitzungsParserTest {

    private fun satz(version: Int, art: Int, start: Long, sekunden: Long, schritte: Int, manual: Int?): ByteArray {
        val b = mutableListOf<Byte>()
        fun u16(v: Int) { b += (v and 0xFF).toByte(); b += ((v shr 8) and 0xFF).toByte() }
        fun u32(v: Long) { for (i in 0 until 4) b += ((v shr (8 * i)) and 0xFF).toByte() }
        u16(version); u16(if (manual != null) 27 else 26); u16(art)
        u32(7200) // utc_to_local
        u32(start); u32(sekunden)
        u16(schritte); u16(12); u16(80); u16(1500) // Schritte, aktive kcal, Ruhe-kcal, Meter
        if (manual != null) b += manual.toByte()
        return b.toByteArray()
    }

    @Test
    fun bewegungUndTrainingMitKennzeichen() {
        val daten = satz(7, 8, 1_790_000_000, 1500, 2500, 0) +
            satz(7, 7, 1_790_003_000, 3600, 5200, 1)
        val saetze = parseOverlayData(daten, 27u)

        assertEquals(2, saetze.size)
        val bewegung = saetze[0]
        assertEquals(OverlayType.Movement.value, bewegung.type)
        assertEquals(2500, bewegung.steps)
        // Reihenfolge wie ActivitySessionDataStepping in der Firmware: die
        // Vorlage las aktive und Ruhe-kcal vertauscht.
        assertEquals(12, bewegung.activeKiloCalories)
        assertEquals(80, bewegung.restingKiloCalories)
        assertEquals(1500, bewegung.distanceCm)
        assertFalse(bewegung.manual)
        val training = saetze[1]
        assertEquals(OverlayType.OpenWorkout.value, training.type)
        assertEquals(5200, training.steps)
        assertTrue(training.manual)
    }

    @Test
    fun schlafMitKennzeichenBleibtAusgerichtet() {
        // Beim Schlaf liest der Parser die acht Bytes Schrittdaten nicht; das
        // Kennzeichen muss trotzdem vom Satzanfang aus gefunden werden, und der
        // naechste Satz darf nicht verrutschen.
        val daten = satz(7, 1, 1_790_000_000, 25_000, 0, 0) +
            satz(7, 8, 1_790_030_000, 1200, 1800, 0)
        val saetze = parseOverlayData(daten, 27u)

        assertEquals(2, saetze.size)
        assertEquals(OverlayType.Sleep.value, saetze[0].type)
        assertEquals(25_000L, saetze[0].duration)
        assertEquals(OverlayType.Movement.value, saetze[1].type)
        assertEquals(1800, saetze[1].steps)
    }

    @Test
    fun alteFirmwareOhneKennzeichen() {
        // Version 3 der offiziellen Firmware: 26 Byte, kein `manual`.
        val saetze = parseOverlayData(satz(3, 5, 1_790_000_000, 900, 1100, null), 26u)
        assertEquals(1, saetze.size)
        assertEquals(OverlayType.Walk.value, saetze[0].type)
        assertEquals(1100, saetze[0].steps)
        assertEquals(12, saetze[0].activeKiloCalories)
        assertEquals(80, saetze[0].restingKiloCalories)
        assertFalse(saetze[0].manual)
    }
}
