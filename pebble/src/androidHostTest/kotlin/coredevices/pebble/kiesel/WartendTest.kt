package coredevices.pebble.kiesel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WartendTest {
    private val tag = 86_400_000L
    private val jetzt = 1_790_000_000_000L

    @Test
    fun verfallen_ist_erst_was_laenger_liegt() {
        assertFalse(Wartend.verfallen("${jetzt - 29 * tag}-0.json", jetzt, 30))
        assertTrue(Wartend.verfallen("${jetzt - 31 * tag}-12.json", jetzt, 30))
        // Eine Datei mit fremdem Namen ist nie verfallen - sie gehoert nicht uns.
        assertFalse(Wartend.verfallen("notiz.json", jetzt, 30))
    }

    @Test
    fun abgelegt_ist_der_empfang() {
        // Eine Zusammenfassung vom letzten Mal zaehlt mit der Zeit, zu der sie ankam.
        assertEquals(jetzt / 1000, Wartend.abgelegt("$jetzt-3.json"))
        assertNull(Wartend.abgelegt("notiz.json"))
    }
}
