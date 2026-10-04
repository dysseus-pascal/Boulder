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
    private val v2NeuKodiert = "01f0c0ffcc090000070a04002e00000041414141414141000000000000000000"
    private val v3 = "00".repeat(32)
    private val v4 = "02ffc0ffc0000100070a04002e5000ff414c540000000000ffffffffffffffff"
    private val v5 = "01c0ffc0ff01010100020c0e2e0000005ac3bc00000000000000000000000000"
    // Ab b24 (Firmware test_timestyle_einstellungen.c V7/V8): Sprache 37 und Schrift 5 gibt
    // es nicht (Vorgabe), Hebraeisch (36) und "Bold Hour" (3) schon.
    private val v7 = "01ffc0ffc0250500070a04002e500000414c5400000000000000000000000000"
    private val v8 = "01ffc0ffc0240300070a04002e500000414c5400000000000000000000000000"

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
        // Griechisch (9) und "Regular" (0) sind ab b24 gueltig.
        assertEquals(9, e.languageId)
        assertEquals(0, e.clockFontId)
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
                widgets = listOf(1, 99), languageId = 37, clockFontId = 5, hourlyVibe = 7, altclockOffset = 40,
                decimalSeparator = 'x', altclockName = "ÄÖÜÉÈ", timeColor = 0x00,
            ),
        )
        faelle.forEach { assertEquals(32, it.kodieren().size) }
        val kaputt = dekodieren(faelle[2].kodieren())
        assertEquals(listOf(7, 10, 4), kaputt.widgets)
        assertEquals(0, kaputt.languageId)
        assertEquals(1, kaputt.clockFontId)
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
    fun v7SpracheUndSchriftAusserhalb() {
        assertEquals(TsBedrockEinstellungen.VORGABE, dekodieren(bytes(v7)))
        assertEquals(v0, neuKodiert(v7))
    }

    @Test
    fun v8HebraeischFetteStunde() {
        val e = dekodieren(bytes(v8))
        assertEquals(TsBedrockEinstellungen.VORGABE.copy(languageId = 36, clockFontId = 3), e)
        assertEquals(v8, neuKodiert(v8))
    }

    /** V0 mit Byte 13 = [a] und Byte 14 = [b]. */
    private fun mitFlags(a: Int, b: Int) = dekodieren(bytes(v0).also { it[13] = a.toByte(); it[14] = b.toByte() })

    /** Die Vorgabe ohne die zwei Bits, die V0 in Byte 13 setzt (4 und 6). */
    private val ohneFlags = TsBedrockEinstellungen.VORGABE.copy(activateDisconnectIcon = false, showBatteryPct = false)

    // Walking One: je genau ein Bit, jedes mit seinem eigenen ausgeschriebenen Feld - so faellt
    // auch ein Vertauschen zweier Bits auf (dieselben Faelle prueft die Firmware).
    @Test
    fun flagsAEinzeln() {
        assertEquals(ohneFlags, mitFlags(0x00, 0))
        assertEquals(ohneFlags.copy(showLeadingZero = true), mitFlags(0x01, 0))
        assertEquals(ohneFlags.copy(btVibe = true), mitFlags(0x02, 0))
        assertEquals(ohneFlags.copy(sidebarOnLeft = true), mitFlags(0x04, 0))
        assertEquals(ohneFlags.copy(useLargeFonts = true), mitFlags(0x08, 0))
        assertEquals(ohneFlags.copy(activateDisconnectIcon = true), mitFlags(0x10, 0))
        // Bit 5 ist frei (frueher useMetric).
        assertEquals(ohneFlags, mitFlags(0x20, 0))
        assertEquals(ohneFlags.copy(showBatteryPct = true), mitFlags(0x40, 0))
        assertEquals(ohneFlags.copy(disableAutobattery = true), mitFlags(0x80, 0))
    }

    @Test
    fun flagsBEinzeln() {
        assertEquals(ohneFlags.copy(healthUseDistance = true), mitFlags(0, 0x01))
        assertEquals(ohneFlags.copy(healthUseRestfulSleep = true), mitFlags(0, 0x02))
        for (bit in 2..7) assertEquals(ohneFlags, mitFlags(0, 1 shl bit), "freies Bit $bit")
    }

    @Test
    fun flagsSchreibenJedesBitEinzeln() {
        // Die Gegenrichtung: jedes Feld allein ergibt genau sein Bit.
        fun ab(e: TsBedrockEinstellungen) = e.kodieren().let { (it[13].toInt() and 0xff) to (it[14].toInt() and 0xff) }
        assertEquals(0x00 to 0x00, ab(ohneFlags))
        assertEquals(0x01 to 0x00, ab(ohneFlags.copy(showLeadingZero = true)))
        assertEquals(0x02 to 0x00, ab(ohneFlags.copy(btVibe = true)))
        assertEquals(0x04 to 0x00, ab(ohneFlags.copy(sidebarOnLeft = true)))
        assertEquals(0x08 to 0x00, ab(ohneFlags.copy(useLargeFonts = true)))
        assertEquals(0x10 to 0x00, ab(ohneFlags.copy(activateDisconnectIcon = true)))
        assertEquals(0x40 to 0x00, ab(ohneFlags.copy(showBatteryPct = true)))
        assertEquals(0x80 to 0x00, ab(ohneFlags.copy(disableAutobattery = true)))
        assertEquals(0x00 to 0x01, ab(ohneFlags.copy(healthUseDistance = true)))
        assertEquals(0x00 to 0x02, ab(ohneFlags.copy(healthUseRestfulSleep = true)))
    }

    @Test
    fun schriftenWieDasOriginal() {
        for (id in 0..255) {
            val b = bytes(v0).also { it[6] = id.toByte() }
            assertEquals(if (id <= 4) id else 1, dekodieren(b).clockFontId, "Schrift $id")
        }
        assertEquals(1, TsBedrockEinstellungen.VORGABE.clockFontId)
    }

    @Test
    fun sprachliste() {
        assertEquals(37, TS_SPRACHEN.size)
        assertEquals(0 to "English", TS_SPRACHEN.first())
        assertEquals(TS_SPRACHEN.drop(1).sortedBy { it.second }, TS_SPRACHEN.drop(1))
        for (id in 0..255) assertEquals(id <= 36, tsSpracheErlaubt(id), "Sprache $id")
        assertEquals((0..36).toSet(), TS_SPRACHEN.map { it.first }.toSet())
        // Die acht mit Sprachpaket (Hinweis "language pack" im Original) und die 29 bis b23.
        assertEquals(setOf(9, 13, 17, 29, 31, 34, 35, 36), TS_SPRACHPAKET.keys)
        assertEquals(29, TS_SPRACHEN_BIS_B23.size)
        assertEquals((0..36).toSet() - setOf(9, 13, 17, 29, 31, 34, 35, 36), TS_SPRACHEN_BIS_B23)
    }

    @Test
    fun sprachpaketNurWennNoetig() {
        // Ohne Paket: nur die acht brauchen eines.
        assertTrue(tsBrauchtSprachpaket(17, null))
        assertFalse(tsBrauchtSprachpaket(2, null))
        assertFalse(tsBrauchtSprachpaket(0, "ru_RU"))
        // Das passende Paket ist da.
        assertFalse(tsBrauchtSprachpaket(17, "ru_RU"))
        assertFalse(tsBrauchtSprachpaket(17, "bg_BG"))
        assertFalse(tsBrauchtSprachpaket(29, "zh_CN"))
        assertFalse(tsBrauchtSprachpaket(29, "en_TW"))
        assertFalse(tsBrauchtSprachpaket(36, "he_IL"))
        assertFalse(tsBrauchtSprachpaket(9, "el"))
        // Ein anderes Paket hilft nicht.
        assertTrue(tsBrauchtSprachpaket(17, "de_DE"))
        assertTrue(tsBrauchtSprachpaket(31, "ru_RU"))
        assertTrue(tsBrauchtSprachpaket(34, "ko_KR"))
        assertTrue(tsBrauchtSprachpaket(29, "en_US"))
        // Die Kennung kommt aus dem Paket: Bindestrich und Gross/Klein zaehlen nicht.
        assertFalse(tsBrauchtSprachpaket(17, "ru-RU"))
        assertFalse(tsBrauchtSprachpaket(17, "RU_ru"))
        assertFalse(tsBrauchtSprachpaket(29, "en-CN"))
        assertFalse(tsBrauchtSprachpaket(35, "ko"))
        // Nur Sprache oder Sprache plus Land: Konkani ("kok") ist kein Koreanisch,
        // "ukr" kein "uk", und ein leerer Name ist kein Paket fuer irgendwas.
        assertTrue(tsBrauchtSprachpaket(35, "kok_IN"))
        assertTrue(tsBrauchtSprachpaket(31, "ukr"))
        assertTrue(tsBrauchtSprachpaket(9, ""))
    }

    @Test
    fun keinWidgetDoppelt() {
        val v = TsBedrockEinstellungen.VORGABE // Wetter, Schritte, Datum
        // Wie das Original: der alte Platz wird leer.
        assertEquals(listOf(TsWidget.KEINS, TsWidget.WETTER, TsWidget.DATUM), v.mitWidget(1, TsWidget.WETTER).widgets)
        assertEquals(listOf(TsWidget.WETTER, TsWidget.KEINS, TsWidget.SCHRITTE), v.mitWidget(2, TsWidget.SCHRITTE).widgets)
        // Ein neues Widget verdraengt nichts.
        assertEquals(listOf(TsWidget.UV, TsWidget.SCHRITTE, TsWidget.DATUM), v.mitWidget(0, TsWidget.UV).widgets)
        // "Keins" darf mehrfach stehen.
        val leer = v.mitWidget(0, TsWidget.KEINS).mitWidget(1, TsWidget.KEINS)
        assertEquals(listOf(TsWidget.KEINS, TsWidget.KEINS, TsWidget.DATUM), leer.widgets)
        // Sonst bleibt alles gleich.
        assertEquals(v.copy(widgets = listOf(TsWidget.KEINS, TsWidget.WETTER, TsWidget.DATUM)), v.mitWidget(1, TsWidget.WETTER))
    }

    @Test
    fun wasDieUhrZeigt() {
        val gewaehlt = TsBedrockEinstellungen.VORGABE.copy(clockFontId = 2, languageId = 17)
        // b24 und neuer: wie gewaehlt.
        assertEquals(gewaehlt, gewaehlt.wieDieUhr(24))
        assertEquals(gewaehlt, gewaehlt.wieDieUhr(25))
        // b23: immer LECO, die acht Sprachen mit Paket werden Englisch, die anderen bleiben.
        assertEquals(gewaehlt.copy(clockFontId = 1, languageId = 0), gewaehlt.wieDieUhr(23))
        assertEquals(gewaehlt.copy(clockFontId = 1, languageId = 2), gewaehlt.copy(languageId = 2).wieDieUhr(23))
        assertEquals(24, TS_BEDROCK_SCHRIFTEN_AB_BUILD)
        // b22 und b21 nehmen keine Einstellungen an: die feste Einstellung von b22
        // (settings.c: weiss auf schwarz, Wetter/Schritte/Datum, LECO, Englisch).
        val bunt = gewaehlt.copy(timeColor = 0xF4, sidebarOnLeft = true, widgets = listOf(TsWidget.UV, TsWidget.KEINS, TsWidget.AKKU))
        assertEquals(TsBedrockEinstellungen.VORGABE, bunt.wieDieUhr(22))
        assertEquals(TsBedrockEinstellungen.VORGABE, bunt.wieDieUhr(21))
        // Unbekannte Firmware (kein -bN) nimmt wie ueberall keine Einstellungen an -
        // die Kachel zeigt dann nicht Schrift und Sprache, die sie gar nicht zeichnet.
        assertEquals(TsBedrockEinstellungen.VORGABE, bunt.wieDieUhr(null))
        assertEquals(TsBedrockEinstellungen.VORGABE, gewaehlt.wieDieUhr(null))
        assertEquals(1, TsBedrockEinstellungen.VORGABE.clockFontId)
        assertEquals(listOf(TsWidget.WETTER, TsWidget.SCHRITTE, TsWidget.DATUM), TsBedrockEinstellungen.VORGABE.widgets)
        assertEquals(listOf(0xFF, 0xC0, 0xFF, 0xC0), TsBedrockEinstellungen.VORGABE.let {
            listOf(it.timeColor, it.timeBgColor, it.sidebarColor, it.sidebarTextColor)
        })
        assertEquals(23, TS_BEDROCK_EINSTELLUNGEN_AB_BUILD)
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
