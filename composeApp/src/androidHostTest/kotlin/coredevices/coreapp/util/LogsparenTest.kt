package coredevices.coreapp.util

import co.touchlab.kermit.Severity
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals

/** Boulder: die Mindeststufe im Release und der Bytezaehler der Logdatei. */
class LogsparenTest {

    @Test
    fun release_erst_ab_info_debug_und_diagnose_alles() {
        assertEquals(Severity.Info, logMindeststufe(debugBuild = false, ausfuehrlich = false))
        assertEquals(Severity.Verbose, logMindeststufe(debugBuild = true, ausfuehrlich = false))
        assertEquals(Severity.Verbose, logMindeststufe(debugBuild = false, ausfuehrlich = true))
    }

    /**
     * Der Zaehler ersetzt stat(): er muss genau die Bytes zaehlen, die append() schreibt -
     * auch fuer Umlaute, Zeichen ausserhalb der BMP und ein einzelnes Surrogat aus einer
     * kaputten Meldung.
     */
    @Test
    fun zaehler_folgt_der_dateigroesse() {
        val zeilen = listOf(
            "2026-10-03T12:00:00Z [I] Trends: 4'986 Schritte\n",
            "Grüße, Kaffee ☕ für 3,50 €\n",
            "Uhr 😀 verbunden\n",
            "kaputt \uD800 halb\n",
            RuntimeException("Fehler mit ä").stackTraceToString() + "\n",
        )
        val datei = Buffer()
        var gezaehlt = 0L
        zeilen.forEach { gezaehlt += datei.appendGezaehlt(it) }
        assertEquals(datei.size, gezaehlt)
    }
}
