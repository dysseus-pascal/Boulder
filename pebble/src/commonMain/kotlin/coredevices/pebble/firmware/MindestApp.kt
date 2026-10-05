package coredevices.pebble.firmware

/**
 * Boulder: die Zeile "Mindest-App: X.Y.Z" in den Notizen eines Firmware-Releases.
 *
 * Eine Firmware, die etwas schickt, das erst eine neuere App lesen kann (etwa
 * neue Datalogging-Tags), nennt dort die kleinste passende Boulder-Fassung.
 * Eine aeltere App bietet sie nicht an: die Uhr gaebe die Daten nach dem ACK frei.
 */
internal object MindestApp {
    // Genau so, ganze Zeile: ein Tippfehler setzt keine Grenze, statt eine falsche.
    private val ZEILE = Regex("""^Mindest-App: (\d+\.\d+\.\d+)$""", RegexOption.MULTILINE)
    private val FASSUNG = Regex("""^(\d+)\.(\d+)\.(\d+)""")

    /** Die verlangte Fassung, oder null: dann gilt keine Grenze. */
    fun aus(notizen: String?): String? = notizen?.let { ZEILE.find(it)?.groupValues?.get(1) }

    /**
     * Genuegt die App [app] der Grenze [mindest]? Verglichen wird Zahl fuer Zahl
     * (0.10.0 ist neuer als 0.9.0). Laesst sich [app] nicht lesen, nein: lieber
     * kein Angebot als verlorene Daten.
     */
    fun erfuellt(app: String, mindest: String): Boolean {
        val hat = zahlen(app) ?: return false
        val braucht = zahlen(mindest) ?: return true
        return compareValuesBy(hat, braucht, { it[0] }, { it[1] }, { it[2] }) >= 0
    }

    private fun zahlen(fassung: String): List<Int>? =
        FASSUNG.find(fassung.trim())?.groupValues?.drop(1)?.map { it.toIntOrNull() ?: return null }
}
