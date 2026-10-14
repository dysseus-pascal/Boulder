package coredevices.pebble.health

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.PebbleDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Wer Boulder oeffnet, will die Zahlen von jetzt sehen, nicht die vom letzten
 * 15-Minuten-Schub der Uhr. Beim Oeffnen fragt Boulder die Uhr deshalb einmal
 * nach ihren Daten (derselbe Endpunkt wie der stuendliche Hintergrund-Sync).
 *
 * Das Oeffnen wird als Wunsch gemerkt und erst in [beobachte] erfuellt: nach
 * einem Kaltstart verbindet die Uhr oft erst Sekunden nach dem Oeffnen, und
 * die Nachfrage soll dann trotzdem kommen.
 */
class UhrNachfrage(
    private val frage: () -> Unit,
    private val clock: Clock,
) {
    private val logger = Logger.withTag("UhrNachfrage")
    private val sperre = Nachfragesperre(2.minutes)
    private val wunsch = MutableStateFlow<Instant?>(null)

    fun beimOeffnen() {
        wunsch.value = clock.now()
    }

    /** Laeuft dauerhaft; nur hier wird gefragt, darum braucht die Sperre keinen Schutz. */
    suspend fun beobachte(uhren: Flow<List<PebbleDevice>>) {
        val verbunden = uhren.map { l -> l.any { it is ConnectedPebbleDevice } }.distinctUntilChanged()
        combine(wunsch, verbunden) { w, v -> w to v }.collect { (w, v) ->
            if (w == null || !v) return@collect
            // Nur diesen Wunsch loeschen - ein neuerer bleibt fuer die Sperrpruefung stehen.
            wunsch.compareAndSet(w, null)
            val jetzt = clock.now()
            // Kommt die Uhr erst viel spaeter, war das kein Oeffnen fuer diese Daten mehr.
            if (jetzt - w > WARTEZEIT) return@collect
            if (!sperre.darf(jetzt)) return@collect
            try {
                logger.d { "Uhr nach Gesundheitsdaten gefragt" }
                frage()
            } catch (e: Exception) {
                logger.w(e) { "Nachfrage an die Uhr fehlgeschlagen" }
            }
        }
    }

    companion object {
        val WARTEZEIT: Duration = 60.seconds
    }
}

/**
 * Hoechstens eine Nachfrage je `abstand`: jeder Wechsel zurueck in die App
 * (auch aus einem Berechtigungsdialog) meldet sich als Oeffnen, und jede
 * Nachfrage laesst die Uhr alles Offene senden.
 */
class Nachfragesperre(private val abstand: Duration) {
    private var zuletzt: Instant? = null

    fun darf(jetzt: Instant): Boolean {
        val z = zuletzt
        // Rueckwaerts gestellte Uhr: nicht bis zur alten Zeit sperren.
        if (z != null && jetzt >= z && jetzt - z < abstand) return false
        zuletzt = jetzt
        return true
    }
}
