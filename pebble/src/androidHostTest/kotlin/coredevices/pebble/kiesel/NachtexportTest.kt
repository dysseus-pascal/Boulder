package coredevices.pebble.kiesel

import io.rebble.libpebblecommon.database.entity.NachtHrvEntity
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class NachtexportTest {
    // 26.9.2026, 20:00 in Zuerich (Sommerzeit).
    private val beginn = 1_790_445_600L
    private val zone = ZoneId.of("Europe/Zurich")

    @Test
    fun hrv_fenster_im_format_der_testnaechte() {
        // Wie nacht-2026-09-26.csv der Firmware: Leerzeile, Kopf, je Fenster
        // Minute ab Beginn, Uhrzeit, RMSSD und Puls - nach der Minute geordnet.
        val text = Nachtexport.hrvTeil(beginn, listOf(
            NachtHrvEntity(beginn, 150, 49, 54),
            NachtHrvEntity(beginn, 120, 57, 0),
        ), zone)
        assertEquals("\nhrv_minute,uhrzeit,rmssd,puls\n120,22:00,57,0\n150,22:30,49,54\n", text)
    }

    @Test
    fun ohne_fenster_kein_teil() {
        // Aeltere Firmware schickt keine Fenster: der Export bleibt wie bisher.
        assertEquals("", Nachtexport.hrvTeil(beginn, emptyList(), zone))
    }
}
