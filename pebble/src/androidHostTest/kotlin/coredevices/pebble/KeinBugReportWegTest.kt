package coredevices.pebble

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Boulder: nichts ueber Boulders App oder Firmware geht an Core Devices, und kein Einstieg weist
 * auf Bug-Reports oder Absturzprotokolle hin. Cores Bug-Report-Seiten bleiben im Code, aber ohne
 * Einstieg: keine Navigation dorthin, kein Deep Link, kein Core Dump. Bringt ein Core-Merge einen
 * Einstieg zurueck, schlaegt dieser Test mit Datei und Zeile an.
 */
class KeinBugReportWegTest {
    private val wurzel = File("..").canonicalFile

    // Die Bug-Report-Seiten selbst verweisen aufeinander; ohne Einstieg erreicht sie niemand.
    private val seitenDerBugReports = setOf("BugReportScreen.kt", "BugReportsListScreen.kt", "ViewBugReportScreen.kt")

    private val muster = listOf(
        Regex("""navigateTo\(\s*CommonRoutes\.(BugReport|ViewMyBugReportsRoute|ViewBugReportRoute|TroubleshootingRoute)\b"""),
        Regex("""deep-link/(view-)?bug-report"""),
        Regex("""\.createCoreDump\("""),
    )

    /** Fundstellen in [text] als "Zeile: Treffer". */
    private fun funde(text: String): List<String> = muster.flatMap { m ->
        m.findAll(text).map { treffer ->
            val zeile = text.substring(0, treffer.range.first).count { it == '\n' } + 1
            "$zeile: ${treffer.value}"
        }.toList()
    }

    @Test
    fun keinEinstiegZuBugReportsOderCoreDumps() {
        val module = listOf("pebble", "composeApp", "util", "experimental", "androidApp")
        val gefunden = module.flatMap { modul ->
            File(wurzel, "$modul/src").walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name !in seitenDerBugReports }
                // Nur Produktivcode: Testquellen und iOS (unbetreut) zaehlen nicht.
                .filter { datei -> datei.path.split(File.separator).none { it.endsWith("Test") || it == "iosMain" } }
                .flatMap { datei -> funde(datei.readText()).map { "${datei.relativeTo(wurzel)}:$it" } }
                .toList()
        }
        assertEquals(emptyList(), gefunden)
    }

    @Test
    fun derWaechterErkenntDieEinstiegeVonCore() {
        // So standen sie in Core 1.15.0.1; jeder muss anschlagen.
        val core = listOf(
            "nav.navigateTo(\n    CommonRoutes.BugReport(\n        pebble = true,\n    )\n)",
            "nav.navigateTo(CommonRoutes.ViewMyBugReportsRoute)",
            "nav.navigateTo(CommonRoutes.TroubleshootingRoute)",
            "NavDeepLink(\"pebblecore://deep-link/bug-report?pebble={pebble}\")",
            "deepLink = \"pebblecore://deep-link/bug-report?pebble=false\"",
            "watch.createCoreDump()",
        )
        core.forEach { assertTrue(funde(it).isNotEmpty(), "nicht erkannt: $it") }
    }
}
