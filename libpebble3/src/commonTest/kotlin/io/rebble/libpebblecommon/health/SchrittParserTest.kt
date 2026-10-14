package io.rebble.libpebblecommon.health

import io.rebble.libpebblecommon.health.parsers.parseStepsData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Minutensaetze auf Tag 81 (AlgMinuteDLSRecord der Firmware): 9 Byte Kopf
 * (version, time_utc, time_local_offset, sample_size, num_samples), dann
 * num_samples Minuten zu sample_size Byte. Die Firmware schickt 15 Minuten.
 */
class SchrittParserTest {

    /** Ein Item; jede Minute traegt [schritte] + Index als Schritte, Fassung 14 hat 18 Byte Felder. */
    private fun item(
        version: Int = 14,
        beginn: Long = 1_790_000_000,
        minutenBytes: Int = 18,
        anzahl: Int = 15,
        platz: Int = 15,
        schritte: Int = 1,
    ): ByteArray {
        val b = mutableListOf<Byte>()
        fun u8(v: Int) { b += (v and 0xFF).toByte() }
        fun u16(v: Int) { u8(v); u8(v shr 8) }
        fun u32(v: Long) { for (i in 0 until 4) u8((v shr (8 * i)).toInt()) }
        u16(version); u32(beginn); u8(0); u8(minutenBytes); u8(anzahl)
        repeat(platz) { m ->
            val start = b.size
            u8(schritte + m)   // steps
            u8(3)              // orientation
            u16(400)           // vmc
            u8(9)              // light
            u8(2)              // flags: aktiv
            if (version >= 6) { u16(11); u16(22); u16(330) } // Ruhe-, Aktiv-kcal, Strecke
            if (version >= 7) u8(60 + m)                     // Puls
            if (version >= 12) u16(150)                      // Pulsgewicht
            if (version >= 13) u8(1)                         // Zone
            if (version >= 14) { u8(0); u8(0) }              // SpO2
            while (b.size - start < minutenBytes) u8(0xEE)   // Fuellbytes einer laengeren Minute
        }
        return b.toByteArray()
    }

    @Test
    fun minuteMitFuellbyteBleibtAusgerichtet() {
        // sample_size 19 statt 18: eine Firmware, die an jede Minute ein Feld haengt.
        val daten = item(minutenBytes = 19)
        val verworfen = mutableListOf<String>()
        val gelesen = parseStepsData(daten, daten.size.toUShort()) { verworfen += it }
        assertEquals((1..15).toList(), gelesen.healthData.map { it.steps })
        assertEquals((60..74).toList(), gelesen.healthData.map { it.heartRate })
        assertEquals(150, gelesen.healthData.last().heartRateWeight)
        assertTrue(verworfen.isEmpty())
    }

    @Test
    fun einDefektesItemNimmtDieNachbarnNichtMit() {
        // B behauptet 16 Minuten, hat aber nur Platz fuer 15. A und C muessen heil bleiben.
        val a = item(beginn = 1_790_000_000, schritte = 1)
        val b = item(beginn = 1_790_000_900, anzahl = 16, schritte = 101)
        val c = item(beginn = 1_790_001_800, schritte = 201)
        val verworfen = mutableListOf<String>()
        val gelesen = parseStepsData(a + b + c, a.size.toUShort()) { verworfen += it }
        val c0 = 1_790_001_800L
        val vonC = gelesen.healthData.filter { it.timestamp >= c0 }
        assertEquals((201..215).toList(), vonC.map { it.steps })
        assertEquals((0 until 15).map { c0 + it * 60 }, vonC.map { it.timestamp })
        assertEquals((1..15).toList(), gelesen.healthData.filter { it.timestamp < 1_790_000_900 }.map { it.steps })
        assertEquals(1, verworfen.size)
    }

    @Test
    fun zuKurzeMinuteVerwirftNurDiesesItem() {
        // Der Kopf nennt 12 Byte je Minute, Fassung 14 hat aber 18: jede Minute laege versetzt.
        val kurz = item().also { it[7] = 12 }
        val gut = item(beginn = 1_790_000_900)
        val verworfen = mutableListOf<String>()
        val gelesen = parseStepsData(kurz + gut, kurz.size.toUShort()) { verworfen += it }
        // Der Grund nennt die Ursache - er steht neben dem Paket in der Quarantaene.
        assertTrue(verworfen.single().contains("sample_size 12"), verworfen.single())
        assertTrue(gelesen.healthData.all { it.timestamp >= 1_790_000_900 })
        assertEquals(15, gelesen.healthData.size)
    }

    @Test
    fun fassung12MitPulsgewichtWirdGelesen() {
        // activity_algorithm.h: "12: Added total heart rate weight" - 15 Byte je Minute.
        val daten = item(version = 12, minutenBytes = 15)
        val gelesen = parseStepsData(daten, daten.size.toUShort())
        assertEquals(15, gelesen.healthData.size)
        assertEquals(150, gelesen.healthData.first().heartRateWeight)
        assertEquals(60, gelesen.healthData.first().heartRate)
        assertEquals(330, gelesen.healthData.first().distanceCm)
    }

    @Test
    fun unbekannteFassungWirdGemeldet() {
        val daten = item(version = 8)
        val verworfen = mutableListOf<String>()
        assertTrue(parseStepsData(daten, daten.size.toUShort()) { verworfen += it }.healthData.isEmpty())
        assertEquals(1, verworfen.size)
    }
}
