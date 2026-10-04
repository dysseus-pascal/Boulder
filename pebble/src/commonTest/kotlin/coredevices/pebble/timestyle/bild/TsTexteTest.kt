package coredevices.pebble.timestyle.bild

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Die Texte der Leiste mit DENSELBEN Vektoren wie die Firmware
 * (tests/fw/apps/watch/timestyle/test_timestyle.c und
 * test_timestyle_einstellungen.c) - die Vorschau muss dieselben Zeichen
 * zeigen wie die Uhr, sonst stimmt kein Pixel.
 */
class TsTexteTest {
    @Test
    fun schritte() {
        assertEquals("5.5k", TsTexte.schritte(5523, '.'))
        assertEquals("5.5k", TsTexte.schritte(5599, '.'))
        assertEquals("0", TsTexte.schritte(0, '.'))
        assertEquals("999", TsTexte.schritte(999, '.'))
        assertEquals("1.0k", TsTexte.schritte(1000, '.'))
        assertEquals("9.9k", TsTexte.schritte(9999, '.'))
        assertEquals("10k", TsTexte.schritte(10000, '.'))
        assertEquals("123k", TsTexte.schritte(123456, '.'))
        assertEquals("5,5k", TsTexte.schritte(5523, ','))
        assertEquals("0", TsTexte.schritte(-1, '.'))
    }

    @Test
    fun distanzMetrischAnDenGrenzen() {
        assertEquals("0m" to false, TsTexte.distanz(0, true, '.'))
        assertEquals("99m" to false, TsTexte.distanz(99, true, '.'))
        assertEquals(".1km" to false, TsTexte.distanz(100, true, '.'))
        assertEquals(".9km" to false, TsTexte.distanz(999, true, '.'))
        assertEquals(",9km" to false, TsTexte.distanz(999, true, ','))
        assertEquals("1km" to false, TsTexte.distanz(1000, true, '.'))
        // Ganze km, abgeschnitten - "4.2km" (die alte Vorschau) gibt es auf der Uhr nicht.
        assertEquals("4km" to false, TsTexte.distanz(4230, true, '.'))
        assertEquals("9km" to false, TsTexte.distanz(9999, true, '.'))
        // Ab 10 km die kleine Schrift.
        assertEquals("10km" to true, TsTexte.distanz(10000, true, '.'))
    }

    @Test
    fun distanzMeilenAnDenGrenzen() {
        assertEquals(".0mi" to false, TsTexte.distanz(0, false, '.'))
        assertEquals(".4mi" to false, TsTexte.distanz(804, false, '.'))
        assertEquals(",4mi" to false, TsTexte.distanz(804, false, ','))
        assertEquals("1mi" to false, TsTexte.distanz(805, false, '.'))
        assertEquals("3mi" to false, TsTexte.distanz(4230, false, '.'))
        assertEquals("1mi" to false, TsTexte.distanz(2413, false, '.'))
        assertEquals("2mi" to false, TsTexte.distanz(2414, false, '.'))
        assertEquals("12mi" to false, TsTexte.distanz(20000, false, '.'))
    }

    @Test
    fun zweiteZeitzoneWieDieFirmware() {
        // Zuerich im Sommer: 14:00 Ortszeit = 12:00 UTC, UTC-4 also 8 Uhr.
        assertEquals("8a", TsTexte.zweitzeit(14, 0, 7200, -4, h24 = false, fuehrendeNull = false))
        assertEquals("8", TsTexte.zweitzeit(14, 0, 7200, -4, h24 = true, fuehrendeNull = false))
        assertEquals("8a", TsTexte.zweitzeit(14, 0, 3600, -5, h24 = false, fuehrendeNull = false))
        assertEquals("08a", TsTexte.zweitzeit(14, 0, 3600, -5, h24 = false, fuehrendeNull = true))
        assertEquals("08", TsTexte.zweitzeit(14, 0, 3600, -5, h24 = true, fuehrendeNull = true))
        assertEquals("12p", TsTexte.zweitzeit(13, 0, 3600, 0, h24 = false, fuehrendeNull = false))
        assertEquals("12a", TsTexte.zweitzeit(1, 0, 3600, 0, h24 = false, fuehrendeNull = false))
        assertEquals("2", TsTexte.zweitzeit(14, 0, 7200, 14, h24 = true, fuehrendeNull = false))
        assertEquals("11", TsTexte.zweitzeit(1, 0, 7200, -12, h24 = true, fuehrendeNull = false))
        assertEquals("11a", TsTexte.zweitzeit(1, 0, 7200, -12, h24 = false, fuehrendeNull = false))
        // Halbe Stunde Abstand (Indien): 14:00 = 08:30 UTC.
        assertEquals("8", TsTexte.zweitzeit(14, 0, 19800, 0, h24 = true, fuehrendeNull = false))
        assertEquals("3", TsTexte.zweitzeit(14, 0, 19800, -5, h24 = true, fuehrendeNull = false))
    }

    @Test
    fun stundeWieStrftime() {
        // %k / %l fuellen mit einem Leerzeichen auf (die Ziffer steht dann rechts), %H / %I mit 0.
        assertEquals(" 9", TsTexte.stunde(9, h24 = true, fuehrendeNull = false))
        assertEquals("09", TsTexte.stunde(9, h24 = true, fuehrendeNull = true))
        assertEquals("15", TsTexte.stunde(15, h24 = true, fuehrendeNull = false))
        assertEquals(" 3", TsTexte.stunde(15, h24 = false, fuehrendeNull = false))
        assertEquals("03", TsTexte.stunde(15, h24 = false, fuehrendeNull = true))
        assertEquals("12", TsTexte.stunde(0, h24 = false, fuehrendeNull = false))
        assertEquals("12", TsTexte.stunde(12, h24 = false, fuehrendeNull = false))
        assertEquals(" 0", TsTexte.stunde(0, h24 = true, fuehrendeNull = false))
    }

    @Test
    fun wocheWieStrftimeV() {
        // 3.10.2026 (Samstag, Tag 275 ab 0) liegt in KW 40 - die alte Vorschau zeigte 41.
        assertEquals("40", TsTexte.woche(2026, 275, 6))
        // 1.1.2021 (Freitag) gehoert zur KW 53 von 2020.
        assertEquals("53", TsTexte.woche(2021, 0, 5))
        // 31.12.2024 (Dienstag) ist schon KW 1 von 2025.
        assertEquals("01", TsTexte.woche(2024, 365, 2))
        // 4.1.2026 (Sonntag) ist KW 1.
        assertEquals("01", TsTexte.woche(2026, 3, 0))
    }

    @Test
    fun beatsWieDieUhr() {
        // 15:45 UTC = 16:45 in Biel: (60300 * 1000 + 43200) / 86400 = 698.
        assertEquals(698, TsTexte.beats(15 * 3600 + 45 * 60))
        // Mitternacht UTC = 01:00 in Biel: 3643200 / 86400 = 42.
        assertEquals(42, TsTexte.beats(0))
        assertEquals(0, TsTexte.beats(23 * 3600))
        assertEquals(999, TsTexte.beats(22 * 3600 + 59 * 60))
        // Die Rundung (+43200) laeuft in der letzten halben Beat auf 1000 = 0 - wie auf der Uhr.
        assertEquals(0, TsTexte.beats(22 * 3600 + 59 * 60 + 59))
    }

    @Test
    fun akkuUndTemperatur() {
        assertEquals("80%", TsTexte.akku(80, gross = false, sprache = 0))
        // Tuerkisch: das Prozentzeichen vorn; grosse Schrift: nur die Zahl.
        assertEquals("%80", TsTexte.akku(80, gross = false, sprache = 6))
        assertEquals("80", TsTexte.akku(80, gross = true, sprache = 6))
        assertEquals("22°", TsTexte.temperatur(22, gross = false))
        assertEquals("22", TsTexte.temperatur(22, gross = true))
        assertEquals("-3°", TsTexte.temperatur(-3, gross = false))
    }
}
