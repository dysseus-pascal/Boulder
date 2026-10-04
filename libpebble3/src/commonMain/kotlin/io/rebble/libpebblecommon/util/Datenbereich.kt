package io.rebble.libpebblecommon.util

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.plus

/**
 * Boulder: ein Bereich fuer die Datenwege (Aufraeumen, Tageszahlen, Wetter,
 * Gesundheitsdaten) auf dem Scope von libpebble3. Ein Fehler dort wird geloggt,
 * statt die App zu beenden, und reisst keine Geschwister mit.
 *
 * Der Scope von libpebble3 selbst bleibt, wie er ist: an ihm haengen auch die
 * Verbindung zur Uhr und die Benachrichtigungen, deren Verhalten bei einem
 * Fehler unveraendert bleiben soll.
 */
fun CoroutineScope.datenbereich(name: String): CoroutineScope {
    val logger = Logger.withTag(name)
    return this + SupervisorJob(coroutineContext[Job]) + CoroutineName(name) +
        CoroutineExceptionHandler { _, e -> logger.e(e) { "Unhandled exception in $name" } }
}
