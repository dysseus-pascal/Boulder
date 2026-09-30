package coredevices.pebble.kiesel

/**
 * Wann Kieselstrasse an OsmAnd haengt und wie oft es nachfragt.
 *
 * AUF ABRUF: gebunden wird nur, solange die Uhr-App offen ist, und kurz fuer
 * eine Pruefung aus den Einstellungen. Frueher hing Boulder an OsmAnd, sobald
 * eine Uhr verbunden war, und fragte alle zwei Sekunden - auch mit der Uhr in
 * der Tasche und ohne Navigation. Ohne Android, damit es sich pruefen laesst;
 * die Zeit kommt immer von aussen.
 */
internal class StrassenAbruf(
    private val karenzMs: Long = KARENZ_MS,
    private val pruefDauerMs: Long = PRUEF_MS,
) {
    /** Was gerade sein soll: gebunden oder nicht, und wie oft nachfragen (null = gar nicht). */
    data class Soll(val binden: Boolean, val taktMs: Long?)

    var offen = false
        private set
    private var zuSeit: Long? = null
    private var pruefenBis: Long? = null
    private var navigiert = false
    private var leere = 0
    private var rueckrufe = 0

    /** Die Uhr-App ging auf oder zu. Rueckgabe true heisst: gerade geoeffnet. */
    fun uhrApp(offen: Boolean, jetzt: Long): Boolean {
        val geoeffnet = offen && !this.offen
        // Wer kurz in ein anderes Menue schaut, soll OsmAnd nicht neu anbinden muessen.
        if (this.offen && !offen) zuSeit = jetzt
        if (offen) zuSeit = null
        this.offen = offen
        return geoeffnet
    }

    /** Die Einstellungen wollen den Zustand wissen: kurz binden, bis [geprueft]. */
    fun pruefe(jetzt: Long) {
        pruefenBis = jetzt + pruefDauerMs
    }

    fun geprueft() {
        pruefenBis = null
    }

    /** Rueckgabe true, wenn sich damit der Takt aendert. */
    fun navigation(laeuft: Boolean): Boolean {
        leere = 0
        if (navigiert == laeuft) return false
        navigiert = laeuft
        return true
    }

    /**
     * OsmAnd nannte keine Abzweigung. Rueckgabe true erst, wenn damit eine
     * laufende Navigation endet - nach [LEER_BIS_ENDE] leeren Antworten in
     * Folge; bis dahin bleibt der schnelle Takt.
     */
    fun leereAntwort(): Boolean {
        if (!navigiert) return false
        if (++leere < LEER_BIS_ENDE) return false
        return navigation(false)
    }

    /** Rueckgabe true beim ersten Rueckruf einer Bindung - der Beweis, dass er ankommt. */
    fun rueckruf(): Boolean = ++rueckrufe == 1

    val anzahlRueckrufe: Int get() = rueckrufe

    /** Die Bindung ist weg: was sie ueber OsmAnd wusste, gilt nicht mehr. */
    fun geloest() {
        navigiert = false
        leere = 0
        rueckrufe = 0
        pruefenBis = null
    }

    fun soll(jetzt: Long): Soll {
        val binden = offen ||
            zuSeit?.let { jetzt - it < karenzMs } == true ||
            pruefenBis?.let { jetzt < it } == true
        val takt = when {
            !offen -> null
            navigiert -> ABFRAGE_MS
            // Kommt der Rueckruf, meldet er den Beginn einer Navigation selbst.
            // In Kiesel-Helper kam er nie; dann muss ein langsamer Blick genuegen.
            rueckrufe > 0 -> null
            else -> LAUERN_MS
        }
        return Soll(binden, takt)
    }

    /** Wann sich [soll] von selbst aendert (Ende der Karenz oder der Pruefung), sonst null. */
    fun naechsteWende(jetzt: Long): Long? =
        listOfNotNull(zuSeit?.plus(karenzMs), pruefenBis).filter { it > jetzt }.minOrNull()

    companion object {
        const val KARENZ_MS = 30_000L
        const val PRUEF_MS = 15_000L
        const val ABFRAGE_MS = 2_000L
        const val LAUERN_MS = 10_000L
        const val LEER_BIS_ENDE = 3
    }
}
