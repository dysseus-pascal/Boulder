package coredevices.pebble.health

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Boulder: wann der Health-Sync die Akte nach dem Recht fragt (IPC, 10-30 ms).
 * Bei Neuem immer, ohne Neues nur nach dem Start und beim Zurueckkehren in die
 * App: dort entzieht man das Recht, und der Schalter soll dann sofort aus. So
 * fragen nur die ~24 Stundenlaeufe ohne Neues am Tag nicht mehr.
 */
internal class Rechteanlass {
    // Der erste Lauf nach dem Prozessstart fragt: das Recht kann inzwischen weg sein.
    private val faellig = MutableStateFlow(true)

    /** Beim Zurueckkehren in die App; laeuft gerade ein Sync, nimmt ihn sein Nachlauf mit. */
    fun merke() {
        faellig.value = true
    }

    /** Verbraucht den Anlass; [gibtNeues] (Datenbank) nur, wenn keiner vorliegt. */
    suspend fun fragen(gibtNeues: suspend () -> Boolean): Boolean =
        faellig.getAndUpdate { false } || gibtNeues()
}
