package coredevices.pebble.timestyle

import coredevices.pebble.timestyle.bild.TsSprachen
import coredevices.pebble.timestyle.bild.mischeFarbe
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Die Daten der Vorschau gegen die DATEN DER FIRMWARE, nicht gegen eine
 * Abschrift: resources/timestyle/languages.c ist die Datei der Firmware
 * (src/fw/apps/watch/timestyle/languages.c), gcolor_blend_33.bin die Tabelle
 * s_blending_lookup_33_percent aus src/fw/applib/graphics/gtypes.c (4096 Byte,
 * Reihenfolge wie dort). Aendert sich eine davon in der Firmware, kommt sie
 * hier neu hin.
 */
class TsSprachenUndMischungTest {
    private val ordner = File("src/androidHostTest/resources/timestyle")

    /** Die Zeichenketten des C-Feldes [name] in der Reihenfolge der Datei. */
    private fun feld(name: String): List<String> {
        val c = File(ordner, "languages.c").readText()
        val anfang = c.indexOf("$name[")
        require(anfang >= 0) { "$name fehlt" }
        val ende = c.indexOf("};", anfang)
        return Regex("\"([^\"]*)\"").findAll(c.substring(c.indexOf('{', anfang), ende)).map { it.groupValues[1] }.toList()
    }

    @Test
    fun tageWieDieFirmware() {
        val c = feld("dayNames")
        assertEquals(37 * 7, c.size)
        assertEquals(c, TsSprachen.TAGE.flatMap { it.toList() })
    }

    @Test
    fun monateWieDieFirmware() {
        val c = feld("monthNames")
        assertEquals(37 * 12, c.size)
        assertEquals(c, TsSprachen.MONATE.flatMap { it.toList() })
    }

    @Test
    fun wochenwortWieDieFirmware() {
        val c = feld("wordForWeek")
        assertEquals(37, c.size)
        assertEquals(c, TsSprachen.WOCHE.toList())
    }

    @Test
    fun mischungWieDieTabelleDerFirmware() {
        val tabelle = File(ordner, "gcolor_blend_33.bin").readBytes().map { it.toInt() and 0xff }
        assertEquals(4096, tabelle.size)
        for (quelle in 0 until 64) for (ziel in 0 until 64) {
            // Faktor 1 (33 % Quelle): tabelle[ziel + 64 * quelle]; Faktor 2: gespiegelt.
            assertEquals(tabelle[ziel + 64 * quelle], mischeFarbe(0xc0 or quelle, 0xc0 or ziel, 1), "33 % $quelle auf $ziel")
            assertEquals(tabelle[quelle + 64 * ziel], mischeFarbe(0xc0 or quelle, 0xc0 or ziel, 2), "66 % $quelle auf $ziel")
            assertEquals(0xc0 or ziel, mischeFarbe(0xc0 or quelle, 0xc0 or ziel, 0))
            assertEquals(0xc0 or quelle, mischeFarbe(0xc0 or quelle, 0xc0 or ziel, 3))
        }
    }
}
