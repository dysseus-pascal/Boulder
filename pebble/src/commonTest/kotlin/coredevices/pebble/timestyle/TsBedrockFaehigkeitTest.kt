package coredevices.pebble.timestyle

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TsBedrockFaehigkeitTest {
    @Test
    fun uvDerStundeAbB24() {
        // b23 zeigte den UV-Wert des Tages, ab b24 den der Stunde. Unbekannt ist wie
        // ueberall NICHT faehig (bis zur Nacharbeit galt es hier als "neueste").
        assertFalse(tsUvJeStunde(22))
        assertFalse(tsUvJeStunde(23))
        assertTrue(tsUvJeStunde(24))
        assertTrue(tsUvJeStunde(25))
        assertFalse(tsUvJeStunde(null))
    }

    @Test
    fun regelJeBuild() {
        // (faehig, Kachel, Settings, Schriften). UNBEKANNT (kein -bN: offizielle Firmware oder
        // Entwicklungsbau) IST NICHT FAEHIG - bis 0.48 galt es als faehig, so lief der Emulator
        // nie durch die Sperren.
        fun vier(build: Int?) = tsBedrockFaehigkeit(build).let { listOf(it.faehig, it.kachel, it.einstellungen, it.schriften) }
        assertEquals(listOf(false, false, false, false), vier(null))
        assertEquals(listOf(false, false, false, false), vier(21))
        assertEquals(listOf(true, true, false, false), vier(22))
        assertEquals(listOf(true, true, true, false), vier(23))
        assertEquals(listOf(true, true, true, true), vier(24))
        assertEquals(listOf(true, true, true, true), vier(31))
        // Die Grenzen mit ihrem Wert festgenagelt.
        assertEquals(22, TS_BEDROCK_AB_BUILD)
        assertEquals(23, TS_BEDROCK_EINSTELLUNGEN_AB_BUILD)
        assertEquals(24, TS_BEDROCK_SCHRIFTEN_AB_BUILD)
    }

    @Test
    fun buildAusDerFassung() {
        assertEquals(23, tsFirmwareBuild("v4.38.4-b23"))
        assertEquals(22, tsFirmwareBuild("v4.38.4-b22-3-gabcdef12"))
        // Ohne "-bN" (Entwicklungsbau, offizielle Fassung) und Unlesbares: unbekannt.
        assertNull(tsFirmwareBuild("v4.38.4"))
        assertNull(tsFirmwareBuild("v4.38.4-5-gabcdef12"))
        assertNull(tsFirmwareBuild("kaputt"))
        assertNull(tsFirmwareBuild(null))
    }

    private class Probe(gespeichert: String?, val gesehen: MutableMap<String, String> = mutableMapOf()) {
        val gesetzt = mutableListOf<String>()
        val neu = TsBedrockNeusetzen(
            lies = { gesehen[it] },
            schreibe = { serie, build -> gesehen[serie] = build },
            gespeicherterWert = { gespeichert },
            setze = { gesetzt += it },
        )
    }

    @Test
    fun nachUpdateVon22Auf23EinmalNeuGesetzt() = runTest {
        val p = Probe("01aa")
        assertFalse(p.neu.verbunden("S1", "v4.38.4-b22"))
        assertTrue(p.neu.verbunden("S1", "v4.38.4-b23"))
        assertEquals(listOf("01aa"), p.gesetzt)
        // Jede weitere Verbindung mit b23 (oder hoeher) setzt nichts mehr.
        assertFalse(p.neu.verbunden("S1", "v4.38.4-b23"))
        assertFalse(p.neu.verbunden("S1", "v4.38.4-b24"))
        assertEquals(1, p.gesetzt.size)
    }

    @Test
    fun von23Auf23Nicht() = runTest {
        val p = Probe("01aa", mutableMapOf("S1" to "23"))
        assertFalse(p.neu.verbunden("S1", "v4.38.4-b23"))
        assertEquals(emptyList(), p.gesetzt)
    }

    @Test
    fun nieGesehenGiltAlsAlt() = runTest {
        val p = Probe("01aa")
        assertTrue(p.neu.verbunden("S1", "v4.38.4-b23"))
        assertEquals(listOf("01aa"), p.gesetzt)
    }

    @Test
    fun ohneGespeichertenWertNichts() = runTest {
        val p = Probe(null, mutableMapOf("S1" to "22"))
        assertFalse(p.neu.verbunden("S1", "v4.38.4-b23"))
        assertEquals(emptyList(), p.gesetzt)
        // Der Build ist trotzdem vermerkt: ein spaeter gesetzter Wert geht ganz normal zur Uhr.
        assertEquals("23", p.gesehen["S1"])
    }

    @Test
    fun jeSeriennummerGetrennt() = runTest {
        val p = Probe("01aa", mutableMapOf("S1" to "23", "S2" to "22"))
        assertFalse(p.neu.verbunden("S1", "v4.38.4-b23"))
        assertTrue(p.neu.verbunden("S2", "v4.38.4-b23"))
        assertEquals(1, p.gesetzt.size)
    }

    @Test
    fun entwicklungsbauGiltNichtAlsFaehig() = runTest {
        val p = Probe("01aa", mutableMapOf("S1" to "22"))
        // Ohne -bN weiss Boulder nicht, ob die Firmware tsBedrock kennt: nichts senden.
        assertFalse(p.neu.verbunden("S1", "v4.38.4-3-gabcdef12"))
        assertEquals("0", p.gesehen["S1"])
        assertFalse(p.neu.verbunden("S1", "v4.38.4-3-gabcdef12"))
        assertEquals(0, p.gesetzt.size)
        // Danach eine Firmware mit -b23: einmal neu setzen.
        assertTrue(p.neu.verbunden("S1", "v4.38.4-b23"))
        assertEquals(1, p.gesetzt.size)
    }

    @Test
    fun von23Auf24NichtNeu() = runTest {
        // b23 hat den ganzen Satz gespeichert (auch Schrift und Sprache), b24 liest ihn nur
        // anders: kein neuer Zeitstempel noetig.
        val p = Probe("01aa", mutableMapOf("S1" to "23"))
        assertFalse(p.neu.verbunden("S1", "v4.38.4-b24"))
        assertEquals(emptyList(), p.gesetzt)
    }

    // Spezifikation 2.6: "zuletzt gesehener Build < 23 ODER UNBEKANNT" - die
    // offizielle Firmware ohne -bN hat tsBedrock abgewiesen, der Eintrag gilt
    // aber als synchron. Nach dem Wechsel auf Boulder b23 muss er neu raus.
    @Test
    fun vonOffiziellerFirmwareAuf23EinmalNeuGesetzt() = runTest {
        val p = Probe("01aa")
        assertFalse(p.neu.verbunden("S1", "v4.4.0"))
        assertEquals("0", p.gesehen["S1"])
        assertTrue(p.neu.verbunden("S1", "v4.38.4-b23"))
        assertEquals(listOf("01aa"), p.gesetzt)
        assertFalse(p.neu.verbunden("S1", "v4.38.4-b23"))
        assertEquals(1, p.gesetzt.size)
    }

    @Test
    fun unbekanntGespeichertDannB23() = runTest {
        val p = Probe("01aa", mutableMapOf("S1" to "0"))
        assertTrue(p.neu.verbunden("S1", "v4.38.4-b23"))
        assertEquals(listOf("01aa"), p.gesetzt)
        assertEquals("23", p.gesehen["S1"])
    }
}
