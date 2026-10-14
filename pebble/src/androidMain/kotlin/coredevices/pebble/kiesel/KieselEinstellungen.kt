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
    /**
     * Was aeltere Firmware annimmt, solange niemand einen Maximalpuls
     * eingestellt hat. Die Boulder-Firmware rechnet dann selbst nach Tanaka,
     * ohne Abgleich mit dem Alter 30 aus ihren Gesundheitseinstellungen
     * (187) - 190 nur bei einem unsinnigen Alter.
     */
    const val MAXPULS_VORGABE = 190

    data class Stand(
        /** Was die Uhr meldet - eine Zahl auch dann, wenn keiner eingestellt ist. */
        val maxpuls: Int = MAXPULS_VORGABE,
        /** Ob [maxpuls] eingestellt ist; sonst gilt der nach dem Alter (siehe [Herzgrenzen.eingestellt]). */
        val maxpulsGesetzt: Boolean = false,
        val pause: Int = 90,
        val empfind: Int = 2,
        val becken: Int = 25,
        val pinArt: Int = 0,
        val pinZeit: String = "18:00",
        val gemeldetUm: Long = 0,
        /**
         * Die Uhr kennt "nicht gesetzt" und rechnet dann selbst nach Alter
         * (Boulder-Firmware, SP_MAXPULS_GESETZT). Aeltere rechnet mit der Zahl,
         * die das Telefon schickt.
         */
        val uhrRechnetNachAlter: Boolean = false,
    ) {
        /**
         * Dieselben Werte - wann gemeldet, zaehlt nicht. RECHNET DIE UHR SELBST
         * NACH ALTER, zaehlt ihre Zahl dafuer auch nicht: sie nimmt das Alter
         * der Uhr, und "nicht gesetzt" ist dort dasselbe, welche Zahl auch
         * daneben steht.
         */
        fun gleich(o: Stand): Boolean {
            val ohneZahl = (uhrRechnetNachAlter || o.uhrRechnetNachAlter) && !maxpulsGesetzt && !o.maxpulsGesetzt
            fun kern(s: Stand) = s.copy(gemeldetUm = 0, uhrRechnetNachAlter = false, maxpuls = if (ohneZahl) 0 else s.maxpuls)
            return kern(this) == kern(o)
        }
    }

    enum class Ergebnis { Uebernommen, KeineUhr, KeineAntwort }

    private val logger = Logger.withTag("Kieselsport")

    /** true, wenn es eine Einstellungsmeldung war (erkennbar am Maximalpuls). */
    fun vonDerUhr(context: Context, felder: Map<Int, Long>, texte: Map<Int, String>): Boolean {
        val maxpuls = felder[Kieselsport.SP_MAXPULS] ?: return false
        val gesetzt = felder[Kieselsport.SP_MAXPULS_GESETZT]
        val e = context.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit()
        // Ohne den Vermerk ist es aeltere Firmware: dann gilt wieder die Wahl am Telefon.
        if (gesetzt != null) e.putBoolean("maxpuls_gesetzt", gesetzt != 0L) else e.remove("maxpuls_gesetzt")
        e.putInt("maxpuls", maxpuls.toInt())
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
        val maxpuls = p.getInt("maxpuls", MAXPULS_VORGABE)
        // Die Wahl am Telefon gilt fuer den Wert, der mit ihr zur Uhr ging.
        val wahl = if (p.contains("maxpuls_wahl")) p.getBoolean("maxpuls_wahl", false) else null
        val wahlWert = if (p.contains("maxpuls_wahl_wert")) p.getInt("maxpuls_wahl_wert", 0) else null
        val wartend = if (p.contains("maxpuls_wartend")) p.getBoolean("maxpuls_wartend", false) else null
        val wartendWert = if (p.contains("maxpuls_wartend_wert")) p.getInt("maxpuls_wartend_wert", 0) else null
        // DIE UHR WEISS ES, wenn sie den Vermerk schickt - dann braucht es keine
        // Vermutung aus der Wahl am Telefon und der Zahl 190.
        val vonDerUhr = if (p.contains("maxpuls_gesetzt")) p.getBoolean("maxpuls_gesetzt", false) else null
        return Stand(
            maxpuls = maxpuls,
            maxpulsGesetzt = vonDerUhr ?: (Herzgrenzen.eingestellt(maxpuls, wahl, wahlWert, wartend, wartendWert) != null),
            pause = p.getInt("pause", 90),
            empfind = p.getInt("empfind", 2),
            becken = p.getInt("becken", 25),
            pinArt = p.getInt("pin_art", 0),
            pinZeit = p.getString("pin_zeit", "18:00") ?: "18:00",
            gemeldetUm = p.getLong("um", 0),
            uhrRechnetNachAlter = vonDerUhr != null,
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
     *
     * "NACH ALTER" GEHT ALS VERMERK MIT (SP_MAXPULS_GESETZT = 0): die
     * Boulder-Firmware rechnet dann selbst nach dem Alter ihrer
     * Gesundheitseinstellungen und folgt ihm, wenn es sich aendert. Aeltere
     * Firmware ueberliest den Vermerk und nimmt die Zahl, mit der das Telefon
     * rechnet ([neu].maxpuls) - deshalb geht sie immer mit. Ob sie gewaehlt
     * oder geschaetzt ist, merkt sich das Telefon fuer aeltere Firmware zu
     * dieser Zahl: vor dem Senden als wartend, nach der Bestaetigung als
     * gueltig (siehe [Herzgrenzen.eingestellt]).
     */
    suspend fun sende(context: Context, libPebble: LibPebble, neu: Stand): Ergebnis {
        val uhr = libPebble.watches.value.filterIsInstance<ConnectedPebbleDevice>().firstOrNull()
            ?: return Ergebnis.KeineUhr
        val felder = mapOf<Int, Any>(
            Kieselsport.SP_MAXPULS to neu.maxpuls,
            Kieselsport.SP_MAXPULS_GESETZT to if (neu.maxpulsGesetzt) 1 else 0,
            Kieselsport.SP_PAUSENZIEL to neu.pause,
            Kieselsport.SP_EMPFIND to neu.empfind,
            Kieselsport.SP_BECKEN to neu.becken,
            Kieselsport.SP_PIN_ART to neu.pinArt,
            Kieselsport.SP_PIN_ZEIT to neu.pinZeit,
        )
        context.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit()
            .putBoolean("maxpuls_wartend", neu.maxpulsGesetzt)
            .putInt("maxpuls_wartend_wert", neu.maxpuls)
            .apply()
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
                context.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit()
                    .putBoolean("maxpuls_wahl", neu.maxpulsGesetzt)
                    .putInt("maxpuls_wahl_wert", neu.maxpuls)
                    .remove("maxpuls_wartend")
                    .remove("maxpuls_wartend_wert")
                    .apply()
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

    /**
     * Wartet bis drei Sekunden auf die Meldung des neuen Stands. Aeltere
     * Firmware meldet nur Zahlen, nicht die Wahl - dann zaehlt die Wahl am
     * Telefon; die Boulder-Firmware meldet sie mit ([Stand.uhrRechnetNachAlter]).
     */
    private suspend fun bestaetigt(context: Context, neu: Stand, ab: Long): Boolean {
        val bis = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < bis) {
            val s = stand(context).let { if (it.uhrRechnetNachAlter) it else it.copy(maxpulsGesetzt = neu.maxpulsGesetzt) }
            if (s.gemeldetUm >= ab && s.gleich(neu)) return true
            delay(250)
        }
        return false
    }
}
