package coredevices.pebble.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** Die Vorschau in ganzzahliger Vergroesserung: jedes Uhrpixel gleich viele Telefonpixel. */
class TsZifferblattBildTest {
    @Test
    fun faktorZurWunschhoehe() {
        // Pixel 8 und der Emulator "boulder" (2,625 px/dp): 160 dp = 420 px -> 2 (456 px).
        assertEquals(2, tsFaktor(420f))
        // 2,0 px/dp: 320 px -> 1 (228 px); 4,0 px/dp: 640 px -> 3 (684 px).
        assertEquals(1, tsFaktor(320f))
        assertEquals(3, tsFaktor(640f))
        // Nie 0, auch nicht bei winzigem Platz.
        assertEquals(1, tsFaktor(10f))
        // Knapp unter und ueber der Mitte zwischen 1 und 2 (342 px).
        assertEquals(1, tsFaktor(341f))
        assertEquals(2, tsFaktor(343f))
    }
}
