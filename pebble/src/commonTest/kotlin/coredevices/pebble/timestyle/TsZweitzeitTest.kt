package coredevices.pebble.timestyle

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class TsZweitzeitTest {
    private val sommer = LocalDate(2026, 7, 1)
    private val winter = LocalDate(2026, 1, 15)
    private val zuerich = TimeZone.of("Europe/Zurich")

    private fun text(tag: LocalDate, zone: String, versatz: Int, h24: Boolean = false, null0: Boolean = false, h: Int = 9, m: Int = 41) =
        tsZonenText(tag, TimeZone.of(zone), versatz, h24, null0, h, m)

    // Dieselben Faelle wie der Zeitzonen-Test der Firmware (Spezifikation 5.2).
    @Test
    fun wieDieFirmware() {
        assertEquals("8a", text(sommer, "Europe/Zurich", -4, h = 14, m = 0))
        assertEquals("8", text(sommer, "Europe/Zurich", -4, h24 = true, h = 14, m = 0))
        assertEquals("8a", text(winter, "Europe/Zurich", -5, h = 14, m = 0))
    }

    // Der Befund: halbe Stunden Abstand verschoben die Stunde der Vorschau.
    @Test
    fun halbeStundenAufDieMinute() {
        // 9:41 in St. John's (UTC-2:30 im Sommer) = 12:11 UTC.
        assertEquals("12p", text(sommer, "America/St_Johns", 0))
        // 9:41 in Indien (UTC+5:30) = 4:11 UTC, bei UTC-4 also 0:11.
        assertEquals("12a", text(sommer, "Asia/Kolkata", -4))
        assertEquals("0", text(sommer, "Asia/Kolkata", -4, h24 = true))
        // 9:41 in Indien bei UTC+14 = 18:11.
        assertEquals("6p", text(sommer, "Asia/Kolkata", 14))
    }

    @Test
    fun ganzeStundenUndRaender() {
        assertEquals("3a", tsZonenText(sommer, zuerich, -4, false, false))
        assertEquals("7a", tsZonenText(sommer, zuerich, 0, false, false))
        assertEquals("21", tsZonenText(sommer, zuerich, 14, true, false))
        assertEquals("7p", tsZonenText(sommer, zuerich, -12, false, false))
    }

    // Fuehrende Null wie auf der Uhr: auch die Stunde der zweiten Zone.
    @Test
    fun fuehrendeNull() {
        assertEquals("03a", tsZonenText(sommer, zuerich, -4, false, true))
        assertEquals("03", tsZonenText(sommer, zuerich, -4, true, true))
        assertEquals("12p", text(sommer, "America/St_Johns", 0, null0 = true))
    }
}
