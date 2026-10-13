package io.rebble.libpebblecommon.health

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Der Satz "boulder_ziele" Byte fuer Byte gegen BoulderZieleSatz und ziele.h der Firmware V2. */
class BoulderZieleTest {
    @Test
    fun vorgabeAlsSatz() {
        // 10 000 = 0x2710, 480 = 0x01E0, little endian; Fassung 1, Reserven 0.
        assertContentEquals(byteArrayOf(1, 0, 0x10, 0x27, 0xE0.toByte(), 0x01, 0, 0), BoulderZiele.satz(10_000, 480))
    }

    @Test
    fun grenzenDerFirmware() {
        assertContentEquals(byteArrayOf(1, 0, 0xE8.toByte(), 0x03, 0xF0.toByte(), 0, 0, 0), BoulderZiele.satz(1_000, 240))
        assertContentEquals(byteArrayOf(1, 0, 0x50, 0xC3.toByte(), 0xD0.toByte(), 0x02, 0, 0), BoulderZiele.satz(50_000, 720))
        // Ausserhalb verwirft die Uhr den Satz ganz - so einer entsteht gar nicht erst.
        assertFailsWith<IllegalArgumentException> { BoulderZiele.satz(999, 480) }
        assertFailsWith<IllegalArgumentException> { BoulderZiele.satz(50_001, 480) }
        assertFailsWith<IllegalArgumentException> { BoulderZiele.satz(10_000, 239) }
        assertFailsWith<IllegalArgumentException> { BoulderZiele.satz(10_000, 721) }
    }

    @Test
    fun schluesselOhneNullbyte() {
        // Mit Nullbyte waere es fuer die Uhr ein anderer Schluessel (Befund K4 der Firmware).
        val zeile = BoulderZiele.zeile(10_000, 480)
        assertEquals(13, zeile.key().size)
        assertEquals("boulder_ziele", zeile.key().toByteArray().decodeToString())
        assertContentEquals(BoulderZiele.satz(10_000, 480), zeile.payload)
    }
}
