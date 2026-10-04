package coredevices.pebble.timestyle

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Die Labels der Einstellungsseite (timestyle_strings.xml). WARUM ein eigener
 * Test: die Build-Grenze stand einmal als Konstante und einmal fest im Text
 * ("Needs firmware b24") - wer nur die Konstante nachzieht, zeigt eine falsche
 * Nummer. Jetzt kommt sie nur noch aus TS_BEDROCK_SCHRIFTEN_AB_BUILD.
 */
class TsBeschriftungTest {
    private val texte: Map<String, String> by lazy {
        val datei = File("src/androidMain/res/values/timestyle_strings.xml")
        val dok = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(datei)
        val liste = dok.getElementsByTagName("string")
        (0 until liste.length).associate { i ->
            val e = liste.item(i)
            e.attributes.getNamedItem("name").nodeValue to e.textContent
        }
    }

    @Test
    fun firmwareHinweisAusDerKonstante() {
        val muster = texte.getValue("ts_ab_build")
        assertEquals("Needs firmware b%1\$d", muster)
        // So wie stringResource(R.string.ts_ab_build, TS_BEDROCK_SCHRIFTEN_AB_BUILD) es formatiert.
        assertEquals("Needs firmware b24", String.format(muster, TS_BEDROCK_SCHRIFTEN_AB_BUILD))
    }

    @Test
    fun keineFesteBuildNummer() {
        // Kein Label nennt einen Build woertlich ("b23", "b24" ...).
        val fest = texte.filterValues { Regex("""\bb\d+\b""").containsMatchIn(it) }
        assertTrue(fest.isEmpty(), "feste Build-Nummer in $fest")
    }
}
