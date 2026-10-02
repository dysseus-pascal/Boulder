package coredevices.pebble.kiesel

import kotlin.test.Test
import kotlin.test.assertEquals

class HerzgrenzenTest {
    @Test
    fun vorgabe_heisst_tanaka() {
        // Alter 40: 208 - 28 = 180
        assertEquals(180, Herzgrenzen.maxpuls(KieselEinstellungen.MAXPULS_VORGABE, 40, 55))
    }

    @Test
    fun gemessener_wert_gilt_wenn_plausibel() {
        assertEquals(188, Herzgrenzen.maxpuls(188, 40, 55))
        // Ueber Tanaka + 25 ist ein Tippfehler
        assertEquals(180, Herzgrenzen.maxpuls(220, 40, 55))
        // Kaum ueber dem Ruhepuls ebenso
        assertEquals(180, Herzgrenzen.maxpuls(70, 40, 55))
    }

    @Test
    fun ohne_alter_bleibt_die_einstellung() {
        assertEquals(KieselEinstellungen.MAXPULS_VORGABE, Herzgrenzen.maxpuls(KieselEinstellungen.MAXPULS_VORGABE, null, 55))
        assertEquals(200, Herzgrenzen.maxpuls(200, null, 55))
    }

    @Test
    fun ruhepuls_ist_der_median_der_letzten_naechte() {
        // Eine Fiebernacht mit 70 verschiebt den Median nicht
        assertEquals(53, Herzgrenzen.ruhepuls(listOf(52, 53, 54, 0, 53, 70)))
    }
}
