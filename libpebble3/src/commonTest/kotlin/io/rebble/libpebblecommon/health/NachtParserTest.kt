package io.rebble.libpebblecommon.health

import io.rebble.libpebblecommon.health.parsers.parseNachtData
import io.rebble.libpebblecommon.health.parsers.parseNachtHrvData
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
        val verworfen = mutableListOf<String>()
        assertEquals(0, parseNachtData(satz(version = 2, abschnitte = emptyList()), 324u) { verworfen += it }.size)
        assertEquals(1, verworfen.size)
    }

    @Test
    fun fremdeGroesseIstKeineNacht() {
        // Ein 40-Byte-Satz mit Bit 0 (Tag 88 koennte einmal Upstream belegen).
        val kurz = satz(abschnitte = emptyList()).copyOf(40).also { it[2] = 40; it[3] = 0 }
        assertTrue(parseNachtData(kurz, 40u).isEmpty())
        // Itemgroesse stimmt, das size-Feld des Satzes nicht.
        val falsch = satz(version = 3, abschnitte = listOf(Triple(137, 200, 1))).also { it[2] = 44; it[3] = 1 }
        assertTrue(parseNachtData(falsch, 328u).isEmpty())
        assertEquals(1, parseNachtData(satz(version = 3, abschnitte = listOf(Triple(137, 200, 1))), 328u).size)
    }

    @Test
    fun unmoeglicherTeilWirdVerworfen() {
        assertTrue(parseNachtData(satz(teil = 2, teile = 2, abschnitte = emptyList()), 324u).isEmpty())
        // Eine Nacht ohne Abschnitte schickt die Firmware als Teil 0 von 0 (nacht.c, prv_schicke).
        val leer = parseNachtData(satz(teil = 0, teile = 0, abschnitte = emptyList()), 324u).single()
        assertEquals(0, leer.teile)
        assertEquals(490, leer.nacht.schlafMinuten)
    }

    @Test
    fun unplausibleAbschnitteFallenWeg() {
        val verworfen = mutableListOf<String>()
        val t = parseNachtData(
            satz(abschnitte = listOf(Triple(137, 192, 1), Triple(300, 100, 2), Triple(192, 225, 9), Triple(225, 280, 3))),
            324u,
        ) { verworfen += it }.single()
        assertEquals(listOf(137 to 192, 225 to 280), t.abschnitte.map { it.von to it.bis })
        assertEquals(1, verworfen.size)
    }
}

/**
 * Saetze auf Tag 89 (NachtHrvDlsRecord der Boulder-Firmware): 11 Byte Kopf,
 * dann 48 Fenster zu 5 Byte - 251 Byte je Satz.
 */
class NachtHrvParserTest {

    private fun satz(
        version: Int = 1,
        beginn: Long = 1_790_445_600,
        teil: Int = 0,
        teile: Int = 1,
        fenster: List<Triple<Int, Int, Int>>,
    ): ByteArray {
        val b = mutableListOf<Byte>()
        fun u8(v: Int) { b += (v and 0xFF).toByte() }
        fun u16(v: Int) { u8(v); u8(v shr 8) }
        fun u32(v: Long) { for (i in 0 until 4) u8((v shr (8 * i)).toInt()) }
        u16(version); u16(251)
        u32(beginn)
        u8(teil); u8(teile); u8(fenster.size)
        fenster.forEach { (minute, rmssd, puls) -> u16(minute); u16(rmssd); u8(puls) }
        repeat(48 - fenster.size) { repeat(5) { u8(0) } }
        return b.toByteArray()
    }

    @Test
    fun fensterMitMinuteRmssdUndPuls() {
        val daten = satz(fenster = listOf(Triple(120, 57, 0), Triple(150, 312, 54)))
        assertEquals(251, daten.size)
        val t = parseNachtHrvData(daten, 251u).single()
        assertEquals(1_790_445_600L, t.beginnUtc)
        assertEquals(0, t.teil)
        assertEquals(1, t.teile)
        assertEquals(2, t.fenster.size)
        assertEquals(150, t.fenster[1].minute)
        assertEquals(312, t.fenster[1].rmssd)
        assertEquals(54, t.fenster[1].puls)
        assertTrue(t.fenster.all { it.beginnUtc == 1_790_445_600L })
    }

    @Test
    fun zweiTeileNacheinander() {
        val daten = satz(teil = 0, teile = 2, fenster = List(48) { Triple(it * 10, 40 + it, 55) }) +
            satz(teil = 1, teile = 2, fenster = listOf(Triple(480, 61, 52)))
        val teile = parseNachtHrvData(daten, 251u)
        assertEquals(2, teile.size)
        assertEquals(48, teile[0].fenster.size)
        assertEquals(1, teile[1].teil)
        assertEquals(480, teile[1].fenster.single().minute)
    }

    @Test
    fun fremdeGroesseIstKeinHrvSatz() {
        val kurz = satz(fenster = listOf(Triple(0, 50, 50))).copyOf(100).also { it[2] = 100; it[3] = 0 }
        assertTrue(parseNachtHrvData(kurz, 100u).isEmpty())
        val falsch = satz(fenster = listOf(Triple(0, 50, 50))).also { it[2] = 0; it[3] = 1 }
        assertTrue(parseNachtHrvData(falsch, 251u).isEmpty())
    }

    @Test
    fun unbekannteFassungUndLeereFensterFallenWeg() {
        // Bit 0 nicht gesetzt: eine Fassung, die dieses Boulder nicht kennt.
        assertTrue(parseNachtHrvData(satz(version = 2, fenster = listOf(Triple(0, 50, 50))), 251u).isEmpty())
        // RMSSD 0 ist keine Messung.
        val t = parseNachtHrvData(satz(fenster = listOf(Triple(0, 0, 0), Triple(30, 45, 0))), 251u).single()
        assertEquals(listOf(30), t.fenster.map { it.minute })
    }
}
