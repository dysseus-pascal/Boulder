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
 */
internal object Riegel {
    private const val DATEI = "kiesel-riegel"

    /** true heisst: noch nicht dagewesen, jetzt vormerken. */
    fun neu(context: Context, aufgabe: String, merkmal: String): Boolean {
        val p = context.getSharedPreferences(DATEI, Context.MODE_PRIVATE)
        if (p.getString(aufgabe, null) == merkmal) return false
        p.edit().putString(aufgabe, merkmal).apply()
        return true
    }

    /** Den Vormerk zuruecknehmen - wenn das Eintragen danach scheiterte. */
    fun loese(context: Context, aufgabe: String) {
        context.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit().remove(aufgabe).apply()
    }
}
