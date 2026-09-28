package io.rebble.libpebblecommon.health

import io.rebble.libpebblecommon.health.parsers.parseNachtData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Saetze auf Tag 88 (NachtDlsRecord der Boulder-Firmware): 24 Byte Kopf, dann
 * 60 Abschnitte zu 5 Byte - 324 Byte je Satz.
 */
class NachtParserTest {

    private fun satz(
        version: Int = 1,
        beginn: Long = 1_790_445_600,
        teil: Int = 0,
        teile: Int = 1,
        abschnitte: List<Triple<Int, Int, Int>>,
    ): ByteArray {
        val b = mutableListOf<Byte>()
        fun u8(v: Int) { b += (v and 0xFF).toByte() }
        fun u16(v: Int) { u8(v); u8(v shr 8) }
        fun u32(v: Long) { for (i in 0 until 4) u8((v shr (8 * i)).toInt()) }
        u16(version); u16(if (version and 2 != 0) 328 else 324)
        u32(beginn); u32(7200)
        u16(137); u16(660); u16(490)
        u8(52); u8(teil); u8(teile)
        u16(41)
        u8(abschnitte.size)
        abschnitte.forEach { (von, bis, phase) -> u16(von); u16(bis); u8(phase) }
        repeat(60 - abschnitte.size) { repeat(5) { u8(0) } }
        if (version and 2 != 0) {
            u8(78); u8(69); u8(0); u8(3)
        }
        return b.toByteArray()
    }

    @Test
    fun kopfUndAbschnitte() {
        val teile = parseNachtData(
            satz(abschnitte = listOf(Triple(137, 192, 1), Triple(192, 225, 0), Triple(225, 280, 2))),
            324u,
        )
        assertEquals(1, teile.size)
        val t = teile[0]
        assertEquals(1_790_445_600L, t.nacht.beginnUtc)
        assertEquals(7200, t.nacht.utcZuLokal)
        assertEquals(137, t.nacht.einschlafen)
        assertEquals(660, t.nacht.aufwachen)
        assertEquals(490, t.nacht.schlafMinuten)
        assertEquals(52, t.nacht.ruhepuls)
        assertEquals(41, t.nacht.hrv)
        assertEquals(3, t.abschnitte.size)
        assertEquals(225, t.abschnitte[2].von)
        assertEquals(2, t.abschnitte[2].phase)
        assertTrue(t.abschnitte.all { it.beginnUtc == 1_790_445_600L })
    }

    @Test
    fun mehrereTeileNacheinander() {
        val daten = satz(teil = 0, teile = 2, abschnitte = List(60) { Triple(it * 5, it * 5 + 5, 1) }) +
            satz(teil = 1, teile = 2, abschnitte = listOf(Triple(300, 310, 3)))
        val teile = parseNachtData(daten, 324u)
        assertEquals(2, teile.size)
        assertEquals(60, teile[0].abschnitte.size)
        assertEquals(1, teile[1].teil)
        assertEquals(3, teile[1].abschnitte.single().phase)
    }

    @Test
    fun fassung3MitAkkuUndMessstufe() {
        val t = parseNachtData(satz(version = 3, abschnitte = listOf(Triple(137, 200, 1))), 328u).single()
        assertEquals(78, t.nacht.akkuBeginn)
        assertEquals(69, t.nacht.akkuEnde)
        assertEquals(false, t.nacht.geladen)
        assertEquals(3, t.nacht.messstufe)
        assertEquals(1, t.abschnitte.size)
    }

    @Test
    fun fassung1OhneAkku() {
        val t = parseNachtData(satz(version = 1, abschnitte = emptyList()), 324u).single()
        assertEquals(null, t.nacht.akkuBeginn)
        assertEquals(null, t.nacht.messstufe)
    }

    @Test
    fun unbekannteFassungWirdUebersprungen() {
        assertEquals(0, parseNachtData(satz(version = 2, abschnitte = emptyList()), 324u).size)
    }
}
