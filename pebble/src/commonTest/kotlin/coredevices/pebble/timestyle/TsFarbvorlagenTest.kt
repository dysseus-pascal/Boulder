package coredevices.pebble.timestyle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Die 77 Farbvorlagen des Originals und die eigenen Vorlagen (speichern, loeschen). */
class TsFarbvorlagenTest {
    @Test
    fun die77DesOriginals() {
        assertEquals(77, TS_FARBVORLAGEN.size)
        assertEquals(
            listOf("Neon" to 6, "Contrast" to 6, "Dark" to 6, "Solid" to 6, "Pastel" to 6, "User Submissions" to 47),
            TS_FARBVORLAGEN.groupBy { it.gruppe }.map { it.key to it.value.size },
        )
        // Stichproben woertlich aus config_theme_color.html (#rrggbb -> GColor8).
        // "Orange Dreams" hat keine Leistenschrift-Farbe: das Original nimmt Schwarz.
        assertEquals(TsFarbvorlage("Neon", "Orange Dreams", 0xf4, 0xc0, 0xf4, 0xc0), TS_FARBVORLAGEN.first())
        assertEquals(TsFarbvorlage("Neon", "Blue Screen", 0xcf, 0xc0, 0xcf, 0xc0), TS_FARBVORLAGEN[1])
        assertEquals(TsFarbvorlage("User Submissions", "Silver & Gold", 0xfe, 0xd5, 0xfe, 0xd5), TS_FARBVORLAGEN[74])
        assertEquals(TsFarbvorlage("User Submissions", "Pika Stripe", 0xf0, 0xfc, 0xd4, 0xff), TS_FARBVORLAGEN.last())
        // Jede Farbe ist eine der 64 (deckend).
        TS_FARBVORLAGEN.forEach { v ->
            listOf(v.zeit, v.hintergrund, v.leiste, v.leistentext).forEach { assertEquals(0xc0, it and 0xc0, v.name) }
        }
    }

    @Test
    fun vorlageSetztNurDieVierFarben() {
        val v = TS_FARBVORLAGEN.first()
        val vorher = TsBedrockEinstellungen.VORGABE.copy(languageId = 2, useLargeFonts = true)
        val nachher = vorher.mitVorlage(v)
        assertEquals(vorher.copy(timeColor = 0xf4, timeBgColor = 0xc0, sidebarColor = 0xf4, sidebarTextColor = 0xc0), nachher)
        assertTrue(nachher.alsVorlage().gleicheFarben(v))
    }

    @Test
    fun eigeneSpeichernUndLoeschen() {
        val a = TsBedrockEinstellungen.VORGABE.alsVorlage()
        val b = TsBedrockEinstellungen.VORGABE.mitVorlage(TS_FARBVORLAGEN[3]).alsVorlage()
        val liste = emptyList<TsFarbvorlage>().mitEigener(a).mitEigener(b)
        assertEquals(listOf(a, b), liste)
        // Dieselben Farben noch einmal: nicht doppelt.
        assertEquals(liste, liste.mitEigener(a))
        assertEquals(listOf(b), liste.ohneStelle(0))
        assertEquals(listOf(a), liste.ohneStelle(1))
    }

    @Test
    fun eigeneAlsTextHinUndZurueck() {
        val liste = listOf(TsBedrockEinstellungen.VORGABE.alsVorlage(), TS_FARBVORLAGEN.last().copy(gruppe = TS_EIGENE_GRUPPE, name = ""))
        val text = tsEigeneAlsText(liste)
        assertEquals("ffc0ffc0,f0fcd4ff", text)
        assertEquals(liste, tsEigeneAusText(text))
        assertEquals(emptyList(), tsEigeneAusText(null))
        assertEquals(emptyList(), tsEigeneAusText(""))
        // Ein kaputter Eintrag faellt weg, die anderen bleiben; Alpha wird deckend.
        assertEquals(liste.take(1), tsEigeneAusText("ffc0ffc0,zz,1234"))
        assertEquals(listOf(TsFarbvorlage(TS_EIGENE_GRUPPE, "", 0xff, 0xc0, 0xc0, 0xc0)), tsEigeneAusText("3f000000"))
    }
}
