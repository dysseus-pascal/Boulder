package io.rebble.libpebblecommon.connection.endpointmanager

import java.io.File

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Boulder: Waechter fuer die eine Zeile in `createCompanionApps`, die PebbleKit
 * JS neben einem sichernden Begleiter (Drinktervall, SupCycle) schweigen
 * laesst: `bestaetigt = pkjsBestaetigt(daneben)`. Stuende dort `true`,
 * bestaetigte das JS jede Nachricht beim Empfang - die Uhr gaebe Glas und
 * Kaffee auf, bevor Boulder sie gesichert hat.
 * PKJSApp braucht dort eine PBW mit JS und eine WebView, die kein Hosttest hat;
 * `pkjsBestaetigt` selbst und PKJSApp mit `bestaetigt = false` haben eigene
 * Tests, die Verdrahtung dazwischen nur diesen.
 *
 * Geprueft wird Text ohne Zeilenkommentare: der Begleiter daneben entsteht vor
 * PKJSApp, und dessen Aufruf reicht `pkjsBestaetigt(daneben)` weiter.
 */
class BegleiterBestaetigungWaechterTest {

    // Gradle startet die Tests im Modulordner (libpebble3).
    private val manager =
        File("src/commonMain/kotlin/io/rebble/libpebblecommon/connection/endpointmanager/CompanionAppLifecycleManager.kt")

    private val source by lazy { manager.readText() }

    private fun code(text: String) = text.lines().joinToString("\n") { it.substringBefore("//") }

    /** Der Text zwischen der Klammer nach [kopf] und der, die sie schliesst. */
    private fun inKlammern(text: String, kopf: Regex): String? {
        val start = (kopf.find(text) ?: return null).range.last
        var tiefe = 0
        for (i in start until text.length) {
            if (text[i] == '(') tiefe++
            if (text[i] == ')' && --tiefe == 0) return text.substring(start + 1, i)
        }
        return null
    }

    private val daneben = Regex("""val\s+daneben\s*=\s*builtInCompanions\s*\.\s*createBeside\(""")
    private val pkjsAufruf = Regex("""PKJSApp\(""")
    private val weitergereicht = Regex("""bestaetigt\s*=\s*pkjsBestaetigt\(\s*daneben\s*\)""")

    private val keinDaneben = "createCompanionApps erzeugt den Begleiter daneben nicht mehr vor PKJSApp"
    private val nichtWeitergereicht = "PKJSApp bekommt nicht mehr bestaetigt = pkjsBestaetigt(daneben)"

    /** Die Meldung jeder Pruefung, die [text] nicht besteht. */
    private fun probleme(text: String): List<String> = buildList {
        val code = code(text)
        val d = daneben.find(code)
        val p = pkjsAufruf.find(code)
        if (d == null || p == null || d.range.first > p.range.first) add(keinDaneben)
        val argumente = inKlammern(code, pkjsAufruf)
        if (argumente == null || !weitergereicht.containsMatchIn(argumente)) add(nichtWeitergereicht)
    }

    @Test
    fun pebblekitJsSchweigtNebenEinemSicherndenBegleiter() {
        assertEquals(emptyList(), probleme(source))
    }

    private fun String.geaendert(alt: String, neu: String): String {
        assertTrue(contains(alt), "der Manager enthaelt den Text nicht mehr, den diese Aenderung ersetzt: $alt")
        return replaceFirst(alt, neu)
    }

    @Test
    fun bekannteFehlerFallenAuf() {
        val zeile = "bestaetigt = pkjsBestaetigt(daneben),"
        val entstehen = "val daneben = builtInCompanions.createBeside(lockerEntry.id, device, pkjsRunning = pbw.hasPKJS)"
        val faelle = mapOf(
            "bestaetigt immer" to (nichtWeitergereicht to { t: String -> t.geaendert(zeile, "bestaetigt = true,") }),
            "Argument weg (Vorgabe true)" to (nichtWeitergereicht to { t: String -> t.geaendert(zeile, "") }),
            "nur auskommentiert" to (nichtWeitergereicht to { t: String -> t.geaendert(zeile, "// $zeile") }),
            "Begleiter erst danach" to (keinDaneben to { t: String ->
                t.geaendert(entstehen, "val danebenSpaeter = 0").geaendert("pkjsApp?.let { add(it) }", "pkjsApp?.let { add(it) }\n            $entstehen")
            }),
        )
        for ((fall, paar) in faelle) {
            val (erwartet, aendern) = paar
            assertTrue(erwartet in probleme(aendern(source)), "nicht bemerkt: $fall")
        }
    }
}
