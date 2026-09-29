package coredevices.pebble.kiesel

import android.content.Context

/**
 * Die Einstellungen von Kieselsport, wie die Uhr sie meldet.
 *
 * DIE UHR IST DIE EINE STELLE, AN DER SIE GELTEN. Sie meldet sie beim Start
 * der App und nach jeder Aenderung; hier werden sie nur gemerkt - fuer die
 * Pulszonen der Trainingsansicht und die Einstellungsseite, die sie spaeter
 * zeigt und aendert.
 */
internal object KieselEinstellungen {
    private const val DATEI = "kiesel-uhr"

    data class Stand(
        val maxpuls: Int = 190,
        val pause: Int = 90,
        val empfind: Int = 2,
        val becken: Int = 25,
        val pinArt: Int = 0,
        val pinZeit: String = "18:00",
        val gemeldetUm: Long = 0,
    )

    /** true, wenn es eine Einstellungsmeldung war (erkennbar am Maximalpuls). */
    fun vonDerUhr(context: Context, felder: Map<Int, Long>, texte: Map<Int, String>): Boolean {
        val maxpuls = felder[Kieselsport.SP_MAXPULS] ?: return false
        context.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit()
            .putInt("maxpuls", maxpuls.toInt())
            .putInt("pause", (felder[Kieselsport.SP_PAUSENZIEL] ?: 90).toInt())
            .putInt("empfind", (felder[Kieselsport.SP_EMPFIND] ?: 2).toInt())
            .putInt("becken", (felder[Kieselsport.SP_BECKEN] ?: 25).toInt())
            .putInt("pin_art", (felder[Kieselsport.SP_PIN_ART] ?: 0).toInt())
            .putString("pin_zeit", texte[Kieselsport.SP_PIN_ZEIT] ?: "18:00")
            .putLong("um", System.currentTimeMillis())
            .apply()
        return true
    }

    fun stand(context: Context): Stand {
        val p = context.getSharedPreferences(DATEI, Context.MODE_PRIVATE)
        return Stand(
            maxpuls = p.getInt("maxpuls", 190),
            pause = p.getInt("pause", 90),
            empfind = p.getInt("empfind", 2),
            becken = p.getInt("becken", 25),
            pinArt = p.getInt("pin_art", 0),
            pinZeit = p.getString("pin_zeit", "18:00") ?: "18:00",
            gemeldetUm = p.getLong("um", 0),
        )
    }
}
