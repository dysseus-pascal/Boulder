package coredevices.pebble.kiesel

import android.util.Log
import kotlin.coroutines.cancellation.CancellationException

/**
 * Ein Zugriff, dessen Fehler die Seite nicht kippen soll: gelingt er nicht,
 * steht null da und eine Warnung mit [tag] und [was] im Log. Statt
 * runCatching - das schluckte jeden Fehler still, und eine leere Seite liess
 * sich spaeter nicht erklaeren.
 *
 * DER ABBRUCH GEHT DURCH: verlaesst man die Seite, waehrend sie laedt, endet
 * das Laden gleich, statt jede weitere Abfrage noch anzustossen.
 */
internal inline fun <T> versuche(tag: String, was: String, block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(tag, "$was: " + e.message)
    null
}
