package io.rebble.libpebblecommon.datalogging

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Fasst die Anstoesse eines Datalogging-Schubs zu einer Meldung zusammen.
 *
 * Die Uhr schickt einen Schub als viele kleine Pakete. Meldete jedes Paket
 * einzeln, liefen Health-Connect-Sync, Widget und Ansicht je Paket neu. Die
 * Meldung kommt deshalb erst, wenn `ruhe` lang nichts mehr kam - und
 * spaetestens nach `hoechstens`, damit ein langer Rueckstand die Ansicht nicht
 * minutenlang stehen laesst.
 */
internal class Schubsammler(
    scope: CoroutineScope,
    private val ruhe: Duration = 5.seconds,
    private val hoechstens: Duration = 60.seconds,
    private val melde: suspend () -> Unit,
) {
    // CONFLATED: stossAn blockiert nie den Empfang, und mehr als "es gab etwas" braucht es nicht.
    private val anstoesse = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            while (true) {
                anstoesse.receive()
                // Ein Anstoss, den der aeussere Abbruch verschluckt, kam vor der
                // Meldung unten - seine Daten liegen also schon in der Datenbank.
                withTimeoutOrNull(hoechstens) {
                    while (withTimeoutOrNull(ruhe) { anstoesse.receive() } != null) Unit
                }
                melde()
            }
        }
    }

    /** Nach dem Speichern eines Pakets aufrufen, nie davor. */
    fun stossAn() {
        anstoesse.trySend(Unit)
    }
}
