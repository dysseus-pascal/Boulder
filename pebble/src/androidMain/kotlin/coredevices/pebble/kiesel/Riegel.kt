package coredevices.pebble.kiesel

import android.content.Context

/**
 * Der Riegel gegen Doppeleintraege - wie in Kiesel-Helper.
 *
 * Die Uhr schickt eine Zusammenfassung so lange, bis das Telefon sie
 * bestaetigt; kommt die Bestaetigung zu spaet an, kommt sie ein zweites Mal.
 * Die Akte fuehrt denselben Eintrag nur zusammen, wenn er aus derselben App
 * mit derselben Kennung kommt - das tut er -, aber das Protokoll zeigte ihn
 * doppelt. Gemerkt wird je Aufgabe EIN Merkmal, das zuletzt verarbeitete.
 *
 * ERST PRUEFEN, NACH DEM EINTRAG MERKEN ([schonDa], [merke]): stand der Riegel
 * schon vor dem Eintrag, galt ein Training nach einem Absturz dazwischen als
 * erledigt, und seine Datei wurde geloescht. Ein Absturz nach dem Eintrag
 * kostet jetzt hoechstens eine zweite Protokollzeile - die Akte ersetzt den
 * Satz ueber seine Kennung.
 */
internal object Riegel {
    private const val DATEI = "kiesel-riegel"

    /** Was nach einem gelungenen Eintrag vorgemerkt wird. */
    data class Marke(val aufgabe: String, val merkmal: String)

    /** Ob [merkmal] fuer [aufgabe] schon eingetragen ist. Setzt nichts. */
    fun schonDa(context: Context, aufgabe: String, merkmal: String): Boolean =
        context.getSharedPreferences(DATEI, Context.MODE_PRIVATE).getString(aufgabe, null) == merkmal

    /** Vormerken - erst, wenn die Akte den Eintrag genommen hat. */
    fun merke(context: Context, marke: Marke) {
        context.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit().putString(marke.aufgabe, marke.merkmal).apply()
    }

    /** true heisst: noch nicht dagewesen, jetzt vormerken. Nur noch fuer SupCycle. */
    fun neu(context: Context, aufgabe: String, merkmal: String): Boolean {
        if (schonDa(context, aufgabe, merkmal)) return false
        merke(context, Marke(aufgabe, merkmal))
        return true
    }

    /** Den Vormerk zuruecknehmen - wenn das Eintragen danach scheiterte. */
    fun loese(context: Context, aufgabe: String) {
        context.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit().remove(aufgabe).apply()
    }
}
