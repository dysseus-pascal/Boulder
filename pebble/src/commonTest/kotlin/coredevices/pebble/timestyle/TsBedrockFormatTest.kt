package coredevices.pebble.timestyle

import io.rebble.libpebblecommon.database.entity.BytesWatchPref
import io.rebble.libpebblecommon.database.entity.alsHex
import io.rebble.libpebblecommon.database.entity.hexAlsBytes
import io.rebble.libpebblecommon.timeline.TimelineColor
import io.rebble.libpebblecommon.timeline.toPebbleColor
import io.rebble.libpebblecommon.util.toProtocolNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Die gemeinsamen Testvektoren V0..V6 aus der Spezifikation - WOERTLICH, die
 * Firmware prueft dieselben (tests/fw/apps/watch/timestyle/test_timestyle_einstellungen.c).
 */
class TsBedrockFormatTest {
    private val v0 = "01ffc0ffc0000100070a04002e500000414c5400000000000000000000000000"
    private val v1 = "01f4c1dfff02010203090dfb2c8f03004e594300000000000000000000000000"
    private val v2 = "0130003f0c090007010eff0f7820fc0041414141414141410000000000000000"
    private val v2NeuKodiert = "01f0c0ffcc000100070a04002e00000041414141414141000000000000000000"
    private val v3 = "00".repeat(32)
    private val v4 = "02ffc0ffc0000100070a04002e5000ff414c540000000000ffffffffffffffff"
    private val v5 = "01c0ffc0ff01010100020c0e2e0000005ac3bc00000000000000000000000000"

    private fun bytes(hex: String) = hex.hexAlsBytes()!!

    private fun neuKodiert(hex: String) = dekodieren(bytes(hex)).kodieren().alsHex()

    /** Alle Felder aus 1.1 der Spezifikation plus clockFontId - ohne die Fassung. */
    private fun felder(e: TsBedrockEinstellungen) = e.copy(fassung = 0)

    @Test
    fun v0Vorgabe() {
        val e = dekodieren(bytes(v0))
        assertEquals(0xFF, e.timeColor)
        assertEquals(0xC0, e.timeBgColor)
        assertEquals(0xFF, e.sidebarColor)
        assertEquals(0xC0, e.sidebarTextColor)
        assertEquals(0, e.languageId)
        assertEquals(1, e.clockFontId)
        assertEquals(0, e.hourlyVibe)
        assertEquals(listOf(7, 10, 4), e.widgets)
        assertEquals(0, e.altclockOffset)
        assertEquals('.', e.decimalSeparator)
        assertFalse(e.showLeadingZero)
        assertFalse(e.btVibe)
        assertFalse(e.sidebarOnLeft)
        assertFalse(e.useLargeFonts)
        assertTrue(e.activateDisconnectIcon)
        assertTrue(e.showBatteryPct)
        assertFalse(e.disableAutobattery)
        assertFalse(e.healthUseDistance)
        assertFalse(e.healthUseRestfulSleep)
        assertEquals("ALT", e.altclockName)
        assertEquals(1, e.fassung)
        assertEquals(TsBedrockEinstellungen.VORGABE, e)
        assertEquals(v0, neuKodiert(v0))
    }

    @Test
    fun v1AllesAnders() {
        val e = dekodieren(bytes(v1))
        assertEquals(0xF4, e.timeColor)
        assertEquals(0xC1, e.timeBgColor)
        assertEquals(0xDF, e.sidebarColor)
        assertEquals(0xFF, e.sidebarTextColor)
        assertEquals(2, e.languageId)
        assertEquals(1, e.clockFontId)
        assertEquals(2, e.hourlyVibe)
        assertEquals(listOf(3, 9, 13), e.widgets)
        assertEquals(-5, e.altclockOffset)
        assertEquals(',', e.decimalSeparator)
        assertTrue(e.showLeadingZero)
        assertTrue(e.btVibe)
        assertTrue(e.sidebarOnLeft)
        assertTrue(e.useLargeFonts)
        assertFalse(e.activateDisconnectIcon)
        assertFalse(e.showBatteryPct)
        assertTrue(e.disableAutobattery)
        assertTrue(e.healthUseDistance)
        assertTrue(e.healthUseRestfulSleep)
        assertEquals("NYC", e.altclockName)
        assertEquals(v1, neuKodiert(v1))
    }

    @Test
    fun v1FarbenSindDieTimelineFarben() {
        // Die Seite vergleicht ueber toPebbleColor().toProtocolNumber() - V1 nennt die Namen.
        val e = dekodieren(bytes(v1))
        val namen = TimelineColor.entries.associateBy { it.toPebbleColor().toProtocolNumber().toInt() }
        assertEquals("Orange", namen[e.timeColor]?.identifier)
        assertEquals("OxfordBlue", namen[e.timeBgColor]?.identifier)
        assertEquals("ElectricBlue", namen[e.sidebarColor]?.identifier)
        assertEquals("White", namen[e.sidebarTextColor]?.identifier)
    }

    @Test
    fun v2UngueltigeFelderFallenEinzelnZurueck() {
        val e = dekodieren(bytes(v2))
        assertEquals(0xF0, e.timeColor)
        assertEquals(0xC0, e.timeBgColor)
        assertEquals(0xFF, e.sidebarColor)
        assertEquals(0xCC, e.sidebarTextColor)
        assertEquals(0, e.languageId)
        assertEquals(1, e.clockFontId)
        assertEquals(0, e.hourlyVibe)
        assertEquals(listOf(7, 10, 4), e.widgets)
        assertEquals(0, e.altclockOffset)
        assertEquals('.', e.decimalSeparator)
        assertFalse(e.showLeadingZero)
        assertFalse(e.btVibe)
        assertFalse(e.sidebarOnLeft)
        assertFalse(e.useLargeFonts)
        assertFalse(e.activateDisconnectIcon)
        assertFalse(e.showBatteryPct)
        assertFalse(e.disableAutobattery)
        assertFalse(e.healthUseDistance)
        assertFalse(e.healthUseRestfulSleep)
        assertEquals("AAAAAAA", e.altclockName)
        assertEquals(v2NeuKodiert, neuKodiert(v2))
    }

    @Test
    fun v3Fassung0IstDieVorgabe() {
        assertEquals(TsBedrockEinstellungen.VORGABE, dekodieren(bytes(v3)))
        assertEquals(v0, neuKodiert(v3))
    }

    @Test
    fun v4HoehereFassungUeberspringtReserviertes() {
        val e = dekodieren(bytes(v4))
        assertEquals(felder(TsBedrockEinstellungen.VORGABE), felder(e))
        assertEquals(2, e.fassung)
        assertEquals(v0, neuKodiert(v4))
    }

    @Test
    fun v5SchwarzAufWeissMitUtf8() {
        val e = dekodieren(bytes(v5))
        assertEquals(0xC0, e.timeColor)
        assertEquals(0xFF, e.timeBgColor)
        assertEquals(0xC0, e.sidebarColor)
        assertEquals(0xFF, e.sidebarTextColor)
        assertEquals(1, e.languageId)
        assertEquals(1, e.clockFontId)
        assertEquals(1, e.hourlyVibe)
        assertEquals(listOf(0, 2, 12), e.widgets)
        assertEquals(14, e.altclockOffset)
        assertEquals('.', e.decimalSeparator)
        assertFalse(e.showLeadingZero)
        assertFalse(e.btVibe)
        assertFalse(e.sidebarOnLeft)
        assertFalse(e.useLargeFonts)
        assertFalse(e.activateDisconnectIcon)
        assertFalse(e.showBatteryPct)
        assertFalse(e.disableAutobattery)
        assertFalse(e.healthUseDistance)
        assertFalse(e.healthUseRestfulSleep)
        assertEquals("Zü", e.altclockName)
        assertEquals(v5, neuKodiert(v5))
    }

    @Test
    fun v6FalscheLaengeIstDieVorgabe() {
        val v1Bytes = bytes(v1)
        assertEquals(TsBedrockEinstellungen.VORGABE, dekodieren(v1Bytes.copyOf(31)))
        assertEquals(TsBedrockEinstellungen.VORGABE, dekodieren(v1Bytes + byteArrayOf(0)))
        assertEquals(TsBedrockEinstellungen.VORGABE, dekodieren(ByteArray(0)))
    }

    @Test
    fun vorgabeIstV0UndStehtGleichInLibpebble3() {
        assertEquals(v0, TsBedrockEinstellungen.VORGABE.kodieren().alsHex())
        assertEquals(v0, TsBedrockEinstellungen.VORGABE.alsHex())
        assertEquals(v0, BytesWatchPref.TimeStyleBedrock.defaultValue)
    }

    @Test
    fun kodierenLiefertImmer32Byte() {
        val faelle = listOf(
            TsBedrockEinstellungen.VORGABE,
            dekodieren(bytes(v1)),
            // Ungueltiges von Hand: alles faellt auf gueltige Werte, die Laenge bleibt.
            TsBedrockEinstellungen.VORGABE.copy(
                widgets = listOf(1, 99), languageId = 9, hourlyVibe = 7, altclockOffset = 40,
                decimalSeparator = 'x', altclockName = "ÄÖÜÉÈ", timeColor = 0x00,
            ),
        )
        faelle.forEach { assertEquals(32, it.kodieren().size) }
        val kaputt = dekodieren(faelle[2].kodieren())
        assertEquals(listOf(7, 10, 4), kaputt.widgets)
        assertEquals(0, kaputt.languageId)
        assertEquals(0, kaputt.hourlyVibe)
        assertEquals(0, kaputt.altclockOffset)
        assertEquals('.', kaputt.decimalSeparator)
        assertEquals(0xC0, kaputt.timeColor)
        // Drei Umlaute = 6 Byte; der vierte passte nicht mehr in die 7 Byte.
        assertEquals("ÄÖÜ", kaputt.altclockName)
    }

    @Test
    fun ersatzzeichenGehenNichtZurUhr() {
        // Kaputtes UTF-8 von der Uhr: Boulder zeigt Ersatzzeichen, schreibt sie aber nicht zurueck.
        val b = bytes(v0)
        b[16] = 0xC3.toByte()
        b[17] = 0x41
        val e = dekodieren(b)
        assertTrue('�' in e.altclockName)
        assertTrue(dekodieren(e.kodieren()).altclockName.all(::tsZeichenErlaubt))
    }

    @Test
    fun sprachliste() {
        assertEquals(29, TS_SPRACHEN.size)
        assertEquals(0 to "English", TS_SPRACHEN.first())
        assertEquals(TS_SPRACHEN.drop(1).sortedBy { it.second }, TS_SPRACHEN.drop(1))
        val erlaubt = (0..33).toSet() - setOf(9, 13, 17, 29, 31)
        for (id in 0..255) assertEquals(id in erlaubt, tsSpracheErlaubt(id), "Sprache $id")
        assertEquals(TS_SPRACHEN.map { it.first }.toSet(), erlaubt)
    }

    @Test
    fun widgetliste() {
        assertEquals(listOf(0, 4, 7, 8, 13, 10, 9, 12, 2, 3, 5, 6, 11), TS_WIDGETS)
        assertFalse(TsWidget.BLUETOOTH in TS_WIDGETS)
    }

    @Test
    fun namensfilter() {
        assertEquals("ÄBC1", tsNameEingabe("ÄBC1"))
        assertNull(tsNameEingabe("ΑΒ"))
        assertEquals("ABCD", tsNameEingabe("ABCDE"))
        assertEquals("", tsNameEingabe(""))
        // Vier Zeichen zu je 2 Byte passen nicht in 7 Byte: das letzte faellt weg.
        assertEquals("ÄÖÜ", tsNameEingabe("ÄÖÜÉ"))
    }

    @Test
    fun ausHex() {
        assertEquals(TsBedrockEinstellungen.VORGABE, tsAusHex(null))
        assertEquals(TsBedrockEinstellungen.VORGABE, tsAusHex("kein hex"))
        assertEquals(dekodieren(bytes(v1)), tsAusHex(v1.uppercase()))
    }
}
