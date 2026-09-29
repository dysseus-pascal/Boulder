package coredevices.pebble.kiesel

import android.content.Context
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Die Einstellungen von Kieselsport, wie die Uhr sie meldet.
 *
 * DIE UHR IST DIE EINE STELLE, AN DER SIE GELTEN. Sie meldet sie beim Start
 * der App und nach jeder Aenderung; hier werden sie gemerkt - fuer die
 * Pulszonen der Trainingsansicht und den Einstellungsdialog. Was der Dialog
 * aendert, gilt erst, wenn die Uhr es zurueckgemeldet hat.
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
    ) {
        /** Dieselben Werte - wann gemeldet, zaehlt nicht. */
        fun gleich(o: Stand): Boolean = copy(gemeldetUm = 0) == o.copy(gemeldetUm = 0)
    }

    enum class Ergebnis { Uebernommen, KeineUhr, KeineAntwort }

    private val logger = Logger.withTag("Kieselsport")

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

    /**
     * Schickt die Einstellungen an Kieselsport und wartet, bis die Uhr sie
     * zurueckmeldet.
     *
     * DIE UHR-APP MUSS LAUFEN: nur sie nimmt Einstellungen an, der Dienst in
     * der Firmware hat keinen eigenen Empfang. Also wird sie gestartet, wenn
     * sie nicht schon offen ist - und danach wieder geschlossen, damit die
     * Uhr nicht in Kieselsport stehen bleibt, nur weil am Telefon etwas
     * eingestellt wurde.
     *
     * BESTAETIGT IST ERST DIE MELDUNG, nicht das ACK: die App meldet ihren
     * Stand beim Start (dann noch den alten) und kurz nach jeder Aenderung.
     * Kommt der neue Stand nicht, wird zweimal nachgeschickt - wie in
     * Kiesel-Helper, wo die erste Nachricht oft vor dem Start der App ankam.
     */
    suspend fun sende(context: Context, libPebble: LibPebble, neu: Stand): Ergebnis {
        val uhr = libPebble.watches.value.filterIsInstance<ConnectedPebbleDevice>().firstOrNull()
            ?: return Ergebnis.KeineUhr
        val felder = mapOf<Int, Any>(
            Kieselsport.SP_MAXPULS to neu.maxpuls,
            Kieselsport.SP_PAUSENZIEL to neu.pause,
            Kieselsport.SP_EMPFIND to neu.empfind,
            Kieselsport.SP_BECKEN to neu.becken,
            Kieselsport.SP_PIN_ART to neu.pinArt,
            Kieselsport.SP_PIN_ZEIT to neu.pinZeit,
        )
        val ab = System.currentTimeMillis()
        val warOffen = uhr.runningApp.value == Kieselsport.UUID
        var ergebnis = Ergebnis.KeineAntwort
        for (versuch in 1..3) {
            try {
                if (uhr.runningApp.value != Kieselsport.UUID) {
                    uhr.launchApp(Kieselsport.UUID)
                    withTimeoutOrNull(5_000) { uhr.runningApp.first { it == Kieselsport.UUID } }
                }
                uhr.sendAppMessage(AppMessageData(uhr.transactionSequence.next(), Kieselsport.UUID, felder))
            } catch (e: Exception) {
                logger.w { "Einstellungen senden (Versuch $versuch): ${e.message}" }
            }
            if (bestaetigt(context, neu, ab)) {
                ergebnis = Ergebnis.Uebernommen
                break
            }
        }
        if (!warOffen && uhr.runningApp.value == Kieselsport.UUID) {
            try {
                uhr.stopApp(Kieselsport.UUID)
            } catch (e: Exception) {
                logger.w { "Kieselsport schliessen: ${e.message}" }
            }
        }
        return ergebnis
    }

    /** Wartet bis drei Sekunden auf die Meldung des neuen Stands. */
    private suspend fun bestaetigt(context: Context, neu: Stand, ab: Long): Boolean {
        val bis = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < bis) {
            val s = stand(context)
            if (s.gemeldetUm >= ab && s.gleich(neu)) return true
            delay(250)
        }
        return false
    }
}
